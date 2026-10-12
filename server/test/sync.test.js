import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp } from "../src.js";
import { defaultRecords } from "../public/holidays.js";
import { createHash } from "node:crypto";
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
  return { app, api, base, token: account.token };
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
      changes: [
        change("calendar", "personal", calendar),
        change("event", "item", event),
      ],
    }),
  });
  const link = await (
    await fetch(base + "/api/share", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer " + auth.token,
      },
      body: JSON.stringify({ eventId: "item", action: "create" }),
    })
  ).json();
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
  const shared = await (
    await fetch(base + "/api/shared/" + link.path.split("/").pop())
  ).json();
  assert.equal(shared.event.title, event.title);
});

test("custom domain share links use the public HTTPS host behind a proxy", async (t) => {
  const { api, base, token } = await harness(t, {
    domain: "calander.domain.com",
    publicUrl: "",
  });
  assert.equal((await api("sync", {
    cursor: 0,
    changes: [
      change("calendar", "personal", calendar),
      change("event", "item", event),
    ],
  }, token)).status, 200);
  const response = await fetch(base + "/api/share", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer " + token,
      "X-Forwarded-Host": "untrusted.example.com",
      "X-Forwarded-Proto": "http",
    },
    body: JSON.stringify({ eventId: "item", action: "create" }),
  });
  assert.equal(response.status, 200);
  const link = await response.json();
  assert.equal(link.url, "https://calander.domain.com" + link.path);
  assert.equal((await fetch(base + link.path)).status, 200);
});

test("share links are private by default, account-scoped, live and revocable", async (t) => {
  const { api, base, token } = await harness(t, {
    publicUrl: "https://calendar.example.com",
  });
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
  assert.equal(
    (await api("share", { eventId: "item", action: "create" })).status,
    401,
  );
  assert.deepEqual(
    await api("share", { eventId: "item", action: "status" }, token),
    { status: 200, active: false, path: null, url: null },
  );
  const other = await api("register", {
    email: "other@example.com",
    password: "test-password-123",
  });
  assert.equal(
    (await api("share", { eventId: "item", action: "create" }, other.token))
      .status,
    404,
  );
  const link = await api("share", { eventId: "item", action: "create" }, token);
  assert.match(link.path, /^\/s\/[a-f0-9]{64}$/);
  assert.equal(link.url, "https://calendar.example.com" + link.path);
  assert.deepEqual(
    await api("share", { eventId: "item", action: "create" }, token),
    link,
  );
  const sharedPath = "shared/" + link.path.split("/").pop();
  const publicEvent = await api(sharedPath);
  assert.deepEqual(
    Object.keys(publicEvent.event).sort(),
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
    ].sort(),
  );
  assert.equal(publicEvent.event.title, event.title);
  const page = await fetch(base + link.path);
  assert.equal(page.status, 200);
  assert.match(page.headers.get("x-robots-tag"), /noindex/);
  assert.match(await page.text(), /share.js/);
  assert.equal((await api("shared/not-a-token")).status, 404);
  const version = seed.records.find((r) => r.id === "item").version;
  await api(
    "sync",
    {
      cursor: seed.cursor,
      changes: [
        change("event", "item", { ...event, title: "Updated title" }, version),
      ],
    },
    token,
  );
  assert.equal((await api(sharedPath)).event.title, "Updated title");
  assert.equal(
    (await api("share", { eventId: "item", action: "revoke" }, other.token))
      .status,
    404,
  );
  await api("share", { eventId: "item", action: "revoke" }, token);
  assert.equal((await api(sharedPath)).status, 404);
  const replacement = await api(
    "share",
    { eventId: "item", action: "create" },
    token,
  );
  assert.notEqual(replacement.path, link.path);
  assert.equal((await api(sharedPath)).status, 404);
});
test("calendar deletion permanently revokes links, including after restoring events", async (t) => {
  const { api, token } = await harness(t);
  const seed = await api(
    "sync",
    {
      cursor: 0,
      changes: [
        change("calendar", "personal", calendar),
        change("event", "item", {
          ...event,
          allDay: true,
          start: Date.UTC(2026, 9, 11),
          end: Date.UTC(2026, 9, 13),
        }),
      ],
    },
    token,
  );
  const link = await api("share", { eventId: "item", action: "create" }, token);
  const path = "shared/" + link.path.split("/").pop();
  assert.equal(
    (await api(path)).event.end - (await api(path)).event.start,
    2 * 86400000,
  );
  const deleted = await api(
    "sync",
    {
      cursor: seed.cursor,
      changes: [
        change(
          "calendar",
          "personal",
          null,
          seed.records.find((r) => r.kind === "calendar").version,
          true,
        ),
      ],
    },
    token,
  );
  assert.equal((await api(path)).status, 404);
  await api(
    "sync",
    {
      cursor: deleted.cursor,
      changes: [
        change(
          "calendar",
          "personal",
          calendar,
          deleted.records.find((r) => r.kind === "calendar").version,
        ),
        change(
          "event",
          "item",
          event,
          deleted.records.find((r) => r.kind === "event").version,
        ),
      ],
    },
    token,
  );
  assert.equal((await api(path)).status, 404);
  assert.equal(
    (await api("share", { eventId: "item", action: "status" }, token)).active,
    false,
  );
});

