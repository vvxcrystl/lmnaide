import { createServer } from "node:http";
import { DatabaseSync } from "node:sqlite";
import {
  randomBytes,
  randomUUID,
  scrypt as scryptCallback,
  timingSafeEqual,
  createHash,
} from "node:crypto";
import { promisify } from "node:util";
import { mkdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve, sep, extname } from "node:path";
import { fileURLToPath } from "node:url";
const scrypt = promisify(scryptCallback);
const hash = (value) => createHash("sha256").update(value).digest("hex");
const fail = (status, message) => {
  throw Object.assign(new Error(message), { status });
};
const enums = {
  importance: ["LOW", "MEDIUM", "HIGH"],
  recurrence: ["NONE", "DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY", "YEARLY"],
  kind: ["EVENT", "TASK"],
};
function validate(change) {
  if (
    !change ||
    !["calendar", "event"].includes(change.kind) ||
    typeof change.id !== "string" ||
    change.id.length > 200 ||
    !change.id ||
    !Number.isSafeInteger(change.baseVersion) ||
    change.baseVersion < 0 ||
    typeof change.deleted !== "boolean"
  )
    fail(400, "Invalid change");
  if (change.deleted) return null;
  const d = change.data;
  if (!d || typeof d !== "object" || Array.isArray(d))
    fail(400, "Missing record");
  const text = (key, max, required = false) => {
    if (
      typeof d[key] !== "string" ||
      d[key].length > max ||
      (required && !d[key].trim())
    )
      fail(400, `Invalid ${key}`);
  };
  const int = (key) => {
    if (!Number.isSafeInteger(d[key])) fail(400, `Invalid ${key}`);
  };
  if (change.kind === "calendar") {
    text("name", 200, true);
    text("keywords", 2000);
    int("color");
    if (typeof d.visible !== "boolean") fail(400, "Invalid visibility");
    if (
      d.defaultImportance != null &&
      !enums.importance.includes(d.defaultImportance)
    )
      fail(400, "Invalid importance");
    return {
      name: d.name,
      color: d.color,
      visible: d.visible,
      keywords: d.keywords,
      defaultImportance: d.defaultImportance ?? null,
    };
  }
  text("title", 1000, true);
  text("description", 20000);
  text("location", 2000);
  text("calendarId", 200, true);
  int("start");
  int("end");
  if (
    Math.abs(d.start) > 8640000000000000 ||
    Math.abs(d.end) > 8640000000000000 ||
    d.end < d.start ||
    typeof d.allDay !== "boolean"
  )
    fail(400, "Invalid event dates");
  for (const key of ["importance", "recurrence", "kind"])
    if (
      !(key === "importance" && d[key] == null) &&
      !enums[key].includes(d[key])
    )
      fail(400, `Invalid ${key}`);
  for (const key of ["color", "recurrenceUntil"])
    if (d[key] != null && !Number.isSafeInteger(d[key]))
      fail(400, `Invalid ${key}`);
  for (const key of ["exceptions", "reminders", "completions"])
    if (
      !Array.isArray(d[key]) ||
      d[key].length > 10000 ||
      !d[key].every(Number.isSafeInteger)
    )
      fail(400, `Invalid ${key}`);
  // Device-specific contact lookup URIs never leave the device.
  return Object.fromEntries(
    [
      "calendarId",
      "title",
      "description",
      "location",
      "start",
      "end",
      "allDay",
      "color",
      "recurrence",
      "recurrenceUntil",
      "exceptions",
      "reminders",
      "kind",
      "completions",
      "importance",
    ].map((k) => [k, d[k] ?? null]),
  );
}
export function createApp({
  dbPath = process.env.DB_PATH || "./data/calendar.sqlite",
  allowRegistration = process.env.ALLOW_REGISTRATION !== "false",
  publicUrl = process.env.PUBLIC_URL || "",
} = {}) {
  if (publicUrl) {
    const url = new URL(publicUrl);
    if (
      !["http:", "https:"].includes(url.protocol) ||
      url.username ||
      url.password
    )
      throw new Error(
        "PUBLIC_URL must be an HTTP(S) server address without credentials",
      );
    publicUrl = url.origin;
  }
  if (dbPath !== ":memory:") mkdirSync(dirname(dbPath), { recursive: true });
  const db = new DatabaseSync(dbPath);
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,password TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,userId TEXT NOT NULL REFERENCES users(id),expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS records(userId TEXT NOT NULL REFERENCES users(id),kind TEXT NOT NULL,id TEXT NOT NULL,version INTEGER NOT NULL,deleted INTEGER NOT NULL,data TEXT,PRIMARY KEY(userId,kind,id));
    CREATE INDEX IF NOT EXISTS record_changes ON records(userId,version);
    CREATE TABLE IF NOT EXISTS history(id TEXT PRIMARY KEY,userId TEXT NOT NULL,kind TEXT NOT NULL,recordId TEXT NOT NULL,data TEXT,deleted INTEGER NOT NULL,created INTEGER NOT NULL,reason TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS operations(userId TEXT NOT NULL,operationId TEXT NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(userId,operationId));
    CREATE TABLE IF NOT EXISTS shares(userId TEXT NOT NULL REFERENCES users(id),eventId TEXT NOT NULL,token TEXT UNIQUE NOT NULL,PRIMARY KEY(userId,eventId));
    CREATE TRIGGER IF NOT EXISTS revoke_inserted_tombstones AFTER INSERT ON records WHEN NEW.kind='event' AND NEW.deleted=1 BEGIN
      DELETE FROM shares WHERE userId=NEW.userId AND eventId=NEW.id;
    END;
    CREATE TRIGGER IF NOT EXISTS revoke_deleted_shares AFTER UPDATE OF deleted ON records WHEN NEW.kind='event' AND NEW.deleted=1 BEGIN
      DELETE FROM shares WHERE userId=NEW.userId AND eventId=NEW.id;
    END;
    CREATE TABLE IF NOT EXISTS profiles(userId TEXT PRIMARY KEY REFERENCES users(id),photo TEXT);
    CREATE TABLE IF NOT EXISTS counter(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL);
    INSERT OR IGNORE INTO counter VALUES(1,0);`);
  const limits = new Map();
  const encode = (row) => ({
    kind: row.kind,
    id: row.id,
    version: row.version,
    deleted: !!row.deleted,
    data: row.data ? JSON.parse(row.data) : null,
  });
  const nextVersion = () =>
    db
      .prepare("UPDATE counter SET value=value+1 WHERE id=1 RETURNING value")
      .get().value;
  const archive = (userId, kind, id, data, deleted, reason) =>
    db
      .prepare("INSERT INTO history VALUES(?,?,?,?,?,?,?,?)")
      .run(randomUUID(), userId, kind, id, data, deleted, Date.now(), reason);
  const publicDir = join(dirname(fileURLToPath(import.meta.url)), "public");
  const server = createServer(async (req, res) => {
    const send = (status, data) => {
      res.writeHead(status, {
        "Content-Type": "application/json",
        "Cache-Control": "no-store",
      });
      res.end(JSON.stringify(data));
    };
    try {
      res.setHeader("X-Content-Type-Options", "nosniff");
      res.setHeader("Referrer-Policy", "no-referrer");
      res.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
      );
      res.setHeader("X-Robots-Tag", "noindex, nofollow");
      const path = new URL(req.url, "http://localhost").pathname;
      if (req.method === "GET" && path === "/health")
        return send(200, { ok: true, version: "0.1.0" });
      if (req.method === "GET" && path.startsWith("/api/shared/")) {
        const shareToken = path.slice("/api/shared/".length);
        if (!/^[a-f0-9]{64}$/.test(shareToken))
          fail(404, "This link is no longer available.");
        const row = db
          .prepare(
            "SELECT r.data FROM shares s JOIN records r ON r.userId=s.userId AND r.id=s.eventId AND r.kind='event' AND r.deleted=0 WHERE s.token=?",
          )
          .get(shareToken);
        if (!row) fail(404, "This link is no longer available.");
        const event = JSON.parse(row.data);
        return send(200, {
          event: Object.fromEntries(
            [
              "title",
              "description",
              "location",
              "start",
              "end",
              "allDay",
              "recurrence",
              "recurrenceUntil",
              "kind",
            ].map((key) => [key, event[key]]),
          ),
        });
      }
      if (req.method === "GET" && !path.startsWith("/api/")) {
        const types = {
          ".html": "text/html",
          ".js": "text/javascript",
          ".css": "text/css",
          ".svg": "image/svg+xml",
          ".png": "image/png",
          ".ttf": "font/ttf",
          ".woff2": "font/woff2",
          ".ico": "image/x-icon",
          ".txt": "text/plain",
        };
        let file;
        try {
          file = resolve(
            publicDir,
            "." +
              decodeURIComponent(
                path === "/"
                  ? "/index.html"
                  : /^\/s\/[a-f0-9]{64}$/.test(path)
                    ? "/share.html"
                    : path,
              ),
          );
        } catch {
          fail(400, "Invalid path");
        }
        if (!file.startsWith(publicDir + sep) || !types[extname(file)])
          fail(404, "Not found");
        let content;
        try {
          content = readFileSync(file);
        } catch {
          fail(404, "Not found");
        }
        res.writeHead(200, {
          "Content-Type": types[extname(file)],
          "Cache-Control": "no-cache",
        });
        return res.end(content);
      }
      if (!path.startsWith("/api/")) fail(404, "Not found");
      const body = async () => {
        let size = 0;
        const chunks = [];
        for await (const chunk of req) {
          size += chunk.length;
          if (size > 4 * 1024 * 1024) fail(413, "Request too large");
          chunks.push(chunk);
        }
        try {
          return JSON.parse(Buffer.concat(chunks).toString());
        } catch {
          fail(400, "Invalid JSON");
        }
      };
      if (
        req.method === "POST" &&
        ["/api/register", "/api/login"].includes(path)
      ) {
        const ip = req.socket.remoteAddress;
        if (limits.size > 10000)
          for (const [key, value] of limits)
            if (value.until < Date.now()) limits.delete(key);
        const limit = limits.get(ip) || { count: 0, until: Date.now() + 60000 };
        if (limit.until < Date.now()) {
          limit.count = 0;
          limit.until = Date.now() + 60000;
        }
        limits.set(ip, limit);
        if (++limit.count > 30)
          fail(429, "Too many attempts. Try again in a minute.");
        const b = await body();
        if (!b || typeof b !== "object" || Array.isArray(b))
          fail(400, "Invalid request");
        const email =
          typeof b.email === "string" ? b.email.trim().toLowerCase() : "";
        if (
          !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
          email.length > 254 ||
          typeof b.password !== "string" ||
          b.password.length < 10 ||
          b.password.length > 256
        )
          fail(
            400,
            "Use an email address and a password of 10–256 characters.",
          );
        let user = db.prepare("SELECT * FROM users WHERE email=?").get(email);
        if (path === "/api/register") {
          if (!allowRegistration)
            fail(403, "New accounts are disabled on this server.");
          if (user) fail(409, "An account already exists. Sign in instead.");
          const salt = randomBytes(16).toString("hex");
          const key = await scrypt(b.password, salt, 64);
          user = {
            id: randomUUID(),
            email,
            password: `${salt}:${key.toString("hex")}`,
          };
          try {
            db.prepare("INSERT INTO users VALUES(?,?,?)").run(
              user.id,
              email,
              user.password,
            );
          } catch {
            fail(409, "An account already exists. Sign in instead.");
          }
        } else {
          const [salt, stored] = (
            user?.password || `${"0".repeat(32)}:${"0".repeat(128)}`
          ).split(":");
          const key = await scrypt(b.password, salt, 64);
          if (!timingSafeEqual(key, Buffer.from(stored, "hex")) || !user)
            fail(401, "Email or password is incorrect.");
        }
        const token = randomBytes(32).toString("hex");
        db.prepare("DELETE FROM sessions WHERE expires<?").run(Date.now());
        db.prepare("INSERT INTO sessions VALUES(?,?,?)").run(
          hash(token),
          user.id,
          Date.now() + 90 * 86400000,
        );
        return send(200, { token, email, userId: user.id });
      }
      const token = req.headers.authorization?.replace(/^Bearer /, "");
      const session =
        token &&
        db
          .prepare("SELECT * FROM sessions WHERE token=? AND expires>?")
          .get(hash(token), Date.now());
      if (!session) fail(401, "Session expired. Sign in again.");
      const userId = session.userId;
      if (req.method === "POST" && path === "/api/share") {
        const b = await body();
        if (
          !b ||
          typeof b.eventId !== "string" ||
          !["status", "create", "revoke"].includes(b.action)
        )
          fail(400, "Invalid share request");
        const event = db
          .prepare(
            "SELECT id FROM records WHERE userId=? AND kind='event' AND id=? AND deleted=0",
          )
          .get(userId, b.eventId);
        if (!event) fail(404, "Sync this event before sharing it.");
        if (b.action === "revoke")
          db.prepare("DELETE FROM shares WHERE userId=? AND eventId=?").run(
            userId,
            b.eventId,
          );
        if (b.action === "create")
          db.prepare("INSERT OR IGNORE INTO shares VALUES(?,?,?)").run(
            userId,
            b.eventId,
            randomBytes(32).toString("hex"),
          );
        const share = db
          .prepare("SELECT token FROM shares WHERE userId=? AND eventId=?")
          .get(userId, b.eventId);
        const sharePath = share ? "/s/" + share.token : null;
        return send(200, {
          active: !!share,
          path: sharePath,
          url:
            sharePath && publicUrl ? new URL(sharePath, publicUrl).href : null,
        });
      }
      // A small profile photo shown in the web app's top bar, as a data URL.
      if (path === "/api/profile") {
        if (req.method === "POST") {
          const b = await body();
          const photo = b?.photo ?? null;
          if (
            photo !== null &&
            (typeof photo !== "string" ||
              photo.length > 200000 ||
              !/^data:image\/(?:jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/.test(
                photo,
              ))
          )
            fail(400, "Use a JPEG, PNG or WebP image under 150 KB.");
          db.prepare(
            "INSERT INTO profiles VALUES(?,?) ON CONFLICT(userId) DO UPDATE SET photo=excluded.photo",
          ).run(userId, photo);
        } else if (req.method !== "GET") fail(404, "Not found");
        const profile = db
          .prepare("SELECT photo FROM profiles WHERE userId=?")
          .get(userId);
        return send(200, { photo: profile?.photo ?? null });
      }
      if (req.method === "POST" && path === "/api/logout") {
        db.prepare("DELETE FROM sessions WHERE token=?").run(hash(token));
        return send(200, { ok: true });
      }
      if (req.method === "GET" && path === "/api/history")
        return send(200, {
          history: db
            .prepare(
              "SELECT * FROM history WHERE userId=? ORDER BY created DESC LIMIT 200",
            )
            .all(userId)
            .map((r) => ({
              ...r,
              data: r.data ? JSON.parse(r.data) : null,
              userId: undefined,
            })),
        });
      if (req.method !== "POST" || path !== "/api/sync") fail(404, "Not found");
      const b = await body();
      if (!b || typeof b !== "object" || Array.isArray(b))
        fail(400, "Invalid request");
      if (
        !Number.isSafeInteger(b.cursor) ||
        b.cursor < 0 ||
        !Array.isArray(b.changes) ||
        b.changes.length > 1000
      )
        fail(400, "Invalid sync request");
      const changes = b.changes
        .map((c) => {
          if (
            typeof c.operationId !== "string" ||
            !c.operationId ||
            c.operationId.length > 250
          )
            fail(400, "Invalid operation");
          return { ...c, data: validate(c) };
        })
        .sort(
          (a, b) => Number(a.kind === "event") - Number(b.kind === "event"),
        );
      const conflicts = [];
      db.exec("BEGIN IMMEDIATE");
      try {
        for (const c of changes) {
          const fingerprint = hash(JSON.stringify(c));
          const prior = db
            .prepare(
              "SELECT fingerprint FROM operations WHERE userId=? AND operationId=?",
            )
            .get(userId, c.operationId);
          if (prior) {
            if (prior.fingerprint !== fingerprint)
              fail(409, "Operation ID was reused");
            continue;
          }
          const current = db
            .prepare("SELECT * FROM records WHERE userId=? AND kind=? AND id=?")
            .get(userId, c.kind, c.id);
          if (current && c.baseVersion !== current.version) {
            if (
              current.deleted === Number(c.deleted) &&
              current.data === (c.deleted ? null : JSON.stringify(c.data))
            ) {
              // Identical seeded records and retried semantic edits need no new version.
            } else {
              archive(
                userId,
                c.kind,
                c.id,
                JSON.stringify(c.data),
                Number(c.deleted),
                "conflict",
              );
              conflicts.push(c.id);
            }
          } else {
            if (c.kind === "event" && !c.deleted) {
              const parent = db
                .prepare(
                  "SELECT deleted FROM records WHERE userId=? AND kind='calendar' AND id=?",
                )
                .get(userId, c.data.calendarId);
              if (!parent || parent.deleted) {
                archive(
                  userId,
                  c.kind,
                  c.id,
                  JSON.stringify(c.data),
                  0,
                  "deleted-calendar",
                );
                conflicts.push(c.id);
                db.prepare(
                  "INSERT OR REPLACE INTO records VALUES(?,?,?,?,?,?)",
                ).run(userId, c.kind, c.id, nextVersion(), 1, null);
              } else {
                if (current)
                  archive(
                    userId,
                    c.kind,
                    c.id,
                    current.data,
                    current.deleted,
                    "previous",
                  );
                db.prepare(
                  "INSERT OR REPLACE INTO records VALUES(?,?,?,?,?,?)",
                ).run(
                  userId,
                  c.kind,
                  c.id,
                  nextVersion(),
                  0,
                  JSON.stringify(c.data),
                );
              }
            } else {
              if (current)
                archive(
                  userId,
                  c.kind,
                  c.id,
                  current.data,
                  current.deleted,
                  "previous",
                );
              db.prepare(
                "INSERT OR REPLACE INTO records VALUES(?,?,?,?,?,?)",
              ).run(
                userId,
                c.kind,
                c.id,
                nextVersion(),
                Number(c.deleted),
                c.deleted ? null : JSON.stringify(c.data),
              );
              if (c.kind === "calendar" && c.deleted) {
                for (const e of db
                  .prepare(
                    "SELECT * FROM records WHERE userId=? AND kind='event' AND deleted=0 AND json_extract(data,'$.calendarId')=?",
                  )
                  .all(userId, c.id)) {
                  archive(userId, "event", e.id, e.data, 0, "calendar-deleted");
                  db.prepare(
                    "UPDATE records SET deleted=1,data=NULL,version=? WHERE userId=? AND kind='event' AND id=?",
                  ).run(nextVersion(), userId, e.id);
                }
              }
            }
          }
          db.prepare("INSERT INTO operations VALUES(?,?,?)").run(
            userId,
            c.operationId,
            fingerprint,
          );
        }
        // Also return each submitted record, even if its version predates the cursor (conflicts/retries).
        const rows = db
          .prepare(
            "SELECT * FROM records WHERE userId=? AND version>? ORDER BY version",
          )
          .all(userId, b.cursor);
        const map = new Map(rows.map((r) => [r.kind + ":" + r.id, r]));
        for (const c of changes) {
          const row = db
            .prepare("SELECT * FROM records WHERE userId=? AND kind=? AND id=?")
            .get(userId, c.kind, c.id);
          if (row) map.set(row.kind + ":" + row.id, row);
        }
        const cursor = db
          .prepare("SELECT value FROM counter WHERE id=1")
          .get().value;
        db.exec("COMMIT");
        return send(200, {
          cursor,
          records: [...map.values()].map(encode),
          conflicts,
        });
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      }
    } catch (error) {
      if (!res.headersSent)
        send(error.status || 500, {
          error: error.status ? error.message : "Server error",
        });
      if (!error.status) console.error(error);
    }
  });
  server.requestTimeout = 30000;
  server.headersTimeout = 15000;
  return {
    server,
    db,
    close: () =>
      new Promise((resolve) =>
        server.close(() => {
          db.close();
          resolve();
        }),
      ),
  };
}
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const app = createApp();
  const port = Number(process.env.PORT || 3000);
  app.server.listen(port, process.env.HOST || "0.0.0.0", () =>
    console.log(`Calendar Cloud listening on ${port}`),
  );
  for (const signal of ["SIGINT", "SIGTERM"])
    process.on(signal, () => app.close().then(() => process.exit(0)));
}
