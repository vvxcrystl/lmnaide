import { createServer } from "node:http";
import { DatabaseSync } from "node:sqlite";
import { BlockList, isIP } from "node:net";
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
const MIN_EVENT_TIME = Date.UTC(1900, 0, 1);
const MAX_EVENT_TIME = Date.UTC(2201, 0, 1) - 1;
const MIN_EVENT_DAY = MIN_EVENT_TIME / 86400000;
const MAX_EVENT_DAY = Date.UTC(2201, 0, 1) / 86400000 - 1;
const fail = (status, message) => {
  throw Object.assign(new Error(message), { status });
};
function trustedProxyList(value) {
  const list = new BlockList();
  const entries = Array.isArray(value)
    ? value
    : String(value).split(",");
  for (const entry of entries.map((item) => String(item).trim()).filter(Boolean)) {
    const slash = entry.lastIndexOf("/");
    const address = slash < 0 ? entry : entry.slice(0, slash);
    const type = isIP(address);
    if (!type) throw new Error(`Invalid TRUSTED_PROXIES entry: ${entry}`);
    if (slash < 0) list.addAddress(address, type === 4 ? "ipv4" : "ipv6");
    else {
      const prefix = Number(entry.slice(slash + 1));
      if (
        !Number.isInteger(prefix) ||
        prefix < 0 ||
        prefix > (type === 4 ? 32 : 128)
      )
        throw new Error(`Invalid TRUSTED_PROXIES entry: ${entry}`);
      list.addSubnet(address, prefix, type === 4 ? "ipv4" : "ipv6");
    }
  }
  return list;
}
function normalizeIp(value) {
  const address = String(value || "").trim().replace(/^\[|\]$/g, "");
  const mapped = address.match(/^::ffff:(\d{1,3}(?:\.\d{1,3}){3})$/i);
  return mapped ? mapped[1] : address;
}
function clientIp(req, trustedProxies) {
  const peer = normalizeIp(req.socket.remoteAddress);
  const peerType = isIP(peer);
  if (!peerType || !trustedProxies.check(peer, peerType === 4 ? "ipv4" : "ipv6"))
    return peer || "unknown";
  const forwarded = req.headers["x-forwarded-for"];
  const candidate = Array.isArray(forwarded) ? forwarded[0] : forwarded;
  const address = String(candidate || "").split(",")[0]?.trim();
  return isIP(address) ? normalizeIp(address) : peer || "unknown";
}
function validateUsername(value) {
  if (value === null || value === "") return null;
  if (typeof value !== "string") fail(400, "Invalid username.");
  const username = value.trim();
  if (!/^[A-Za-z0-9_]{3,30}$/.test(username))
    fail(400, "Use 3–30 letters, numbers or underscores for your username.");
  return username;
}
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
    if (d.color < -2147483648 || d.color > 2147483647)
      fail(400, "Invalid color");
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
  text("title", 1000);
  text("description", 20000);
  text("location", 2000);
  text("calendarId", 200, true);
  int("start");
  int("end");
  if (
    d.start < MIN_EVENT_TIME ||
    d.start > MAX_EVENT_TIME ||
    d.end < MIN_EVENT_TIME ||
    d.end > MAX_EVENT_TIME ||
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
  if (
    d.color != null &&
    (!Number.isInteger(d.color) || d.color < -2147483648 || d.color > 2147483647)
  )
    fail(400, "Invalid color");
  if (
    d.recurrenceUntil != null &&
    (!Number.isSafeInteger(d.recurrenceUntil) ||
      d.recurrenceUntil < MIN_EVENT_DAY ||
      d.recurrenceUntil > MAX_EVENT_DAY)
  )
    fail(400, "Invalid recurrenceUntil");
  for (const key of ["exceptions", "completions"])
    if (
      !Array.isArray(d[key]) ||
      d[key].length > 10000 ||
      !d[key].every(
        (day) =>
          Number.isSafeInteger(day) && day >= MIN_EVENT_DAY && day <= MAX_EVENT_DAY,
      )
    )
      fail(400, `Invalid ${key}`);
  if (
    !Array.isArray(d.reminders) ||
    d.reminders.length > 100 ||
    !d.reminders.every(
      (minutes) => Number.isSafeInteger(minutes) && minutes >= 0 && minutes <= 525600,
    )
  )
    fail(400, "Invalid reminders");
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
export function resolvePublicUrl({ publicUrl = "", domain = "" } = {}) {
  if (!publicUrl && domain) {
    // A single DNS hostname also keeps the Caddy site address unambiguous.
    if (
      domain.length > 253 ||
      /[^a-z0-9.-]/i.test(domain) ||
      !domain.split(".").every((label) =>
        /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(label),
      )
    )
      throw new Error(
        "CALENDAR_DOMAIN must be a single DNS hostname, such as calander.domain.com (no scheme, port or path)",
      );
    publicUrl = `https://${domain}`;
  }
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
  return publicUrl;
}
export function createApp({
  dbPath = process.env.DB_PATH || "./data/calendar.sqlite",
  allowRegistration = process.env.ALLOW_REGISTRATION !== "false",
  publicUrl = process.env.PUBLIC_URL || "",
  domain = process.env.CALENDAR_DOMAIN || "",
  trustedProxies = process.env.TRUSTED_PROXIES || "127.0.0.1,::1",
} = {}) {
  publicUrl = resolvePublicUrl({ publicUrl, domain });
  const trustedProxyNets = trustedProxyList(trustedProxies);
  if (dbPath !== ":memory:") mkdirSync(dirname(dbPath), { recursive: true });
  const db = new DatabaseSync(dbPath);
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA busy_timeout=5000;
    CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE NOT NULL,password TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS sessions(token TEXT PRIMARY KEY,userId TEXT NOT NULL REFERENCES users(id),expires INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS records(userId TEXT NOT NULL REFERENCES users(id),kind TEXT NOT NULL,id TEXT NOT NULL,version INTEGER NOT NULL,deleted INTEGER NOT NULL,data TEXT,PRIMARY KEY(userId,kind,id));
    CREATE INDEX IF NOT EXISTS record_changes ON records(userId,version);
    CREATE TABLE IF NOT EXISTS history(id TEXT PRIMARY KEY,userId TEXT NOT NULL,kind TEXT NOT NULL,recordId TEXT NOT NULL,data TEXT,deleted INTEGER NOT NULL,created INTEGER NOT NULL,reason TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS history_user_created ON history(userId,created DESC);
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
  if (!db.prepare("PRAGMA table_info(users)").all().some((c) => c.name === "username"))
    db.exec("ALTER TABLE users ADD COLUMN username TEXT COLLATE NOCASE");
  db.exec("CREATE UNIQUE INDEX IF NOT EXISTS user_username ON users(username COLLATE NOCASE)");
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
        const takeLimit = (key, maximum) => {
          const now = Date.now();
          if (limits.size > 10000)
            for (const [name, value] of limits)
              if (value.until < now) limits.delete(name);
          const limit = limits.get(key) || { count: 0, until: now + 60000 };
          if (limit.until < now) {
            limit.count = 0;
            limit.until = now + 60000;
          }
          limits.set(key, limit);
          if (++limit.count > maximum)
            fail(429, "Too many attempts. Try again in a minute.");
        };
        takeLimit(`ip:${clientIp(req, trustedProxyNets)}`, 30);
        const b = await body();
        if (!b || typeof b !== "object" || Array.isArray(b))
          fail(400, "Invalid request");
        const email =
          typeof b.email === "string" ? b.email.trim().toLowerCase() : "";
        if (email) takeLimit(`account:${email}`, 10);
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
          const username = b.username === undefined ? null : validateUsername(b.username);
          const salt = randomBytes(16).toString("hex");
          const key = await scrypt(b.password, salt, 64);
          user = {
            id: randomUUID(),
            email,
            username,
            password: `${salt}:${key.toString("hex")}`,
          };
          try {
            db.prepare("INSERT INTO users(id,email,password,username) VALUES(?,?,?,?)").run(
              user.id,
              email,
              user.password,
              username,
            );
          } catch (e) {
            if (e.message.includes("users.username")) fail(409, "That username is already taken.");
            if (e.message.includes("users.email")) fail(409, "An account already exists. Sign in instead.");
            throw e;
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
        return send(200, { token, email, userId: user.id, username: user.username ?? null });
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
          if (!b || typeof b !== "object" || Array.isArray(b)) fail(400, "Invalid request");
          const hasPhoto = Object.hasOwn(b, "photo");
          const photo = b.photo;
          const hasUsername = Object.hasOwn(b, "username");
          const username = hasUsername ? validateUsername(b.username) : null;
          if (hasPhoto && photo !== null &&
            (typeof photo !== "string" || photo.length > 200000 ||
              !/^data:image\/(?:jpeg|png|webp);base64,[A-Za-z0-9+/=]+$/.test(photo)))
            fail(400, "Use a JPEG, PNG or WebP image under 150 KB.");
          if (hasUsername) {
            try {
              db.prepare("UPDATE users SET username=? WHERE id=?").run(username, userId);
            } catch (e) {
              if (e.message.includes("users.username")) fail(409, "That username is already taken.");
              throw e;
            }
          }
          if (hasPhoto) db.prepare(
            "INSERT INTO profiles VALUES(?,?) ON CONFLICT(userId) DO UPDATE SET photo=excluded.photo",
          ).run(userId, photo);
        } else if (req.method !== "GET") fail(404, "Not found");
        const profile = db
          .prepare("SELECT photo FROM profiles WHERE userId=?")
          .get(userId);
        return send(200, { photo: profile?.photo ?? null, username: db.prepare("SELECT username FROM users WHERE id=?").get(userId).username });
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
          const operationId =
            typeof c?.operationId === "string" ? c.operationId : undefined;
          try {
            if (
              typeof c?.operationId !== "string" ||
              !c.operationId ||
              c.operationId.length > 250
            )
              fail(400, "Invalid operation");
            if (
              c.group !== undefined &&
              (typeof c.group !== "string" ||
                c.group.length < 1 ||
                c.group.length > 250)
            )
              fail(400, "Invalid group");
            return { ...c, data: validate(c) };
          } catch (error) {
            if (operationId !== undefined) error.operationId = operationId;
            throw error;
          }
        })
        .sort(
          (a, b) => Number(a.kind === "event") - Number(b.kind === "event"),
        );
      const conflicts = [];
      db.exec("BEGIN IMMEDIATE");
      try {
        const fingerprints = new Map();
        const pending = new Set();
        const requestOperations = new Map();
        const fingerprintFor = (c) => c.group
          ? hash(JSON.stringify({
            kind: c.kind,
            id: c.id,
            baseVersion: c.baseVersion,
            deleted: c.deleted,
            data: c.data,
            operationId: c.operationId,
            group: c.group,
          }))
          // Keep the old no-group fingerprint for retries already stored before upgrade.
          : hash(JSON.stringify(c));
        for (const c of changes) {
          const fingerprint = fingerprintFor(c);
          fingerprints.set(c, fingerprint);
          const prior = db
            .prepare(
              "SELECT fingerprint FROM operations WHERE userId=? AND operationId=?",
            )
            .get(userId, c.operationId);
          const inRequest = requestOperations.get(c.operationId);
          if (prior && prior.fingerprint !== fingerprint)
            fail(409, "Operation ID was reused");
          if (inRequest && inRequest !== fingerprint)
            fail(409, "Operation ID was reused");
          if (!prior && !inRequest) pending.add(c);
          requestOperations.set(c.operationId, fingerprint);
        }
        const applyChange = (c, operationConflicts) => {
          const fingerprint = fingerprints.get(c);
          const current = db
            .prepare("SELECT * FROM records WHERE userId=? AND kind=? AND id=?")
            .get(userId, c.kind, c.id);
          let conflict = false;
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
              operationConflicts.push(c.id);
              conflict = true;
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
                operationConflicts.push(c.id);
                conflict = true;
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
          return conflict;
        };
        const groupMembers = new Map();
        for (const c of changes)
          if (c.group) {
            if (!groupMembers.has(c.group)) groupMembers.set(c.group, []);
            groupMembers.get(c.group).push(c);
          }
        const handledGroups = new Set();
        const units = [];
        for (const c of changes) {
          if (!c.group) units.push({ grouped: false, changes: [c] });
          else if (!handledGroups.has(c.group)) {
            handledGroups.add(c.group);
            units.push({ grouped: true, changes: groupMembers.get(c.group) });
          }
        }
        const stage = (unit) =>
          unit.changes.every((c) => c.kind === "calendar")
            ? 0
            : unit.changes.some((c) => c.kind === "calendar")
              ? 1
              : 2;
        units.sort((a, b) => stage(a) - stage(b));
        for (const unit of units) {
          const active = unit.changes.filter((c) => pending.has(c));
          if (!active.length) continue;
          if (!unit.grouped) {
            applyChange(active[0], conflicts);
            continue;
          }
          db.exec("SAVEPOINT sync_group");
          const groupConflicts = [];
          let groupFailed = false;
          for (const c of active)
            if (applyChange(c, groupConflicts)) groupFailed = true;
          if (groupFailed) {
            db.exec("ROLLBACK TO sync_group; RELEASE sync_group");
            for (const c of active) {
              archive(
                userId,
                c.kind,
                c.id,
                JSON.stringify(c.data),
                Number(c.deleted),
                "conflict",
              );
              conflicts.push(c.id);
              db.prepare("INSERT INTO operations VALUES(?,?,?)").run(
                userId,
                c.operationId,
                fingerprints.get(c),
              );
            }
          } else {
            db.exec("RELEASE sync_group");
            conflicts.push(...groupConflicts);
          }
        }

        const pageLimit = 500;
        const responseLimit = 1024 * 1024;
        const conflictsJson = JSON.stringify(conflicts);
        const page = [];
        const envelopeBytes = (cursor, more) =>
          Buffer.byteLength(
            `{"cursor":${cursor},"records":[],"conflicts":${conflictsJson},"more":${more}}`,
          ) - 2;
        const recordBytes = (rows) => rows.reduce((total, row) =>
          total + Buffer.byteLength(JSON.stringify(encode(row))), 0) +
          Math.max(0, rows.length - 1);
        const pageRows = db
          .prepare(
            "SELECT * FROM records WHERE userId=? AND version>? ORDER BY version LIMIT 500",
          )
          .all(userId, b.cursor);
        const hasAfter = (version) => !!db
          .prepare("SELECT 1 FROM records WHERE userId=? AND version>? LIMIT 1")
          .get(userId, version);
        for (const row of pageRows) {
          const next = [...page, row];
          const more = hasAfter(row.version);
          if (envelopeBytes(row.version, more) + recordBytes(next) > responseLimit) break;
          page.push(row);
        }
        const lastPageVersion = page.at(-1)?.version ?? null;
        const more = lastPageVersion == null
          ? hasAfter(b.cursor)
          : hasAfter(lastPageVersion);
        const cursor = lastPageVersion ?? db
          .prepare("SELECT value FROM counter WHERE id=1")
          .get().value;
        const map = new Map(page.map((r) => [r.kind + ":" + r.id, r]));
        for (const c of changes) {
          const row = db
            .prepare("SELECT * FROM records WHERE userId=? AND kind=? AND id=?")
            .get(userId, c.kind, c.id);
          if (!row) continue;
          const key = row.kind + ":" + row.id;
          if (map.has(key)) continue;
          if (row.version > b.cursor &&
            (lastPageVersion == null || row.version > lastPageVersion)) continue;
          if (map.size >= pageLimit) continue;
          const next = [...map.values(), row].sort((a, b) => a.version - b.version);
          const bytes = recordBytes(next);
          if (envelopeBytes(cursor, more) + bytes > responseLimit) continue;
          map.set(key, row);
        }
        db.exec("COMMIT");
        return send(200, {
          cursor,
          records: [...map.values()].sort((a, b) => a.version - b.version).map(encode),
          conflicts,
          more,
        });
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      }
    } catch (error) {
      if (!res.headersSent)
        send(error.status || 500, {
          error: error.status ? error.message : "Server error",
          ...(error.operationId !== undefined ? { operationId: error.operationId } : {}),
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
    console.log(`calander server listening on ${port}`),
  );
  for (const signal of ["SIGINT", "SIGTERM"])
    process.on(signal, () => app.close().then(() => process.exit(0)));
}
