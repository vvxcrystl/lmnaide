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
import { toICalendar } from "./public/calendar-export.js";
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
// Rate-limit bucket for untrusted input: IPv4 addresses (and unknown
// strings) are used whole, while IPv6 sources collapse to their /64 prefix.
// A delegated prefix would otherwise gain 2^64 distinct buckets and rotate
// its interface ID to evade per-IP limits.
export function ipBucketKey(value) {
  if (isIP(value) !== 6) return value;
  const halves = value.split("%")[0].split("::");
  if (halves.length > 2) return value;
  const left = halves[0] ? halves[0].split(":") : [];
  const right = halves.length === 2 && halves[1] ? halves[1].split(":") : [];
  if (halves.length === 2 ? left.length + right.length > 7 : left.length !== 8)
    return value;
  const groups = [
    ...left,
    ...Array(8 - left.length - right.length).fill("0"),
    ...right,
  ];
  if (groups.every((group) => /^[0-9a-f]{1,4}$/i.test(group)))
    return (
      "v6/" + groups.slice(0, 4).map((g) => parseInt(g, 16)).join(":")
    );
  return value;
}
// STORAGE_QUOTA_BYTES, when present, must parse as a non-negative byte
// count; 0 lifts the quota entirely.
function quotaFromEnv() {
  const raw = process.env.STORAGE_QUOTA_BYTES;
  if (raw == null || raw.trim() === "") return 256 * 1024 * 1024;
  const value = Number(raw);
  return Number.isFinite(value) && value >= 0 ? value : 256 * 1024 * 1024;
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
    if (d.publicDetails !== undefined && typeof d.publicDetails !== "boolean")
      fail(400, "Invalid publicDetails");
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
      // Omit false to preserve legacy operation fingerprints and identical seed records.
      ...(d.publicDetails === true ? { publicDetails: true } : {}),
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
        "CALENDAR_DOMAIN must be a single DNS hostname, such as calendar.domain.com (no scheme, port or path)",
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
  // Per-account ceiling over records + operation journal, in bytes. When
  // the quota is reached only deletion batches of live records are
  // accepted, so a full account can still free space. 0 lifts the quota.
  quotaBytes = quotaFromEnv(),
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
    CREATE TABLE IF NOT EXISTS public_calendars(userId TEXT PRIMARY KEY REFERENCES users(id),token TEXT UNIQUE NOT NULL);
    CREATE TRIGGER IF NOT EXISTS revoke_inserted_tombstones AFTER INSERT ON records WHEN NEW.kind='event' AND NEW.deleted=1 BEGIN
      DELETE FROM shares WHERE userId=NEW.userId AND eventId=NEW.id;
    END;
    CREATE TRIGGER IF NOT EXISTS revoke_deleted_shares AFTER UPDATE OF deleted ON records WHEN NEW.kind='event' AND NEW.deleted=1 BEGIN
      DELETE FROM shares WHERE userId=NEW.userId AND eventId=NEW.id;
    END;
    CREATE TABLE IF NOT EXISTS profiles(userId TEXT PRIMARY KEY REFERENCES users(id),photo TEXT);
    CREATE TABLE IF NOT EXISTS event_origins(userId TEXT NOT NULL REFERENCES users(id),eventId TEXT NOT NULL,PRIMARY KEY(userId,eventId));
    CREATE TABLE IF NOT EXISTS counter(id INTEGER PRIMARY KEY CHECK(id=1),value INTEGER NOT NULL);
    INSERT OR IGNORE INTO counter VALUES(1,0);`);
  if (!db.prepare("PRAGMA table_info(users)").all().some((c) => c.name === "username"))
    db.exec("ALTER TABLE users ADD COLUMN username TEXT COLLATE NOCASE");
  db.exec("CREATE UNIQUE INDEX IF NOT EXISTS user_username ON users(username COLLATE NOCASE)");
  if (
    !db
      .prepare("PRAGMA table_info(operations)")
      .all()
      .some((c) => c.name === "created")
  ) {
    db.exec("ALTER TABLE operations ADD COLUMN created INTEGER");
    // Legacy journal rows get the full 90-day grace window from the
    // upgrade, so an idempotent offline retry never loses its record.
    db.prepare("UPDATE operations SET created=?").run(Date.now());
  }
  // Sign-in prunes by created-timestamp; index the columns so that sweep
  // stays off full table scans.
  db.exec(`CREATE INDEX IF NOT EXISTS operations_created ON operations(created);
    CREATE INDEX IF NOT EXISTS history_created ON history(created);`);
  const limits = new Map();
  // Sliding one-minute buckets used for authentication and sync rate limits.
  // Entries expire from the map on a sweep that runs once the map grows past
  // its hard cap; still-live entries are then evicted oldest-first so a
  // flood of distinct keys cannot grow memory without bound.
  const takeLimit = (key, maximum) => {
    const now = Date.now();
    if (limits.size > 20000) {
      for (const [name, value] of limits)
        if (value.until < now) limits.delete(name);
      while (limits.size > 20000) {
        const oldest = limits.keys().next().value;
        if (oldest === undefined) break;
        limits.delete(oldest);
      }
    }
    const limit = limits.get(key) || { count: 0, until: now + 60000 };
    if (limit.until < now) {
      limit.count = 0;
      limit.until = now + 60000;
    }
    limits.set(key, limit);
    if (++limit.count > maximum)
      fail(429, "Too many attempts. Try again in a minute.");
  };
  // Account lockout rejects requests up front but counts failures only, so
  // password guesses do not deny service to the account's real owner.
  const accountBlock = (key, maximum) => {
    const limit = limits.get(key);
    if (limit && limit.until >= Date.now() && limit.count >= maximum)
      fail(429, "Too many attempts. Try again in a minute.");
  };
  // One fingerprint definition shared by the quota pre-check and the sync
  // apply loop, so a change's charged and replayed identities cannot drift.
  // A grouped change hashes the named fields; a no-group change hashes its
  // whole serialized object, keeping the fingerprint that retries were
  // stored under before grouping existed.
  const changeFingerprint = (c) => c.group
    ? hash(JSON.stringify({
        kind: c.kind,
        id: c.id,
        baseVersion: c.baseVersion,
        deleted: c.deleted,
        data: c.data,
        operationId: c.operationId,
        group: c.group,
      }))
    : hash(JSON.stringify(c));
  const encode = (row) => ({
    kind: row.kind,
    id: row.id,
    version: row.version,
    deleted: !!row.deleted,
    data: row.data ? {
      ...JSON.parse(row.data),
      ...(row.kind === "event" && db.prepare(
        "SELECT 1 FROM event_origins WHERE userId=? AND eventId=?",
      ).get(row.userId, row.id) ? { createdProgrammatically: true } : {}),
    } : null,
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
      res.setHeader("Cross-Origin-Opener-Policy", "same-origin");
      res.setHeader("Cross-Origin-Resource-Policy", "same-origin");
      res.setHeader(
        "Permissions-Policy",
        "camera=(), microphone=(), geolocation=()",
      );
      res.setHeader("X-Robots-Tag", "noindex, nofollow");
      // Pre-v1 clients call unversioned /api/<x>. Route them to /api/v1/<x>.
      // Other versions, such as /api/v2/, pass through and are rejected below.
      let path;
      try {
        path = new URL(req.url, "http://localhost").pathname.replace(
          /^\/api\/(?!v\d+(?:\/|$))/,
          "/api/v1/",
        );
      } catch {
        // Protocol-relative or malformed absolute targets, such as //host%2f,
        // throw out of URL parsing; return 400 instead of leaking a 500.
        fail(400, "Invalid request");
      }
      if (req.method === "GET" && path === "/health")
        return send(200, { ok: true, version: "0.1.0" });
      if (req.method === "GET" && path.startsWith("/api/v1/public/")) {
        const token = path.slice("/api/v1/public/".length);
        const owner = /^[a-f0-9]{64}$/.test(token) && db.prepare(
          "SELECT u.id,u.username FROM public_calendars p JOIN users u ON u.id=p.userId WHERE p.token=?",
        ).get(token);
        if (!owner) fail(404, "This public calendar is no longer available.");
        const calendars = new Map(db.prepare(
          "SELECT id,data FROM records WHERE userId=? AND kind='calendar' AND deleted=0",
        ).all(owner.id).map((r) => [r.id, JSON.parse(r.data)]));
        const name = owner.username || "User";
        const events = db.prepare(
          "SELECT data FROM records WHERE userId=? AND kind='event' AND deleted=0 ORDER BY version",
        ).all(owner.id).flatMap((row) => {
          const e = JSON.parse(row.data);
          const calendar = calendars.get(e.calendarId);
          if (!calendar) return [];
          // Allowlist timing fields for busy entries. Private metadata never reaches the visitor.
          const timing = Object.fromEntries([
            "start", "end", "allDay", "recurrence", "recurrenceUntil", "exceptions",
          ].map((key) => [key, e[key]]));
          return [{ ...timing, ...(calendar.publicDetails === true ? {
            title: e.title, description: e.description, location: e.location,
            calendar: calendar.name, color: e.color ?? calendar.color, kind: e.kind,
          } : { title: name + " is busy" }) }];
        });
        return send(200, { name, events });
      }
      if (req.method === "GET" && path.startsWith("/api/v1/shared/")) {
        const download = path.endsWith("/event.ics");
        const shareToken = path.slice("/api/v1/shared/".length,
          download ? -"/event.ics".length : undefined);
        if (!/^[a-f0-9]{64}$/.test(shareToken))
          fail(404, "This link is no longer available.");
        const row = db
          .prepare(
            "SELECT r.data FROM shares s JOIN records r ON r.userId=s.userId AND r.id=s.eventId AND r.kind='event' AND r.deleted=0 WHERE s.token=?",
          )
          .get(shareToken);
        if (!row) fail(404, "This link is no longer available.");
        const event = JSON.parse(row.data);
        if (download) {
          res.writeHead(200, {
            "Content-Type": "text/calendar; charset=utf-8",
            "Content-Disposition": 'attachment; filename="event.ics"',
            "Cache-Control": "no-store",
          });
          return res.end(toICalendar(event, hash(shareToken) + "@lmnaide"));
        }
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
                    : /^\/public\/[a-f0-9]{64}$/.test(path)
                      ? "/index.html"
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
      // Unknown versions fail here, before the session check, so they 404 without a token.
      if (!path.startsWith("/api/v1/")) fail(404, "Not found");
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
        ["/api/v1/register", "/api/v1/login"].includes(path)
      ) {
        const ipKey = ipBucketKey(clientIp(req, trustedProxyNets));
        takeLimit(`ip:${ipKey}`, 30);
        const b = await body();
        if (!b || typeof b !== "object" || Array.isArray(b))
          fail(400, "Invalid request");
        const email =
          typeof b.email === "string" ? b.email.trim().toLowerCase() : "";
        // Length is checked before the regex: the regex backtracks
        // quadratically on long inputs, and a single request near the body
        // cap used to block the event loop for roughly an hour (ReDoS).
        if (
          email.length > 254 ||
          !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
          typeof b.password !== "string" ||
          b.password.length < 10 ||
          b.password.length > 256
        )
          fail(
            400,
            "Use an email address and a password of 10–256 characters.",
          );
        // Account lockout is keyed per (account, source bucket) and counts
        // failures only: password guesses from an attacker's prefixes never
        // touch the owner's bucket or their sign-ins, and each source stays
        // at 10 failures per minute per address prefix. Accepted trade: a
        // distributed attacker facing K prefixes scales guessing to K x 10
        // per minute against one account (no global ceiling); the per-IP
        // cap, 10+ character passwords and scrypt remain the primary
        // defenses, and a global failure cap would reintroduce the owner
        // lockout this split exists to prevent.
        const accountKey = `account:${ipKey}/${email}`;
        accountBlock(accountKey, 10);
        let user = db.prepare("SELECT * FROM users WHERE email=?").get(email);
        if (path === "/api/v1/register") {
          if (!allowRegistration)
            fail(403, "New accounts are disabled on this server.");
          // Caps account farming and email-address probing on open
          // registration: both new accounts and existing-account 409s count
          // (10 per minute per source prefix).
          takeLimit(`reg:${ipKey}`, 10);
          if (user) {
            takeLimit(accountKey, 10);
            fail(409, "An account already exists. Sign in instead.");
          }
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
            if (e.message.includes("users.email")) {
              takeLimit(accountKey, 10);
              fail(409, "An account already exists. Sign in instead.");
            }
            throw e;
          }
        } else {
          const [salt, stored] = (
            user?.password || `${"0".repeat(32)}:${"0".repeat(128)}`
          ).split(":");
          const key = await scrypt(b.password, salt, 64);
          if (!timingSafeEqual(key, Buffer.from(stored, "hex")) || !user) {
            takeLimit(accountKey, 10);
            fail(401, "Email or password is incorrect.");
          }
        }
        const token = randomBytes(32).toString("hex");
        db.prepare("DELETE FROM sessions WHERE expires<?").run(Date.now());
        // Edit history and the idempotency journal are pruned on sign-in so
        // long-running servers stay bounded: 90 days matches the sync
        // operation-retry window and the browser's history coverage.
        const retentionCutoff = Date.now() - 90 * 86400000;
        db.prepare("DELETE FROM history WHERE created<?").run(retentionCutoff);
        db.prepare("DELETE FROM operations WHERE created<?").run(retentionCutoff);
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
      if (req.method === "POST" && path === "/api/v1/public") {
        const b = await body();
        if (!b || !["status", "create", "revoke"].includes(b.action))
          fail(400, "Invalid public calendar request");
        if (b.action === "revoke")
          db.prepare("DELETE FROM public_calendars WHERE userId=?").run(userId);
        if (b.action === "create")
          db.prepare("INSERT OR IGNORE INTO public_calendars VALUES(?,?)")
            .run(userId, randomBytes(32).toString("hex"));
        const share = db.prepare("SELECT token FROM public_calendars WHERE userId=?").get(userId);
        const path = share ? "/public/" + share.token : null;
        return send(200, {
          active: !!share, path,
          url: path && publicUrl ? new URL(path, publicUrl).href : null,
        });
      }
      if (req.method === "POST" && path === "/api/v1/share") {
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
      if (path === "/api/v1/profile") {
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
      if (req.method === "POST" && path === "/api/v1/logout") {
        db.prepare("DELETE FROM sessions WHERE token=?").run(hash(token));
        return send(200, { ok: true });
      }
      if (req.method === "GET" && path === "/api/v1/history")
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
      // REST writes use the sync transaction below, including history, quotas,
      // calendar deletion cascades and the version counter used by all clients.
      let apiWrite = null;
      let syncBody;
      const resource = path.match(/^\/api\/v1\/(calendars|events)(?:\/([^/]+))?$/);
      if (resource) {
        takeLimit(`api:${userId}`, 90);
        const kind = resource[1] === "calendars" ? "calendar" : "event";
        let id;
        try { id = resource[2] && decodeURIComponent(resource[2]); }
        catch { fail(400, "Invalid record ID"); }
        const current = id && db.prepare(
          "SELECT * FROM records WHERE userId=? AND kind=? AND id=?",
        ).get(userId, kind, id);
        if (req.method === "GET") {
          if (id) {
            if (!current || current.deleted) fail(404, "Not found");
            return send(200, encode(current));
          }
          const query = new URL(req.url, "http://localhost").searchParams;
          const cursor = Number(query.get("cursor") ?? 0);
          const limit = Number(query.get("limit") ?? 100);
          if (!Number.isSafeInteger(cursor) || cursor < 0 ||
              !Number.isSafeInteger(limit) || limit < 1 || limit > 500)
            fail(400, "Invalid pagination");
          const calendarId = kind === "event" ? query.get("calendarId") : null;
          const rows = db.prepare(
            `SELECT * FROM records WHERE userId=? AND kind=? AND deleted=0 AND version>?
             ${calendarId !== null ? "AND json_extract(data,'$.calendarId')=?" : ""}
             ORDER BY version LIMIT ?`,
          ).all(userId, kind, cursor, ...(calendarId !== null ? [calendarId] : []), limit + 1);
          const page = [];
          let bytes = 100; // Envelope and comma overhead, within the sync response budget.
          for (const row of rows.slice(0, limit)) {
            const record = encode(row);
            const size = Buffer.byteLength(JSON.stringify(record)) + 1;
            if (page.length && bytes + size > 1024 * 1024) break;
            page.push(record);
            bytes += size;
          }
          const more = rows.length > page.length;
          return send(200, { records: page, more,
            nextCursor: page.at(-1)?.version ?? cursor });
        }
        if (!((req.method === "POST" && !id) ||
              (["PATCH", "DELETE"].includes(req.method) && id)))
          fail(405, "Use GET, POST, PATCH or DELETE for this resource.");
        const input = await body();
        if (!input || typeof input !== "object" || Array.isArray(input))
          fail(400, "Invalid request");
        const creating = req.method === "POST";
        if (creating) {
          id = input.id ?? randomUUID();
          if (typeof id !== "string" || !id || id.length > 200 || [".", ".."].includes(id))
            fail(400, "Invalid record ID");
          if (db.prepare("SELECT 1 FROM records WHERE userId=? AND kind=? AND id=?").get(userId, kind, id))
            fail(409, "Record ID already exists.");
        } else {
          // Re-read after awaiting the body so a concurrent save cannot be overwritten.
          const latest = db.prepare("SELECT * FROM records WHERE userId=? AND kind=? AND id=?").get(userId, kind, id);
          if (!latest || latest.deleted) fail(404, "Not found");
          if (!Number.isSafeInteger(input.version) || input.version < 1)
            fail(400, "Supply the current record version.");
          if (input.version !== latest.version) fail(409, "Record changed. Fetch it again before saving.");
          apiWrite = { current: latest };
        }
        const defaults = kind === "calendar"
          ? { color: -13388167, visible: true, keywords: "", defaultImportance: null }
          : { title: "", description: "", location: "", allDay: false, color: null,
              recurrence: "NONE", recurrenceUntil: null, exceptions: [], reminders: [],
              kind: "EVENT", completions: [], importance: null };
        const data = { ...(creating ? defaults : JSON.parse(apiWrite.current.data)), ...input };
        const deleted = req.method === "DELETE";
        const change = { kind, id, baseVersion: creating ? 0 : input.version,
          deleted, data: deleted ? null : data, operationId: randomUUID() };
        change.data = validate(change);
        if (kind === "event" && !deleted && !db.prepare(
          "SELECT 1 FROM records WHERE userId=? AND kind='calendar' AND id=? AND deleted=0",
        ).get(userId, change.data.calendarId)) fail(400, "Calendar does not exist.");
        apiWrite = { kind, id, creating };
        syncBody = { cursor: 0, changes: [change] };
      }
      if (!apiWrite && (req.method !== "POST" || path !== "/api/v1/sync"))
        fail(404, "Not found");
      // Generous bound: foreground clients poll every 10 s (6/min); bursts
      // during large edits stay well under 90. This keeps a single signed-in
      // client from saturating the single-threaded event loop.
      takeLimit(`sync:${userId}`, 90);
      const b = syncBody ?? await body();
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
            if (operationId !== undefined)
              error.operationId = operationId.slice(0, 250);
            throw error;
          }
        })
        .sort(
          (a, b) => Number(a.kind === "event") - Number(b.kind === "event"),
        );
      // Storage quota covers live records and the operation journal; edit
      // history is capped separately per account (rows, below) so that
      // tombstoning a record really does free space. Only batches that
      // would charge something are measured: empty polls run no queries.
      // A deletion frees space only when it removes a live record at the
      // exact base version it claims, when nothing else in the batch
      // writes that record, and when nothing in its group can be rolled
      // back - that is how a full account gets space back. Stale,
      // duplicate, unknown-id or already-deleted deletions, deletions of
      // records their own batch rewrites, and deletions in a group with
      // any other outcome, grow the database without removing rows
      // (tombstones, conflict rows, journal entries), so those are
      // charged like writes.
      let chargeable = 0;
      if (quotaBytes > 0) {
        const rows = new Map();
        const replays = new Map();
        // Any key a non-deletion change in this batch writes will have its
        // version bumped by the time a deletion of it can run, so such a
        // deletion cannot be trusted to free the row. The set is built
        // order-independently because staging can run a later write before
        // an earlier group. Replayed writes count too, which can only
        // over-charge a rare same-key rewrite-and-delete retry.
        const batchWrites = new Set();
        for (const c of changes) if (!c.deleted) batchWrites.add(c.kind + ":" + c.id);
        // Pass 1: replayed operations are no-ops (skipped entirely, free);
        // writes charge; a deletion only escapes charging for the first
        // occurrence of a live row at its claimed base version.
        const verdicts = [];
        for (const c of changes) {
          if (!replays.has(c.operationId)) {
            replays.set(
              c.operationId,
              db
                .prepare(
                  "SELECT fingerprint FROM operations WHERE userId=? AND operationId=?",
                )
                .get(userId, c.operationId) ?? null,
            );
          }
          const prior = replays.get(c.operationId);
          // A journaled operation replays as a no-op, so a lost-response
          // retry must still succeed (free) when the account is full.
          if (prior && prior.fingerprint === changeFingerprint(c)) {
            verdicts.push("skip");
            continue;
          }
          if (!c.deleted) {
            chargeable += Buffer.byteLength(JSON.stringify(c.data)) + 600;
            verdicts.push("charge");
            continue;
          }
          const key = c.kind + ":" + c.id;
          if (batchWrites.has(key)) {
            chargeable += 600;
            verdicts.push("charge");
            continue;
          }
          if (!rows.has(key)) {
            rows.set(
              key,
              db
                .prepare(
                  "SELECT version, deleted FROM records WHERE userId=? AND kind=? AND id=?",
                )
                .get(userId, c.kind, c.id) ?? null,
            );
          }
          const current = rows.get(key);
          if (
            current &&
            !current.deleted &&
            c.baseVersion === current.version
          ) {
            // Only the first real deletion of a given row is free; later
            // copies in the batch would be no-ops that still journal.
            rows.set(key, { deleted: 1 });
            verdicts.push("free");
          } else {
            chargeable += 600;
            verdicts.push("charge");
          }
        }
        // Pass 2: a group whose every member is a free deletion normally
        // removes its rows cleanly, but it can still roll back - for
        // example when a concurrent request rewrites one of the rows
        // between this pre-check and the transaction below (the pre-check
        // runs outside BEGIN IMMEDIATE). A rolled-back group still
        // journals and archives each member, so such a group's deletions
        // are charged; the per-account row caps on the journal and
        // history, later in this transaction, are the hard bound on that
        // residual growth.
        const groupsClean = new Map();
        for (let i = 0; i < changes.length; i++) {
          if (!changes[i].group) continue;
          let state = groupsClean.get(changes[i].group);
          if (!state) groupsClean.set(changes[i].group, (state = { clean: true }));
          if (verdicts[i] === "charge") state.clean = false;
        }
        for (let i = 0; i < changes.length; i++) {
          if (verdicts[i] !== "free") continue;
          const group = changes[i].group;
          if (group && !groupsClean.get(group)?.clean)
            chargeable += 600;
        }
      }
      const usage = chargeable
        ? db
            .prepare(
              `SELECT
                 (SELECT COALESCE(SUM(octet_length(data)),0) FROM records WHERE userId=?)
               + (SELECT COUNT(*) FROM operations WHERE userId=?) * 400 AS bytes`,
            )
            .get(userId, userId).bytes
        : 0;
      // x2 margins the archived previous versions written alongside each
      // accepted change.
      if (chargeable && usage + chargeable * 2 > quotaBytes)
        fail(
          413,
          "Storage quota is full. Delete records and sync, then retry later.",
        );
      const conflicts = [];
      db.exec("BEGIN IMMEDIATE");
      try {
        const fingerprints = new Map();
        const pending = new Set();
        const requestOperations = new Map();
        for (const c of changes) {
          const fingerprint = changeFingerprint(c);
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
          db.prepare(
            "INSERT INTO operations(userId,operationId,fingerprint,created) VALUES(?,?,?,?)",
          ).run(userId, c.operationId, fingerprint, Date.now());
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
              db.prepare(
                "INSERT INTO operations(userId,operationId,fingerprint,created) VALUES(?,?,?,?)",
              ).run(
                userId,
                c.operationId,
                fingerprints.get(c),
                Date.now(),
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
        const envelopeBytes = (cursor, more) =>
          Buffer.byteLength(
            `{"cursor":${cursor},"records":[],"conflicts":${conflictsJson},"more":${more}}`,
          ) - 2;
        // Encode each row exactly once and keep a running byte total.
        // Re-serializing the whole accumulated page for every candidate row
        // used to make big pages quadratic in event-loop time.
        const page = [];
        let pageBytes = 0;
        const pageRows = db
          .prepare(
            "SELECT * FROM records WHERE userId=? AND version>? ORDER BY version LIMIT 500",
          )
          .all(userId, b.cursor);
        const hasAfter = (version) => !!db
          .prepare("SELECT 1 FROM records WHERE userId=? AND version>? LIMIT 1")
          .get(userId, version);
        for (const row of pageRows) {
          const rowBytes = Buffer.byteLength(JSON.stringify(encode(row)));
          const more = hasAfter(row.version);
          // recordBytes' trailing Math.max(0, rows.length - 1) counts the
          // array commas, which after appending is exactly page.length.
          if (
            envelopeBytes(row.version, more) + pageBytes + rowBytes + page.length >
            responseLimit
          )
            break;
          page.push(row);
          pageBytes += rowBytes;
        }
        // A record larger than the whole budget previously left the page
        // empty and let the cursor jump past it permanently; deliver the
        // first row regardless so clients stay consistent.
        if (!page.length && pageRows.length) {
          page.push(pageRows[0]);
          pageBytes = Buffer.byteLength(JSON.stringify(encode(pageRows[0])));
        }
        const lastPageVersion = page.at(-1)?.version ?? null;
        const more = lastPageVersion == null
          ? hasAfter(b.cursor)
          : hasAfter(lastPageVersion);
        const cursor = lastPageVersion ?? db
          .prepare("SELECT value FROM counter WHERE id=1")
          .get().value;
        const map = new Map(page.map((r) => [r.kind + ":" + r.id, r]));
        let mapBytes = pageBytes;
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
          const rowBytes = Buffer.byteLength(JSON.stringify(encode(row)));
          // After appending this row the array has map.size + 1 entries, so
          // its comma separators number exactly map.size.
          if (
            envelopeBytes(cursor, more) + mapBytes + rowBytes + map.size >
            responseLimit
          )
            continue;
          map.set(key, row);
          mapBytes += rowBytes;
        }
        // Edit history and the operation journal are also capped per
        // account in rows, so a conflict-heavy or crafted account cannot
        // keep growing them between the 90-day prunes: the journal writes
        // one row per applied change even when a change turns out to be a
        // self-identical no-op or a conflict. The browser surfaces the
        // latest 200 history entries for restore. Only batches that wrote
        // anything (pending non-replayed changes) pay for the counts;
        // empty polls and full-replay batches skip them.
        if (pending.size) {
          const JOURNAL_ROWS_PER_ACCOUNT = 10000;
          const journalCount = db
            .prepare("SELECT COUNT(*) AS n FROM operations WHERE userId=?")
            .get(userId).n;
          if (journalCount > JOURNAL_ROWS_PER_ACCOUNT)
            db
              .prepare(
                `DELETE FROM operations WHERE userId=? AND rowid IN (
                   SELECT rowid FROM (
                     SELECT rowid FROM operations WHERE userId=?
                     ORDER BY created, rowid LIMIT ?
                   )
                 )`,
              )
              .run(
                userId,
                userId,
                journalCount - JOURNAL_ROWS_PER_ACCOUNT,
              );
          const HISTORY_ROWS_PER_ACCOUNT = 10000;
          const historyCount = db
            .prepare("SELECT COUNT(*) AS n FROM history WHERE userId=?")
            .get(userId).n;
          if (historyCount > HISTORY_ROWS_PER_ACCOUNT)
            db
              .prepare(
                `DELETE FROM history WHERE userId=? AND id IN (
                   SELECT id FROM (
                     SELECT id FROM history WHERE userId=?
                     ORDER BY created, id LIMIT ?
                   )
                 )`,
              )
              .run(
                userId,
                userId,
                historyCount - HISTORY_ROWS_PER_ACCOUNT,
              );
        }
        if (apiWrite?.creating && apiWrite.kind === "event")
          db.prepare("INSERT INTO event_origins VALUES(?,?)").run(userId, apiWrite.id);
        const apiRecord = apiWrite && db.prepare(
          "SELECT * FROM records WHERE userId=? AND kind=? AND id=?",
        ).get(userId, apiWrite.kind, apiWrite.id);
        db.exec("COMMIT");
        if (apiWrite) return send(apiWrite.creating ? 201 : 200, encode(apiRecord));
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
    console.log(`calendar server listening on ${port}`),
  );
  for (const signal of ["SIGINT", "SIGTERM"])
    process.on(signal, () => app.close().then(() => process.exit(0)));
}
