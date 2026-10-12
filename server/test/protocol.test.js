import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import { createHash } from "node:crypto";
import { createApp } from "../src.js";

const day = 86400000;
const minDay = Date.UTC(1900, 0, 1) / day;
const maxDay = Date.UTC(2201, 0, 1) / day - 1;
const calendar = {
  name: "Personal",
  color: -13388167,
  visible: true,
  keywords: "",
  defaultImportance: null,
};
const event = {
  calendarId: "personal",
  title: "Device sync",
  description: "",
  location: "",
  start: Date.UTC(2026, 9, 11, 14),
  end: Date.UTC(2026, 9, 11, 15),
  allDay: false,
  color: null,
  recurrence: "WEEKLY",
  recurrenceUntil: null,
  exceptions: [],
  reminders: [10],
  kind: "EVENT",
  completions: [],
  importance: "HIGH",
};
const change = (kind, id, data, baseVersion = 0, deleted = false, operationId = crypto.randomUUID()) => ({
  kind,
  id,
  data,
  baseVersion,
  deleted,
  operationId,
});

async function harness(t, options = {}) {
  const app = createApp({ dbPath: ":memory:", ...options });
  await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
  t.after(() => app.close());
  const base = `http://127.0.0.1:${app.server.address().port}`;
  const api = async (path, body, token) => {
    const response = await fetch(`${base}/api/${path}`, {
      method: body === undefined ? "GET" : "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: response.status, ...(await response.json()) };
  };
  const account = await api("register", {
    email: "test@example.com",
    password: "test-password-123",
  });
  assert.equal(account.status, 200);
  return { app, api, base, token: account.token };
}

test("stale occurrence groups reject the series exception and replacement together", async (t) => {
  const { app, api, token } = await harness(t);
  const seeded = await api("sync", {
    cursor: 0,
    changes: [change("calendar", "personal", calendar), change("event", "series", event)],
  }, token);
  const seriesVersion = seeded.records.find((row) => row.id === "series").version;
  const winning = await api("sync", {
    cursor: seeded.cursor,
    changes: [change("event", "series", { ...event, title: "Latest series" }, seriesVersion)],
  }, token);
  const occurrenceDay = Date.UTC(2026, 9, 12) / day;
  const group = "occurrence-edit-1";
  const exception = { ...change("event", "series", { ...event, exceptions: [occurrenceDay] }, seriesVersion), group };
  const replacement = {
    ...change("event", "replacement", { ...event, title: "Replacement" }),
    group,
  };
  const rejected = await api("sync", {
    cursor: winning.cursor,
    changes: [exception, replacement],
  }, token);
  assert.deepEqual(rejected.conflicts, ["series", "replacement"]);
  assert.equal(app.db.prepare("SELECT 1 FROM records WHERE userId=? AND kind='event' AND id='replacement'").get(
    app.db.prepare("SELECT id FROM users WHERE email='test@example.com'").get().id,
  ), undefined);
  const current = app.db.prepare("SELECT data FROM records WHERE kind='event' AND id='series'").get();
  assert.equal(JSON.parse(current.data).title, "Latest series");
  assert.deepEqual(JSON.parse(current.data).exceptions, []);
  const conflicts = await api("history", undefined, token);
  assert.equal(conflicts.history.filter((row) => row.reason === "conflict").length, 2);
  const retry = await api("sync", { cursor: winning.cursor, changes: [exception, replacement] }, token);
  assert.deepEqual(retry.conflicts, []);
  assert.equal((await api("history", undefined, token)).history.length, conflicts.history.length);
});

test("a grouped event referencing a deleted calendar rolls the calendar deletion back", async (t) => {
  const { app, api, token } = await harness(t);
  const seeded = await api("sync", {
    cursor: 0,
    changes: [change("calendar", "personal", calendar), change("event", "existing", event)],
  }, token);
  const calendarVersion = seeded.records.find((row) => row.kind === "calendar").version;
  const group = "delete-calendar-with-event";
  const removeCalendar = {
    ...change("calendar", "personal", null, calendarVersion, true),
    group,
  };
  const addEvent = {
    ...change("event", "new-event", { ...event, title: "Must not appear" }),
    group,
  };
  const result = await api("sync", {
    cursor: seeded.cursor,
    changes: [removeCalendar, addEvent],
  }, token);
  assert.deepEqual(result.conflicts, ["personal", "new-event"]);
  const userId = app.db.prepare("SELECT id FROM users WHERE email='test@example.com'").get().id;
  const records = app.db.prepare("SELECT kind,id,deleted FROM records WHERE userId=?").all(userId);
  assert.deepEqual(records.map((row) => [row.kind, row.id, row.deleted]), [
    ["calendar", "personal", 0],
    ["event", "existing", 0],
  ]);
  assert.equal((await api("history", undefined, token)).history.filter((row) => row.reason === "conflict").length, 2);
});

test("sync pages are bounded and clients converge by following more and cursor", async (t) => {
  const { api, token } = await harness(t);
  const changes = Array.from({ length: 1000 }, (_, i) => change(
    "calendar",
    `calendar-${i}`,
    { ...calendar, name: `Calendar ${i}` },
  ));
  let page = await api("sync", { cursor: 0, changes }, token);
  assert.equal(page.status, 200);
  assert.equal(page.records.length, 500);
  assert.equal(page.more, true);
  assert.deepEqual(page.records.map((row) => row.version),
    [...page.records.map((row) => row.version)].sort((a, b) => a - b));
  const seen = new Map(page.records.map((row) => [`${row.kind}:${row.id}`, row]));
  while (page.more) {
    const priorCursor = page.cursor;
    page = await api("sync", { cursor: priorCursor, changes: [] }, token);
    assert.equal(page.status, 200);
    assert(page.cursor > priorCursor);
    assert(page.records.length <= 500);
    for (const row of page.records) seen.set(`${row.kind}:${row.id}`, row);
  }
  assert.equal(seen.size, 1000);
  assert.equal([...seen.values()].filter((row) => !row.deleted).length, 1000);
  const empty = await api("sync", { cursor: page.cursor, changes: [] }, token);
  assert.equal(empty.more, false);
  assert.deepEqual(empty.records, []);
});

test("sync response byte limit also paginates large record sets", async (t) => {
  const { api, token } = await harness(t);
  const longEvent = { ...event, description: "x".repeat(18000) };
  const changes = [change("calendar", "personal", calendar), ...Array.from({ length: 75 }, (_, i) =>
    change("event", `large-${i}`, longEvent),
  )];
  let page = await api("sync", { cursor: 0, changes }, token);
  assert.equal(page.status, 200);
  assert.equal(page.more, true);
  assert(Buffer.byteLength(JSON.stringify(page)) <= 1024 * 1024);
  const seen = new Set(page.records.map((row) => `${row.kind}:${row.id}`));
  while (page.more) {
    page = await api("sync", { cursor: page.cursor, changes: [] }, token);
    assert(Buffer.byteLength(JSON.stringify(page)) <= 1024 * 1024);
    for (const row of page.records) seen.add(`${row.kind}:${row.id}`);
  }
  assert.equal(seen.size, 76);
});

test("event validation bounds dates, epoch days, reminders and colors and returns operationId", async (t) => {
  const { api, token } = await harness(t);
  const invalid = [
    { ...event, start: Date.UTC(1899, 11, 31) },
    { ...event, end: Date.UTC(2201, 0, 1) },
    { ...event, recurrenceUntil: 9007199254740991 },
    { ...event, exceptions: [minDay - 1] },
    { ...event, completions: [maxDay + 1] },
    { ...event, reminders: [4294967296] },
    { ...event, reminders: Array(101).fill(0) },
    { ...event, reminders: [-1] },
    { ...event, color: 2147483648 },
  ];
  for (let i = 0; i < invalid.length; i++) {
    const operationId = `invalid-${i}`;
    const result = await api("sync", {
      cursor: 0,
      changes: [change("event", `bad-${i}`, invalid[i], 0, false, operationId)],
    }, token);
    assert.equal(result.status, 400);
    assert.equal(result.operationId, operationId);
  }
  const firstInvalid = await api("sync", {
    cursor: 0,
    changes: [
      change("event", "first-bad", { ...event, start: Date.UTC(1899, 11, 31) }, 0, false, "first-invalid"),
      change("event", "second-bad", { ...event, reminders: [4294967296] }, 0, false, "second-invalid"),
    ],
  }, token);
  assert.equal(firstInvalid.status, 400);
  assert.equal(firstInvalid.operationId, "first-invalid");
  const invalidCalendar = await api("sync", {
    cursor: 0,
    changes: [change("calendar", "bad-color", { ...calendar, color: 2147483648 }, 0, false, "bad-calendar-color")],
  }, token);
  assert.equal(invalidCalendar.status, 400);
  assert.equal(invalidCalendar.operationId, "bad-calendar-color");
  const accepted = await api("sync", {
    cursor: 0,
    changes: [
      change("calendar", "personal", calendar),
      change("event", "empty-title", {
        ...event,
        title: "",
        start: Date.UTC(1900, 0, 1),
        end: Date.UTC(2200, 11, 31, 23, 59, 59, 999),
        recurrenceUntil: maxDay,
        exceptions: [minDay],
        completions: [maxDay],
        reminders: [0, 525600],
        color: -2147483648,
      }),
    ],
  }, token);
  assert.equal(accepted.status, 200);
  assert.equal(accepted.records.find((row) => row.id === "empty-title").data.title, "");
  const batch = await api("sync", { cursor: -1, changes: [] }, token);
  assert.equal(batch.status, 400);
  assert.deepEqual(Object.keys(batch).sort(), ["error", "status"]);
});

test("group is included in operation idempotency fingerprints", async (t) => {
  const { api, token } = await harness(t);
  const original = { ...change("calendar", "personal", calendar, 0, false, "same-operation"), group: "first" };
  assert.equal((await api("sync", { cursor: 0, changes: [original] }, token)).status, 200);
  const replay = { ...original, group: "different" };
  assert.equal((await api("sync", { cursor: 0, changes: [replay] }, token)).status, 409);
});

test("ungrouped operation fingerprints remain compatible with databases from before upgrade", async (t) => {
  const { app, api, token } = await harness(t);
  const operation = change("calendar", "personal", calendar, 0, false, "legacy-operation");
  const first = await api("sync", { cursor: 0, changes: [operation] }, token);
  const legacyFingerprint = createHash("sha256")
    .update(JSON.stringify(operation))
    .digest("hex");
  app.db.prepare("UPDATE operations SET fingerprint=? WHERE operationId=?")
    .run(legacyFingerprint, operation.operationId);
  const retry = await api("sync", { cursor: first.cursor, changes: [operation] }, token);
  assert.equal(retry.status, 200);
  assert.equal(retry.cursor, first.cursor);
  assert.equal(retry.records.length, 1);
});

test("history index is added idempotently and serves the history query plan", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "calendar-history-index-"));
  const dbPath = join(dir, "test.sqlite");
  const oldDb = new DatabaseSync(dbPath);
  oldDb.exec("CREATE TABLE history(id TEXT PRIMARY KEY,userId TEXT NOT NULL,kind TEXT NOT NULL,recordId TEXT NOT NULL,data TEXT,deleted INTEGER NOT NULL,created INTEGER NOT NULL,reason TEXT NOT NULL)");
  oldDb.close();
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  let app = createApp({ dbPath });
  const indexes = () => app.db.prepare("PRAGMA index_list(history)").all().map((row) => row.name);
  assert(indexes().includes("history_user_created"));
  const plan = app.db.prepare("EXPLAIN QUERY PLAN SELECT * FROM history WHERE userId=? ORDER BY created DESC LIMIT 200").all("user");
  assert(plan.some((row) => row.detail.includes("history_user_created")), JSON.stringify(plan));
  app.close();
  app = createApp({ dbPath });
  assert.equal(indexes().filter((name) => name === "history_user_created").length, 1);
  app.close();
});
