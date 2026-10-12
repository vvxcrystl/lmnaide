import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { runInNewContext } from "node:vm";
import { expandOccurrences, nextOccurrence } from "../public/recurrence.js";

const source = readFileSync(new URL("../public/app.js", import.meta.url), "utf8");
function handlers(names, globals = {}, expose = "") {
  const code = names.map((name) => {
    const match = source.match(
      new RegExp(`^(?:async )?function ${name}\\([^]*?^}`, "m"),
    );
    assert.ok(match, `Missing handler ${name}`);
    return match[0];
  }).join("\n");
  return runInNewContext(
    `${code}\n({${names.join(",")}${expose ? "," + expose : ""}})`,
    globals,
  );
}

function syncHarness(api) {
  return handlers(
    ["sync", "performSync", "changeBatches", "invalidateSession"],
    {
      syncQueue: Promise.resolve(),
      sessionGeneration: 0,
      token: "session",
      records: [],
      cursor: 0,
      busy: false,
      lastSync: 0,
      syncMessage: "",
      quarantinedChanges: [],
      invalidClientChange: () => null,
      api,
      staleSessionError: () => Object.assign(new Error("stale"), { stale: true }),
      seedNewAccount() {},
      refresh() {},
      snackbar() {},
      renderSettings() {},
      $: () => ({ open: false }),
    },
    `get cursor() { return cursor; }, set cursor(value) { cursor = value; },
     get records() { return records; }, set records(value) { records = value; },
     get quarantinedChanges() { return quarantinedChanges; }`,
  );
}

test("API responses are invalidated by a session change", async () => {
  let finishFetch;
  let requestOptions;
  const app = handlers(
    ["api"],
    {
      sessionGeneration: 0,
      token: "account-a-token",
      fetch: (_url, options) => {
        requestOptions = options;
        return new Promise((resolve) => (finishFetch = resolve));
      },
      staleSessionError: () => Object.assign(new Error("stale"), { stale: true }),
    },
    `get sessionGeneration() { return sessionGeneration; },
     set sessionGeneration(value) { sessionGeneration = value; },
     get token() { return token; }, set token(value) { token = value; }`,
  );
  const pending = app.api("sync", { cursor: 0, changes: [] });
  await new Promise(setImmediate);
  assert.equal(requestOptions.headers.Authorization, "Bearer account-a-token");
  app.sessionGeneration = 1;
  app.token = "account-b-token";
  finishFetch({ ok: true, json: async () => ({ records: [{ id: "a-private" }] }) });
  await assert.rejects(pending, (error) => error.stale === true);
});

test("sync drops late account responses, cancels queued work, and follows pages", async () => {
  let finishOld;
  const calls = [];
  const app = syncHarness((_path, body) => {
    calls.push(body);
    if (calls.length === 1)
      return new Promise((resolve) => (finishOld = resolve));
    if (calls.length === 2)
      return Promise.resolve({
        cursor: 5,
        records: [{ kind: "event", id: "b-one", deleted: false }],
        conflicts: [],
        more: true,
      });
    return Promise.resolve({
      cursor: 8,
      records: [{ kind: "event", id: "b-two", deleted: false }],
      conflicts: [],
      more: false,
    });
  });
  const oldSync = app.sync([{ operationId: "old" }]);
  const oldQueued = app.sync([{ operationId: "old-queued" }]);
  await new Promise(setImmediate);
  assert.equal(calls.length, 1);

  app.invalidateSession();
  app.records = [];
  app.cursor = 0;
  assert.equal(await app.sync(), true);
  assert.equal(calls.length, 3);
  assert.equal(JSON.stringify(calls[2].changes), "[]", "follow-up pages send no changes");
  assert.equal(app.cursor, 8);
  assert.deepEqual(Array.from(app.records, (r) => r.id), ["b-one", "b-two"]);

  finishOld({
    cursor: 99,
    records: [{ kind: "event", id: "a-private", deleted: false }],
    conflicts: [],
  });
  await assert.rejects(oldSync, (error) => error.stale === true);
  await assert.rejects(oldQueued, (error) => error.stale === true);
  assert.equal(calls.length, 3, "queued work from the old session never runs");
  assert.equal(app.cursor, 8);
  assert.deepEqual(Array.from(app.records, (r) => r.id), ["b-one", "b-two"]);
});

