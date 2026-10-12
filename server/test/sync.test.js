import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp } from "../src.js";
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
  start: 1791784800000,
  end: 1791788400000,
  allDay: false,
  color: null,
  recurrence: "WEEKLY",
  recurrenceUntil: null,
  exceptions: [],
  reminders: [10],
  kind: "TASK",
  completions: [],
  importance: "HIGH",
};
const change = (
  kind,
  id,
  data,
  baseVersion = 0,
  deleted = false,
  operationId = crypto.randomUUID(),
) => ({ kind, id, data, baseVersion, deleted, operationId });
async function harness(t, options = {}) {
  const app = createApp({ dbPath: ":memory:", ...options });
  await new Promise((r) => app.server.listen(0, "127.0.0.1", r));
  t.after(() => app.close());
  const base = "http://127.0.0.1:" + app.server.address().port;
  const api = async (path, body, token) => {
    const res = await fetch(base + "/api/" + path, {
      method: body ? "POST" : "GET",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: res.status, ...(await res.json()) };
  };
  const account = await api("register", {
    email: "test@example.com",
    password: "test-password-123",
  });
  assert.equal(account.status, 200);
  return { app, api, token: account.token };
}
test("two devices sync create, task completion, recurring exceptions, and deletions", async (t) => {
  const { api, token } = await harness(t);
  let a = await api(
    "sync",
    {
      cursor: 0,
      changes: [
        change("event", "item", event),
        change("calendar", "personal", calendar),
      ],
    },
    token,
  );
  assert.equal(a.status, 200);
  assert.equal(a.records.length, 2);
  let b = await api("sync", { cursor: 0, changes: [] }, token);
  assert.deepEqual(b.records, a.records);
  const original = b.records.find((r) => r.id === "item");
  b = await api(
    "sync",
    {
      cursor: b.cursor,
      changes: [
        change(
          "event",
          "item",
          { ...event, completions: [20737], exceptions: [20738] },
          original.version,
        ),
      ],
    },
    token,
  );
  a = await api("sync", { cursor: a.cursor, changes: [] }, token);
  assert.deepEqual(a.records[0].data.completions, [20737]);
  assert.deepEqual(a.records[0].data.exceptions, [20738]);
  let deleted = await api(
    "sync",
    {
      cursor: b.cursor,
      changes: [
        change(
          "calendar",
          "personal",
          null,
          b.records.find((r) => r.kind === "calendar")?.version || 1,
          true,
        ),
      ],
    },
    token,
  );
  assert.equal(deleted.status, 200);
  assert.equal(deleted.records.filter((r) => r.deleted).length, 2);
  const pull = await api("sync", { cursor: 0, changes: [] }, token);
  assert(pull.records.every((r) => r.deleted));
});
test("lost-response retry is idempotent and operation reuse is rejected", async (t) => {
  const { api, token } = await harness(t);
  const c = change("calendar", "personal", calendar);
  const first = await api("sync", { cursor: 0, changes: [c] }, token);
  const second = await api(
    "sync",
    { cursor: first.cursor, changes: [c] },
    token,
  );
  assert.equal(second.cursor, first.cursor);
  assert.equal(second.records.length, 1);
  assert.equal(
    (
      await api(
        "sync",
        {
          cursor: 0,
          changes: [{ ...c, data: { ...calendar, name: "Changed" } }],
        },
        token,
      )
    ).status,
    409,
  );
});
test("stale edits are archived and cannot resurrect deleted events", async (t) => {
  const { api, token } = await harness(t);
  const seed = await api(
    "sync",
    {
      cursor: 0,
      changes: [
        change("calendar", "personal", calendar),
        change("event", "item", event),
      ],
    },
    token,
  );
  const version = seed.records.find((r) => r.id === "item").version;
  const first = await api(
    "sync",
    {
      cursor: seed.cursor,
      changes: [
        change("event", "item", { ...event, title: "First device" }, version),
      ],
    },
    token,
  );
  const stale = await api(
    "sync",
    {
      cursor: seed.cursor,
      changes: [
        change("event", "item", { ...event, title: "Other device" }, version),
      ],
    },
    token,
  );
  assert.deepEqual(stale.conflicts, ["item"]);
  assert.equal(
    stale.records.find((r) => r.id === "item").data.title,
    "First device",
  );
  const history = await api("history", null, token);
  assert(
    history.history.some(
      (h) => h.reason === "conflict" && h.data.title === "Other device",
    ),
  );
  await api(
    "sync",
    {
      cursor: first.cursor,
      changes: [change("event", "item", null, first.records[0].version, true)],
    },
    token,
  );
  const retry = await api(
    "sync",
    { cursor: seed.cursor, changes: [change("event", "item", event, version)] },
    token,
  );
  assert.equal(retry.records.find((r) => r.id === "item").deleted, true);
});
test("accounts are isolated, passwords protected, logout revokes sessions", async (t) => {
  const { app, api, token } = await harness(t);
  assert.equal((await api("sync", { cursor: 0, changes: [] })).status, 401);
  await api(
    "sync",
    { cursor: 0, changes: [change("calendar", "personal", calendar)] },
    token,
  );
  const second = await api("register", {
    email: "other@example.com",
    password: "another-password",
  });
  assert.equal(
    (await api("sync", { cursor: 0, changes: [] }, second.token)).records
      .length,
    0,
  );
  assert.equal(
    (
      await api("login", {
        email: "test@example.com",
        password: "wrong-password",
      })
    ).status,
    401,
  );
  assert.equal(
    (
      await api("login", {
        email: "test@example.com",
        password: "test-password-123",
      })
    ).status,
    200,
  );
  const stored = app.db
    .prepare("SELECT password FROM users WHERE email=?")
    .get("test@example.com").password;
  assert(!stored.includes("test-password"));
  await api("logout", {}, token);
  assert.equal(
    (await api("sync", { cursor: 0, changes: [] }, token)).status,
    401,
  );
});
test("invalid batch rolls back atomically and contacts are stripped", async (t) => {
  const { api, token } = await harness(t);
  const result = await api(
    "sync",
    {
      cursor: 0,
      changes: [
        change("calendar", "personal", calendar),
        change("event", "bad", { ...event, end: 0 }),
      ],
    },
    token,
  );
  assert.equal(result.status, 400);
  assert.equal(
    (await api("sync", { cursor: 0, changes: [] }, token)).records.length,
    0,
  );
  const valid = await api(
    "sync",
    {
      cursor: 0,
      changes: [
        change("calendar", "personal", calendar),
        change("event", "item", {
          ...event,
          people: [{ name: "Private", uri: "content://contacts/secret" }],
        }),
      ],
    },
    token,
  );
  assert(!("people" in valid.records.find((r) => r.id === "item").data));
});
test("database and sessions survive server restart", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "calendar-sync-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, "test.sqlite");
  let app = createApp({ dbPath: path });
  await new Promise((r) => app.server.listen(0, "127.0.0.1", r));
  let base = "http://127.0.0.1:" + app.server.address().port;
  const auth = await (
    await fetch(base + "/api/register", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: "persist@example.com",
        password: "persistent-password",
      }),
    })
  ).json();
  await fetch(base + "/api/sync", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + auth.token,
    },
    body: JSON.stringify({
      cursor: 0,
      changes: [change("calendar", "personal", calendar)],
    }),
  });
  await app.close();
  app = createApp({ dbPath: path });
  await new Promise((r) => app.server.listen(0, "127.0.0.1", r));
  t.after(() => app.close());
  base = "http://127.0.0.1:" + app.server.address().port;
  const pull = await (
    await fetch(base + "/api/sync", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer " + auth.token,
      },
      body: JSON.stringify({ cursor: 0, changes: [] }),
    })
  ).json();
  assert.equal(pull.records[0].data.name, "Personal");
});
