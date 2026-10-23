import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp } from "../src.js";

const calendar = { name: "Private calendar", color: -13388167, visible: false, keywords: "secret keyword", defaultImportance: "HIGH" };
const event = {
  calendarId: "private", title: "Private title", description: "Private notes", location: "Private address",
  start: Date.UTC(2026, 9, 22, 15), end: Date.UTC(2026, 9, 22, 16), allDay: false,
  color: null, recurrence: "WEEKLY", recurrenceUntil: 21000, exceptions: [20755],
  reminders: [10], kind: "TASK", completions: [20740], importance: "HIGH",
  people: [{ name: "Private contact" }],
};

test("Public link combines calendars, strips private details, updates live and revokes permanently", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "calendar-public-"));
  const dbPath = join(dir, "calendar.sqlite");
  let app, base;
  const start = async () => {
    app = createApp({ dbPath, publicUrl: "https://calendar.example.com" });
    await new Promise((r) => app.server.listen(0, "127.0.0.1", r));
    base = "http://127.0.0.1:" + app.server.address().port;
  };
  t.after(() => { app.close(); rmSync(dir, { recursive: true, force: true }); });
  await start();
  const api = async (path, body, token) => {
    const res = await fetch(base + "/api/v1/" + path, {
      method: body ? "POST" : "GET",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    assert.equal(res.headers.get("cache-control"), "no-store");
    return { status: res.status, ...await res.json() };
  };
  const a = await api("register", { email: "private@example.com", username: "Alice", password: "test-password-123" });
  const b = await api("register", { email: "other@example.com", password: "test-password-123" });
  const share = (action, token = a.token) => api("public", { action }, token);
  const change = (kind, id, data, baseVersion = 0, deleted = false) =>
    ({ kind, id, data, baseVersion, deleted, operationId: crypto.randomUUID() });
  const sync = (changes, token = a.token) => api("sync", { cursor: 0, changes }, token);
  assert.equal((await api("public", { action: "create" })).status, 401);
  assert.equal((await share("invalid")).status, 400);
  assert.equal((await share("status")).active, false);
  assert.equal((await share("status", b.token)).active, false);
  const write = await sync([
    change("calendar", "private", calendar),
    change("calendar", "open", { ...calendar, name: "Open calendar", publicDetails: true }),
    change("event", "private-event", event),
    change("event", "open-event", { ...event, calendarId: "open", title: "Public title", allDay: true }),
  ]);
  assert.equal(write.status, 200);
  await sync([change("calendar", "other", calendar), change("event", "other-event", { ...event, calendarId: "other", title: "Other account" })], b.token);
  const first = await share("create");
  assert.match(first.path, /^\/public\/[a-f0-9]{64}$/);
  assert.equal(first.url, "https://calendar.example.com" + first.path);
  assert.equal((await share("create")).path, first.path, "create retries retain one address");
  assert.equal((await share("status", b.token)).active, false);
  const read = () => api(first.path.slice(1));
  let publicData = await read();
  assert.equal(publicData.events.length, 2, "hidden calendars are included too");
  const busy = publicData.events.find((e) => e.title === "Alice is busy");
  assert.deepEqual(busy, {
    start: event.start, end: event.end, allDay: false, recurrence: event.recurrence,
    recurrenceUntil: event.recurrenceUntil, exceptions: event.exceptions, title: "Alice is busy",
  });
  const open = publicData.events.find((e) => e.title === "Public title");
  assert.equal(open.description, event.description);
  assert.equal(open.location, event.location);
  assert.equal(open.calendar, "Open calendar");
  for (const e of publicData.events) {
    for (const key of ["calendarId", "id", "reminders", "people", "completions", "importance", "keywords"])
      assert.equal(Object.hasOwn(e, key), false, key);
  }
  assert.equal(JSON.stringify(publicData).includes("Other account"), false);
  assert.equal(JSON.stringify(publicData).includes("private@example.com"), false);
  const html = await fetch(base + first.path);
  assert.equal(html.status, 200);
  assert.match(await html.text(), /src="\/app.js"/);

  app.close();
  await start();
  assert.equal((await share("status")).path, first.path, "address survives server restart");
  assert.equal((await read()).events.length, 2);
  const versions = new Map(write.records.map((r) => [r.id, r.version]));
  await sync([change("calendar", "private", { ...calendar, publicDetails: true }, versions.get("private"))]);
  assert.equal((await read()).events.find((e) => e.title === "Private title").description, "Private notes");
  const current = await api("sync", { cursor: 0, changes: [] }, a.token);
  const privateCalendar = current.records.find((r) => r.id === "private");
  const toggled = await sync([change("calendar", "private", { ...calendar, publicDetails: false }, privateCalendar.version)]);
  assert.equal(toggled.status, 200);
  assert.ok((await read()).events.some((e) => e.title === "Alice is busy"));
  await api("profile", { username: "Alice_2" }, a.token);
  assert.ok((await read()).events.some((e) => e.title === "Alice_2 is busy"));
  await api("profile", { username: "" }, a.token);
  assert.ok((await read()).events.some((e) => e.title === "User is busy"));
  await sync([change("calendar", "open", null, versions.get("open"), true)]);
  assert.equal((await read()).events.length, 1, "deleted calendar and its events disappear");
  assert.equal((await share("revoke", b.token)).active, false);
  assert.equal((await read()).status, 200, "other account cannot revoke the link");
  assert.equal((await share("revoke")).active, false);
  assert.equal((await read()).status, 404);
  const second = await share("create");
  assert.notEqual(second.path, first.path);
  assert.equal((await read()).status, 404, "old links never become valid again");
  assert.equal((await api(second.path.slice(1))).status, 200);
  assert.equal((await api("public/invalid")).status, 404);
});

test("calendar publicDetails accepts only booleans and defaults to private", async (t) => {
  const app = createApp({ dbPath: ":memory:" });
  await new Promise((r) => app.server.listen(0, "127.0.0.1", r));
  t.after(() => app.close());
  const base = "http://127.0.0.1:" + app.server.address().port;
  const account = await fetch(base + "/api/v1/register", { method: "POST", body: JSON.stringify({ email: "test@example.com", password: "test-password-123" }) }).then((r) => r.json());
  for (const value of [null, "true", 1, {}, []]) {
    const response = await fetch(base + "/api/v1/sync", { method: "POST",
      headers: { Authorization: "Bearer " + account.token },
      body: JSON.stringify({ cursor: 0, changes: [{ kind: "calendar", id: "bad", baseVersion: 0, deleted: false,
        operationId: crypto.randomUUID(), data: { ...calendar, publicDetails: value } }] }),
    });
    assert.equal(response.status, 400);
  }
});