test("invalid sync changes are quarantined while unrelated changes keep syncing", async () => {
  const sent = [];
  const app = syncHarness(async (_path, body) => {
    sent.push(body.changes.map((c) => c.operationId));
    if (sent.length === 1)
      throw Object.assign(new Error("Invalid event dates"), {
        operationId: "bad",
      });
    return { cursor: 2, records: [], conflicts: [], more: false };
  });
  const result = await app.sync([
    { operationId: "good" },
    { operationId: "bad" },
  ]);
  assert.equal(result, false);
  assert.equal(JSON.stringify(sent), '[["good","bad"],["good"]]');
  assert.equal(app.quarantinedChanges.length, 1);
  assert.equal(app.quarantinedChanges[0].operationId, "bad");
});

test("change batching keeps an atomic group together under the request limit", () => {
  const { changeBatches } = handlers(["changeBatches"]);
  const changes = Array.from({ length: 999 }, (_, i) => ({ operationId: `${i}` }));
  changes.push({ operationId: "split-a", group: "split" });
  changes.push({ operationId: "split-b", group: "split" });
  const batches = changeBatches(changes);
  assert.equal(JSON.stringify(Array.from(batches, (batch) => batch.length)), "[999,2]");
  assert.equal(
    JSON.stringify(Array.from(batches[1], (change) => change.operationId)),
    '["split-a","split-b"]',
  );
});

test("event save retries reuse the same draft record and operation payload", async () => {
  const { editorSaveAttempt } = handlers(["editorSaveAttempt"]);
  const editor = {};
  let builds = 0;
  const payload = {
    target: { id: "draft-record" },
    changes: [{ id: "draft-record", operationId: "stable-op" }],
  };
  const first = await editorSaveAttempt(editor, () => {
    builds++;
    return payload;
  });
  const retry = await editorSaveAttempt(editor, () => {
    builds++;
    return { changes: [{ operationId: "new-op" }] };
  });
  assert.strictEqual(retry, first);
  assert.strictEqual(editor.pendingSave, payload);
  assert.equal(builds, 1);
  assert.equal(retry.changes[0].operationId, "stable-op");
});

test("calendar save remains open on failure and finishes only after success", async () => {
  let finishValue;
  let completeSave;
  const button = { disabled: false, textContent: "Save" };
  const form = { inert: false };
  const cancel = { disabled: false };
  let result = false;
  const app = handlers(["submitCalendarSave"], {
    save: () => new Promise((resolve) => (completeSave = resolve)),
  });
  const failed = app.submitCalendarSave([], (value) => (finishValue = value), button, form, cancel);
  assert.equal(button.disabled, true);
  assert.equal(form.inert, true);
  assert.equal(finishValue, undefined);
  completeSave(result);
  assert.equal(await failed, false);
  assert.equal(finishValue, undefined);
  assert.equal(button.textContent, "Retry save");
  assert.equal(cancel.disabled, true);
  const retry = app.submitCalendarSave(
    [],
    (value) => (finishValue = value),
    button,
    form,
    cancel,
  );
  completeSave(true);
  assert.equal(await retry, true);
  assert.equal(finishValue, true);
});

