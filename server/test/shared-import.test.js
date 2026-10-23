import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";

const source = readFileSync(new URL("../public/app.js", import.meta.url), "utf8");
function handler(name, globals, expose = "") {
  const match = source.match(new RegExp(`^(?:async )?function ${name}\\([^]*?^}$`, "m"));
  assert.ok(match);
  return runInNewContext(`${match[0]}\n({${name}${expose ? "," + expose : ""}})`, { publicToken: null, ...globals });
}

test("shared imports wait for starter calendars and preserve the pending event through sign-in", async () => {
  let ready;
  const calls = [];
  const event = { start: Date.UTC(2026, 9, 23), allDay: true };
  const app = handler("openSharedImport", {
    token: null, sharedImportToken: "a".repeat(64), loadingSharedImport: false,
    sessionGeneration: 0, syncQueue: new Promise((resolve) => ready = resolve),
    calendars: () => [{ id: "personal" }],
    api: async (path) => { calls.push(path); return { event }; },
    wall: (value) => new Date(value),
    openEditor: (options) => calls.push(options),
    location: { href: "https://example.com/?addShared=" + "a".repeat(64) },
    URL, history: { replaceState: (_state, _title, url) => calls.push(url.href) },
    snackbar() {},
  }, `set token(value) { token = value; }, get pending() { return sharedImportToken; }`);
  await app.openSharedImport();
  assert.equal(calls.length, 0);
  assert.ok(app.pending);
  app.token = "recipient-session";
  const pending = app.openSharedImport();
  await Promise.resolve();
  assert.equal(calls.length, 0, "Wait for starter calendars to finish syncing");
  ready();
  await pending;
  assert.equal(calls[0], "shared/" + "a".repeat(64));
  assert.equal(calls[1].sharedEvent, event);
  assert.equal(calls[2], "https://example.com/");
  assert.equal(app.pending, null);
  await app.openSharedImport();
  assert.equal(calls.length, 3, "A successful import opens only once");
});

test("shared imports discard responses after an account change", async () => {
  let finish;
  let opened = false;
  const app = handler("openSharedImport", {
    token: "account-a", sharedImportToken: "a".repeat(64), loadingSharedImport: false,
    sessionGeneration: 0, syncQueue: Promise.resolve(), calendars: () => [{}],
    api: () => new Promise((resolve) => finish = resolve),
    openEditor: () => opened = true, snackbar() {},
  }, `set generation(value) { sessionGeneration = value; }`);
  const pending = app.openSharedImport();
  await new Promise(setImmediate);
  app.generation = 1;
  finish({ event: {} });
  await pending;
  assert.equal(opened, false);
});

test("the editor imports an independent all-day task with its repeat pattern and inclusive last day", () => {
  const startOfDay = (date) => new Date(date.getFullYear(), date.getMonth(), date.getDate());
  const addDays = (date, days) => new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);
  const app = handler("openEditor", {
    ed: null,
    calendars: () => [{ id: "recipient-calendar", data: { defaultImportance: "LOW" } }],
    startOfDay, addDays,
    sameDay: () => false,
    atMinute: (date, minute) => new Date(date.getTime() + minute * 60000),
    minutesOf: (date) => date.getHours() * 60 + date.getMinutes(),
    maxDate: (a, b) => a > b ? a : b,
    wall: (value) => {
      const utc = new Date(value);
      return new Date(utc.getUTCFullYear(), utc.getUTCMonth(), utc.getUTCDate());
    },
    fromEpochDay: (value) => new Date(value * 86400000),
    renderEditor() {}, openPage() {},
    $: () => ({ querySelector: () => ({ focus() {} }) }),
  }, `get editor() { return ed; }`);
  const start = Date.UTC(2026, 9, 23);
  app.openEditor({ date: new Date(start), sharedEvent: {
    title: "Shared task", description: "Notes", location: "Café",
    start, end: Date.UTC(2026, 9, 26), allDay: true, kind: "TASK",
    recurrence: "WEEKLY", recurrenceUntil: 20758,
    calendarId: "owner-calendar", people: ["private"], completions: [20748],
  } });
  const editor = app.editor;
  assert.equal(editor.series, null);
  assert.equal(editor.state.calendarId, "recipient-calendar");
  assert.equal(editor.state.title, "Shared task");
  assert.equal(editor.state.description, "Notes");
  assert.equal(editor.state.location, "Café");
  assert.equal(editor.state.startDate.getDate(), 23);
  assert.equal(editor.state.endDate.getDate(), 25);
  assert.equal(editor.state.recurrence, "WEEKLY");
  assert.equal(editor.state.until.getTime(), 20758 * 86400000);
  assert.equal(editor.state.kind, "TASK");
  assert.equal(editor.state.people, undefined);
  assert.equal(editor.state.completions, undefined);
});
