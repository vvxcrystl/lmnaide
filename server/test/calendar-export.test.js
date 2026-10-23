import { test } from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../src.js";
import { googleCalendarUrl, lmnaideAppUrl, recurrenceRule, toICalendar } from "../public/calendar-export.js";

const event = {
  title: "Lunch & planning + friends",
  description: "Notes, with; punctuation\\and\r\nnew lines\nEND:VEVENT",
  location: "Café, Montréal",
  start: Date.UTC(2026, 9, 23, 18),
  end: Date.UTC(2026, 9, 23, 19),
  allDay: false,
  recurrence: "NONE",
  recurrenceUntil: null,
  kind: "EVENT",
};
const unfold = (value) => value.replaceAll(/\r\n[ \t]/g, "");

test("calendar links encode content without changing times or all-day end dates", () => {
  const google = new URL(googleCalendarUrl(event));
  assert.equal(google.origin, "https://calendar.google.com");
  assert.equal(google.searchParams.get("text"), event.title);
  assert.equal(google.searchParams.get("details"), event.description);
  assert.equal(google.searchParams.get("location"), event.location);
  assert.equal(google.searchParams.get("dates"), "20261023T180000Z/20261023T190000Z");
  assert.equal(google.searchParams.has("recur"), false);
  const allDay = { ...event, allDay: true, start: Date.UTC(2026, 9, 23), end: Date.UTC(2026, 9, 25) };
  assert.equal(new URL(googleCalendarUrl(allDay)).searchParams.get("dates"), "20261023/20261025");
  const fallback = "https://example.com/?addShared=abc";
  const app = lmnaideAppUrl({ ...event, people: ["private"], calendarId: "secret" }, fallback);
  const [uri, intent] = app.split("#Intent;");
  const params = new URL(uri.replace("intent:", "lmnaide:")).searchParams;
  assert.equal(params.get("title"), event.title);
  assert.equal(params.get("description"), event.description);
  assert.equal(params.has("people"), false);
  assert.equal(params.has("calendarId"), false);
  assert.match(intent, /package=dev\.lmnaide\.calendar;/);
  assert.ok(intent.includes("S.browser_fallback_url=" + encodeURIComponent(fallback)));
});

test("iCalendar escapes content injection and folds Unicode at 75 octets", () => {
  const text = toICalendar({ ...event, title: "🍋é".repeat(80) }, "test@lmnaide", Date.UTC(2026, 9, 22));
  for (const line of text.split("\r\n")) assert.ok(Buffer.byteLength(line) <= 75);
  const lines = unfold(text).split("\r\n");
  assert.equal(lines.filter((line) => line === "END:VEVENT").length, 1);
  assert.ok(lines.includes("SUMMARY:" + "🍋é".repeat(80)));
  assert.ok(lines.includes("DESCRIPTION:Notes\\, with\\; punctuation\\\\and\\nnew lines\\nEND:VEVENT"));
  assert.ok(lines.includes("DTSTAMP:20261022T000000Z"));
  assert.ok(lines.includes("DTSTART:20261023T180000Z"));
  assert.ok(lines.includes("DTEND:20261023T190000Z"));
  assert.ok(text.endsWith("END:VCALENDAR\r\n"));
});

test("repeat patterns and inclusive end days use the DTSTART value type", () => {
  const until = Date.UTC(2026, 10, 2) / 86400000;
  for (const [recurrence, rule] of Object.entries({
    DAILY: "FREQ=DAILY", WEEKDAYS: "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
    WEEKLY: "FREQ=WEEKLY", MONTHLY: "FREQ=MONTHLY", YEARLY: "FREQ=YEARLY",
  })) {
    const recurring = { ...event, recurrence, recurrenceUntil: until };
    assert.equal(recurrenceRule(recurring), rule + ";UNTIL=20261102T235959Z");
    assert.equal(recurrenceRule({ ...recurring, allDay: true }), rule + ";UNTIL=20261102");
    assert.equal(new URL(googleCalendarUrl(recurring)).searchParams.get("recur"), "RRULE:" + recurrenceRule(recurring));
  }
  const text = unfold(toICalendar({ ...event, allDay: true, start: Date.UTC(2026, 9, 23), end: Date.UTC(2026, 9, 25) }, "all-day"));
  assert.match(text, /DTSTART;VALUE=DATE:20261023\r\nDTEND;VALUE=DATE:20261025/);
  const task = unfold(toICalendar({ ...event, kind: "TASK", end: event.start }, "task"));
  assert.match(task, /BEGIN:VEVENT/);
  assert.doesNotMatch(task, /DTEND/);
});

test("anonymous downloads are live, private, and revoked along with the shared link", async (t) => {
  const app = createApp({ dbPath: ":memory:" });
  await new Promise((resolve) => app.server.listen(0, "127.0.0.1", resolve));
  t.after(() => app.close());
  const base = "http://127.0.0.1:" + app.server.address().port;
  let token;
  const api = async (path, body) => {
    const response = await fetch(base + "/api/v1/" + path, {
      method: "POST", headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) },
      body: JSON.stringify(body),
    });
    assert.equal(response.status, 200, await response.clone().text());
    return response.json();
  };
  ({ token } = await api("register", { email: "export@example.com", password: "test-calendar-password" }));
  const record = { ...event, calendarId: "personal", reminders: [10], importance: "HIGH", color: null, exceptions: [], completions: [], people: ["private-contact"] };
  const change = (kind, id, data, baseVersion = 0, deleted = false) => ({ kind, id, data, baseVersion, deleted, operationId: crypto.randomUUID() });
  const seed = await api("sync", { cursor: 0, changes: [
    change("calendar", "personal", { name: "Private calendar", color: -1, visible: true, keywords: "secret", defaultImportance: null }),
    change("event", "export", record),
  ] });
  const share = await api("share", { eventId: "export", action: "create" });
  const path = "/api/v1/shared/" + share.path.split("/").pop() + "/event.ics";
  const response = await fetch(base + path);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-type"), "text/calendar; charset=utf-8");
  assert.match(response.headers.get("content-disposition"), /attachment; filename="event.ics"/);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const first = unfold(await response.text());
  assert.doesNotMatch(first, /Private calendar|secret|private-contact|VALARM|HIGH/);
  const version = seed.records.find((r) => r.id === "export").version;
  await api("sync", { cursor: seed.cursor, changes: [change("event", "export", { ...record, title: "New title" }, version)] });
  const updated = unfold(await (await fetch(base + path)).text());
  assert.match(updated, /SUMMARY:New title/);
  assert.equal(updated.match(/UID:.*/)[0], first.match(/UID:.*/)[0]);
  await api("share", { eventId: "export", action: "revoke" });
  assert.equal((await fetch(base + path)).status, 404);
  assert.equal((await fetch(base + "/api/v1/shared/not-a-token/event.ics")).status, 404);
});