test("settings sync refresh preserves a dirty username form and focused draft", () => {
  let current;
  const oldInput = {
    value: "unsaved_name",
    dataset: { dirty: "true" },
    selectionStart: 6,
    selectionEnd: 6,
  };
  const document = { activeElement: oldInput };
  const dialog = {
    querySelector(selector) {
      return selector === ".account-username input" ? current : null;
    },
  };
  const app = handlers(["captureUsernameDraft", "restoreUsernameDraft"], {
    document,
  });
  current = oldInput;
  const draft = app.captureUsernameDraft(dialog);
  assert.equal(draft.dirty, true);
  current = {
    value: "server_name",
    dataset: { dirty: "false" },
    focus() { document.activeElement = this; },
    setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; },
  };
  app.restoreUsernameDraft(dialog, draft);
  assert.equal(current.value, "unsaved_name");
  assert.equal(current.dataset.dirty, "true");
  assert.equal(document.activeElement, current);
  assert.equal(current.selectionStart, 6);
  assert.equal(current.selectionEnd, 6);
});

test("a sync refresh updates settings status without rebuilding a dirty form", () => {
  let rebuilt = 0;
  let updated = 0;
  const dialog = { replaceChildren() { rebuilt++; } };
  const { renderSettings } = handlers(["renderSettings"], {
    $: () => dialog,
    captureUsernameDraft: () => ({ dirty: true }),
    updateSettingsSyncStatus: () => updated++,
  });
  renderSettings({ statusOnly: true });
  assert.equal(updated, 1);
  assert.equal(rebuilt, 0);
});

test("calendar deletion submits just the parent record", () => {
  const calls = [];
  const { calendarDeleteChanges } = handlers(["calendarDeleteChanges"], {
    change: (...args) => {
      calls.push(args);
      return { kind: "calendar", id: args[0].id, deleted: true };
    },
  });
  const changes = calendarDeleteChanges({ id: "calendar-id" });
  assert.equal(changes.length, 1);
  assert.equal(JSON.stringify(calls), '[[{"id":"calendar-id"},null,true]]');
});

test("client validation accepts empty titles and blocks out-of-range reminders", () => {
  const { invalidClientChange } = handlers(["invalidClientChange"], {
    DAY: 86400000,
    MIN_EVENT_TIME: Date.UTC(1900, 0, 1),
    MAX_EVENT_TIME: Date.UTC(2201, 0, 1) - 1,
  });
  const start = Date.UTC(2026, 9, 12, 9);
  const change = {
    kind: "event",
    id: "event-id",
    baseVersion: 0,
    operationId: "operation-id",
    deleted: false,
    data: {
      calendarId: "calendar-id",
      title: "",
      description: "",
      location: "",
      start,
      end: start + 3600000,
      allDay: false,
      color: null,
      recurrence: "NONE",
      recurrenceUntil: null,
      exceptions: [],
      reminders: [0, 525600],
      kind: "EVENT",
      completions: [],
      importance: null,
    },
  };
  assert.equal(invalidClientChange(change), null);
  change.data.reminders = [525601];
  assert.match(invalidClientChange(change), /Notifications/);
});

test("search occurrence lookup matches expansion but returns only the first result", () => {
  const rules = ["NONE", "DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY", "YEARLY"];
  for (const recurrence of rules) {
    const start = Date.UTC(2020, 0, 31, 9);
    const record = {
      id: recurrence,
      data: {
        start,
        end: start + 3600000,
        allDay: false,
        recurrence,
        recurrenceUntil: null,
        exceptions: [Date.UTC(2026, 9, 12) / 86400000],
      },
    };
    const expanded = expandOccurrences(record, "2026-10-12", 5 * 366)[0] ?? null;
    assert.deepEqual(nextOccurrence(record, "2026-10-12", 5 * 366), expanded);
  }
  const ended = {
    id: "ended",
    data: {
      start: Date.UTC(2020, 1, 29),
      end: Date.UTC(2020, 1, 29),
      allDay: true,
      recurrence: "YEARLY",
      recurrenceUntil: Date.UTC(2026, 1, 28) / 86400000,
      exceptions: [],
    },
  };
  assert.equal(nextOccurrence(ended, "2025-01-01", 366), null);
});
