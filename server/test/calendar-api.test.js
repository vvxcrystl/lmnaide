import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp } from "../src.js";

const dates = { start: Date.UTC(2026, 9, 22, 14), end: Date.UTC(2026, 9, 22, 15) };
async function harness(t, options = {}) {
  let app;
  let base;
  const start = async () => {
    app = createApp({ dbPath: ":memory:", ...options });
    await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
    base = `http://127.0.0.1:${app.server.address().port}/api/v1/`;
  };
  await start();
  t.after(() => app.close());
  const request = async (method, path, data, token) => {
    const res = await fetch(base + path, {
      method,
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: data === undefined ? undefined : JSON.stringify(data),
    });
    return { status: res.status, body: await res.json() };
  };
  const account = await request("POST", "register", { email: "api@example.com", password: "test-password-123" });
  const token = account.body.token;
  const call = (method, path, data) => request(method, path, data, token);
  return { call, request, token, restart: async () => { await app.close(); await start(); } };
}
async function createRecords(call) {
  const calendar = await call("POST", "calendars", { name: "Automation", id: "automation" });
  assert.equal(calendar.status, 201);
  const event = await call("POST", "events", { calendarId: calendar.body.id, title: "API meeting", ...dates });
  assert.equal(event.status, 201);
  return { calendar: calendar.body, event: event.body };
}

test("REST CRUD reaches sync and preserves server-owned event origin after device edits", async (t) => {
  const { call } = await harness(t);
  const { calendar, event } = await createRecords(call);
  assert.equal(event.data.createdProgrammatically, true);
  assert.equal(event.data.recurrence, "NONE");
  const pulled = await call("POST", "sync", { cursor: 0, changes: [] });
  assert.deepEqual(pulled.body.records.find((r) => r.id === event.id), event);
  assert.equal((await call("GET", `events/${event.id}`)).body.data.createdProgrammatically, true);

  const updated = await call("PATCH", `events/${event.id}`, { version: event.version, title: "Updated", createdProgrammatically: false });
  assert.equal(updated.status, 200);
  assert.equal(updated.body.data.title, "Updated");
  assert.equal(updated.body.data.createdProgrammatically, true);
  const { createdProgrammatically, ...legacyDeviceData } = updated.body.data;
  const synced = await call("POST", "sync", { cursor: 0, changes: [{
    kind: "event", id: event.id, baseVersion: updated.body.version, deleted: false,
    operationId: crypto.randomUUID(), data: { ...legacyDeviceData, description: "Device edit" },
  }] });
  const afterDevice = synced.body.records.find((r) => r.id === event.id);
  assert.equal(afterDevice.data.createdProgrammatically, true);
  assert.equal(afterDevice.data.description, "Device edit");
  assert.equal((await call("PATCH", `events/${event.id}`, { version: event.version, title: "Stale" })).status, 409);
  assert.equal((await call("PATCH", `events/${event.id}`, { title: "No version" })).status, 400);
  assert.equal((await call("DELETE", `events/${event.id}`, { version: event.version })).status, 409);
  const history = await call("GET", "history");
  assert(history.body.history.some((entry) => entry.recordId === event.id && entry.reason === "previous"));

  const removed = await call("DELETE", `events/${event.id}`, { version: afterDevice.version });
  assert.equal(removed.status, 200);
  assert.equal(removed.body.deleted, true);
  assert.equal((await call("GET", `events/${event.id}`)).status, 404);
  assert.deepEqual((await call("GET", "events")).body.records, []);
  assert.equal((await call("PATCH", `calendars/${calendar.id}`, { version: calendar.version, name: "Scripts" })).status, 200);
});

test("API access is authenticated, account-isolated and revoked on logout", async (t) => {
  const { call, request, token } = await harness(t);
  const { event, calendar } = await createRecords(call);
  const other = (await request("POST", "register", { email: "other@example.com", password: "test-password-123" })).body.token;
  for (const resource of ["events", "calendars"]) {
    const id = resource === "events" ? event.id : calendar.id;
    assert.equal((await request("GET", resource)).status, 401);
    assert.deepEqual((await request("GET", resource, undefined, other)).body.records, []);
    assert.equal((await request("GET", `${resource}/${id}`, undefined, other)).status, 404);
    assert.equal((await request("PATCH", `${resource}/${id}`, { version: 1, title: "Stolen" }, other)).status, 404);
    assert.equal((await request("DELETE", `${resource}/${id}`, { version: 1 }, other)).status, 404);
  }
  assert.equal((await request("POST", "events", { calendarId: calendar.id, ...dates }, other)).status, 400);
  assert.equal((await call("POST", "logout", {})).status, 200);
  assert.equal((await request("GET", "events", undefined, token)).status, 401);
});

