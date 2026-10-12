import { test } from "node:test";
import assert from "node:assert/strict";
import { expandOccurrences } from "../public/recurrence.js";
const record = (start, rule, end = start + 3600000, extra = {}) => ({
  kind: "event",
  id: "event",
  data: {
    start,
    end,
    allDay: true,
    recurrence: rule,
    recurrenceUntil: null,
    exceptions: [],
    ...extra,
  },
});
test("monthly repeats skip months without the 31st", () => {
  const list = expandOccurrences(
    record(Date.UTC(2026, 0, 31), "MONTHLY", Date.UTC(2026, 1, 1)),
    "2026-02-01",
    90,
  );
  assert.deepEqual(
    list.map((r) => new Date(r.occurrence).toISOString().slice(0, 10)),
    ["2026-03-31"],
  );
});
test("yearly leap-day repeats skip non-leap years", () => {
  const item = record(Date.UTC(2024, 1, 29), "YEARLY", Date.UTC(2024, 2, 1));
  assert.equal(expandOccurrences(item, "2026-02-01").length, 0);
  assert.equal(
    expandOccurrences(item, "2028-02-01")[0].occurrence,
    Date.UTC(2028, 1, 29),
  );
});
test("old daily series jump to the visible window and apply exception/end dates", () => {
  const until = Date.UTC(2026, 9, 13) / 86400000;
  const item = record(Date.UTC(1990, 0, 1), "DAILY", Date.UTC(1990, 0, 2), {
    recurrenceUntil: until,
    exceptions: [until - 1],
  });
  assert.deepEqual(
    expandOccurrences(item, "2026-10-11").map((r) => r.day),
    [until - 2, until],
  );
});
test("exclusive all-day end does not show yesterday; timed tasks at midnight do show", () => {
  assert.equal(
    expandOccurrences(
      record(Date.UTC(2026, 9, 10), "NONE", Date.UTC(2026, 9, 11)),
      "2026-10-11",
    ).length,
    0,
  );
  const now = new Date(2026, 9, 11).getTime();
  assert.equal(
    expandOccurrences(record(now, "NONE", now, { allDay: false }), "2026-10-11")
      .length,
    1,
  );
});
test("timed recurrence preserves local wall time across daylight saving", () => {
  const prior = process.env.TZ;
  process.env.TZ = "America/New_York";
  try {
    const start = new Date(2026, 2, 7, 9).getTime();
    const rows = expandOccurrences(
      record(start, "DAILY", new Date(2026, 2, 7, 10).getTime(), {
        allDay: false,
      }),
      "2026-03-07",
      2,
    );
    assert(rows.every((r) => new Date(r.occurrence).getHours() === 9));
    assert.equal(rows[1].occurrence - rows[0].occurrence, 23 * 3600000);
    assert(rows.every((r) => new Date(r.occurrenceEnd).getHours() === 10));
  } finally {
    if (prior === undefined) delete process.env.TZ;
    else process.env.TZ = prior;
  }
});