test("direct event deletion permanently revokes its link", async (t) => {
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
  const link = await api("share", { eventId: "item", action: "create" }, token);
  const path = "shared/" + link.path.split("/").pop();
  const deleted = await api(
    "sync",
    {
      cursor: seed.cursor,
      changes: [
        change(
          "event",
          "item",
          null,
          seed.records.find((r) => r.id === "item").version,
          true,
        ),
      ],
    },
    token,
  );
  assert.equal((await api(path)).status, 404);
  await api(
    "sync",
    {
      cursor: deleted.cursor,
      changes: [
        change(
          "event",
          "item",
          event,
          deleted.records.find((r) => r.id === "item").version,
        ),
      ],
    },
    token,
  );
  assert.equal((await api(path)).status, 404);
});

test("a phone joining web-seeded calendars acknowledges identical seeds without conflicts", async (t) => {
  const { api, token } = await harness(t);
  const seeds = defaultRecords();
  assert.equal(seeds.filter((r) => r.kind === "calendar").length, 3);
  assert.equal(seeds.filter((r) => r.kind === "event").length, 366);
  for (const r of seeds.filter((r) => r.kind === "event")) {
    const digest = createHash("sha256")
      .update(`${r.data.title}:${r.data.start}`)
      .digest("hex");
    assert.equal(r.id, "seed:holiday:" + digest);
  }
  const web = await api(
    "sync",
    { cursor: 0, changes: seeds.map((r) => change(r.kind, r.id, r.data)) },
    token,
  );
  assert.equal(web.status, 200);
  const phone = await api(
    "sync",
    {
      cursor: 0,
      changes: seeds.map((r) =>
        change(
          r.kind,
          r.id,
          Object.fromEntries(Object.entries(r.data).reverse()),
        ),
      ),
    },
    token,
  );
  assert.equal(phone.status, 200);
  assert.deepEqual(phone.conflicts, []);
  assert.equal(phone.cursor, web.cursor);
  assert.equal(phone.records.length, seeds.length);
  assert.deepEqual((await api("history", null, token)).history, []);
  const personal = web.records.find((r) => r.id === "seed:calendar:Personal");
  await api(
    "sync",
    {
      cursor: web.cursor,
      changes: [
        change(
          "calendar",
          personal.id,
          { ...personal.data, name: "My personal calendar" },
          personal.version,
        ),
      ],
    },
    token,
  );
  const laterPhone = await api(
    "sync",
    { cursor: 0, changes: [change("calendar", personal.id, personal.data)] },
    token,
  );
  assert.deepEqual(laterPhone.conflicts, [personal.id]);
  assert.equal(
    laterPhone.records.find((r) => r.id === personal.id).data.name,
    "My personal calendar",
  );
});