test("API validation, bounded pages, calendar filters and deletion cascade", async (t) => {
  const { call } = await harness(t);
  const { calendar, event } = await createRecords(call);
  const extra = await call("POST", "events", { calendarId: calendar.id, id: "second", title: "Second", ...dates });
  assert.equal(extra.status, 201);
  assert.equal((await call("POST", "events", { calendarId: calendar.id, id: "second", ...dates })).status, 409);
  assert.equal((await call("POST", "calendars", { name: "  " })).status, 400);
  assert.equal((await call("POST", "calendars", { name: "Invalid path", id: "." })).status, 400);
  assert.equal((await call("POST", "events", { calendarId: "missing", ...dates })).status, 400);
  assert.equal((await call("POST", "events", { calendarId: calendar.id, ...dates, end: dates.start - 1 })).status, 400);
  assert.equal((await call("POST", "events", { calendarId: calendar.id, ...dates, reminders: [-1] })).status, 400);
  assert.equal((await call("PATCH", `events/${event.id}`, { version: event.version, calendarId: "missing" })).status, 400);
  for (const query of ["limit=0", "limit=501", "cursor=-1", "cursor=bad"]) {
    assert.equal((await call("GET", `events?${query}`)).status, 400);
  }
  const first = (await call("GET", "events?limit=1")).body;
  assert.equal(first.records.length, 1);
  assert.equal(first.more, true);
  const second = (await call("GET", `events?limit=1&cursor=${first.nextCursor}`)).body;
  assert.equal(second.records.length, 1);
  assert.notEqual(first.records[0].id, second.records[0].id);
  assert.equal(second.more, false);
  assert.deepEqual((await call("GET", "events?calendarId=other")).body.records, []);
  assert.equal((await call("GET", `events?calendarId=${calendar.id}`)).body.records.length, 2);
  assert.equal((await call("DELETE", `calendars/${calendar.id}`, { version: calendar.version })).status, 200);
  const sync = await call("POST", "sync", { cursor: 0, changes: [] });
  assert.equal(sync.body.records.length, 3);
  assert(sync.body.records.every((record) => record.deleted));
});

test("device-created events cannot forge origin, and API edits do not relabel them", async (t) => {
  const { call } = await harness(t);
  const { event } = await createRecords(call);
  const { createdProgrammatically, ...data } = event.data;
  const pushed = await call("POST", "sync", { cursor: 0, changes: [{
    kind: "event", id: "manual", baseVersion: 0, deleted: false, operationId: crypto.randomUUID(),
    data: { ...data, createdProgrammatically: true },
  }] });
  const manual = pushed.body.records.find((r) => r.id === "manual");
  assert.equal(manual.data.createdProgrammatically, undefined);
  const edited = await call("PATCH", "events/manual", { version: manual.version, title: "API edit" });
  assert.equal(edited.status, 200);
  assert.equal(edited.body.data.createdProgrammatically, undefined);
});

test("REST writes obey the account storage quota", async (t) => {
  const { call } = await harness(t, { quotaBytes: 3000 });
  const calendar = await call("POST", "calendars", { name: "Quota" });
  assert.equal(calendar.status, 201);
  assert.equal((await call("POST", "events", { calendarId: calendar.body.id, ...dates, description: "x".repeat(2000) })).status, 413);
  assert.deepEqual((await call("GET", "events")).body.records, []);
  assert.equal((await call("DELETE", `calendars/${calendar.body.id}`, { version: calendar.body.version })).status, 200);
});

test("API records and their origin survive a database restart", async (t) => {
  const dir = mkdtempSync(join(tmpdir(), "calendar-api-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const { call, restart } = await harness(t, { dbPath: join(dir, "calendar.sqlite") });
  const { event } = await createRecords(call);
  await restart();
  assert.deepEqual((await call("GET", `events/${event.id}`)).body, event);
});

test("large REST lists respect the response byte limit and paginate to completion", async (t) => {
  const { call } = await harness(t);
  const { calendar, event } = await createRecords(call);
  const changes = Array.from({ length: 60 }, (_, i) => ({
    kind: "event", id: `large-${i}`, baseVersion: 0, deleted: false,
    operationId: crypto.randomUUID(),
    data: { ...event.data, calendarId: calendar.id, description: "x".repeat(20000) },
  }));
  assert.equal((await call("POST", "sync", { cursor: 0, changes })).status, 200);
  const ids = new Set();
  let cursor = 0;
  let pages = 0;
  let more;
  do {
    const response = await call("GET", `events?limit=500&cursor=${cursor}`);
    assert.equal(response.status, 200);
    assert(Buffer.byteLength(JSON.stringify(response.body)) <= 1024 * 1024);
    assert(response.body.nextCursor > cursor, "Each nonempty page advances");
    response.body.records.forEach(record => ids.add(record.id));
    cursor = response.body.nextCursor;
    more = response.body.more;
    assert(++pages <= 5, "Pagination must terminate");
  } while (more);
  assert.equal(ids.size, 61);
  assert(pages > 1);
});
