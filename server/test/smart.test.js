// Mirrors app/src/test/.../SmartEventParserTest.kt and SmartTaskParserTest.kt.
import { test } from "node:test";
import assert from "node:assert/strict";
import { parse, looksLikeTask, cleanTitle } from "../public/smart.js";
const today = new Date(2026, 9, 8);
const iso = (d) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}T${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
const event = (text, options) => {
  const r = parse(text, today, options);
  assert.ok(r.ok, text + ": " + r.message);
  return r.draft;
};
const CHECKS = [
  [
    "Trip Oct 23 at 8pm to Oct 25 at 10am",
    "Trip",
    "2026-10-23T20:00",
    "2026-10-25T10:00",
  ],
  [
    "Trip Oct 23 10am to Oct 25 10am",
    "Trip",
    "2026-10-23T10:00",
    "2026-10-25T10:00",
  ],
  [
    "Trip Oct 23 to Oct 25 from 8pm to 10am",
    "Trip",
    "2026-10-23T20:00",
    "2026-10-25T10:00",
  ],
  [
    "Trip from Fri 8pm to Sun 10am",
    "Trip",
    "2026-10-09T20:00",
    "2026-10-11T10:00",
  ],
  [
    "Holiday Dec 30 to Jan 2",
    "Holiday",
    "2026-12-30T00:00",
    "2027-01-03T00:00",
  ],
  [
    "Holiday 2026-12-30 to 2027-01-02",
    "Holiday",
    "2026-12-30T00:00",
    "2027-01-03T00:00",
  ],
  ["Trip Oct 23 to Oct 23", "Trip", "2026-10-23T00:00", "2026-10-24T00:00"],
  [
    "Trip Oct 23 10am to Oct 23 11am",
    "Trip",
    "2026-10-23T10:00",
    "2026-10-23T11:00",
  ],
  [
    "Trip Oct 23 to 25 all day yearly",
    "Trip",
    "2026-10-23T00:00",
    "2026-10-26T00:00",
  ],
  ["Trip Friday to Monday", "Trip", "2026-10-09T00:00", "2026-10-13T00:00"],
  [
    "Work from 3 to 5pm tomorrow",
    "Work",
    "2026-10-09T15:00",
    "2026-10-09T17:00",
  ],
  [
    "WORK TOMORROW FROM 3 TO 5PM",
    "WORK",
    "2026-10-09T15:00",
    "2026-10-09T17:00",
  ],
  [
    "Dentist tomorrow at 10am",
    "Dentist",
    "2026-10-09T10:00",
    "2026-10-09T11:00",
  ],
  ["Lunch today at noon", "Lunch", "2026-10-08T12:00", "2026-10-08T13:00"],
  [
    "Call Alex on 2026-10-12 at 14:30",
    "Call Alex",
    "2026-10-12T14:30",
    "2026-10-12T15:30",
  ],
  [
    "Coffee tomorrow at 9:30am",
    "Coffee",
    "2026-10-09T09:30",
    "2026-10-09T10:30",
  ],
  ["Work tomorrow 3-5pm", "Work", "2026-10-09T15:00", "2026-10-09T17:00"],
  [
    "Lunch tomorrow from 11 to 1pm",
    "Lunch",
    "2026-10-09T11:00",
    "2026-10-09T13:00",
  ],
  [
    "Lunch tomorrow from 11:30 to 1pm",
    "Lunch",
    "2026-10-09T11:30",
    "2026-10-09T13:00",
  ],
  [
    "Shift tomorrow from 11 to 1am",
    "Shift",
    "2026-10-09T23:00",
    "2026-10-10T01:00",
  ],
  [
    "Night shift tomorrow from 10pm to 2am",
    "Night shift",
    "2026-10-09T22:00",
    "2026-10-10T02:00",
  ],
  [
    "Shift tomorrow from 14:00 to 3pm",
    "Shift",
    "2026-10-09T14:00",
    "2026-10-09T15:00",
  ],
  [
    "Haircut next Friday at 4pm",
    "Haircut",
    "2026-10-09T16:00",
    "2026-10-09T17:00",
  ],
  ["Soccer on Monday at 6pm", "Soccer", "2026-10-12T18:00", "2026-10-12T19:00"],
  [
    "Inspection in 3 days at 11am",
    "Inspection",
    "2026-10-11T11:00",
    "2026-10-11T12:00",
  ],
  [
    "Visit mom the day after tomorrow at noon",
    "Visit mom",
    "2026-10-10T12:00",
    "2026-10-10T13:00",
  ],
  [
    "Meeting on October 20 at 2pm",
    "Meeting",
    "2026-10-20T14:00",
    "2026-10-20T15:00",
  ],
  [
    "Walk tomorrow at 4pm for 30 minutes",
    "Walk",
    "2026-10-09T16:00",
    "2026-10-09T16:30",
  ],
  [
    "Workshop tomorrow at 10am for 2 hours",
    "Workshop",
    "2026-10-09T10:00",
    "2026-10-09T12:00",
  ],
  [
    "Maintenance tomorrow at midnight for 90 minutes",
    "Maintenance",
    "2026-10-09T00:00",
    "2026-10-09T01:30",
  ],
  ["Work at Oct 17th at 3pm", "Work", "2026-10-17T15:00", "2026-10-17T16:00"],
  ["Work on Sep 17th", "Work", "2027-09-17T00:00", "2027-09-18T00:00"],
  ["Work on Sept 17th 2027", "Work", "2027-09-17T00:00", "2027-09-18T00:00"],
  [
    "Change engine on 6th",
    "Change engine",
    "2026-11-06T00:00",
    "2026-11-07T00:00",
  ],
  ["Work on 17th at 3pm", "Work", "2026-10-17T15:00", "2026-10-17T16:00"],
  ["Work on 8th", "Work", "2026-10-08T00:00", "2026-10-09T00:00"],
  [
    "Lunch with Sam tomorrow on the 23rd",
    "Lunch with Sam",
    "2026-10-23T00:00",
    "2026-10-24T00:00",
  ],
  [
    "Lunch with Sam on the 23rd tomorrow at noon",
    "Lunch with Sam",
    "2026-10-23T12:00",
    "2026-10-23T13:00",
  ],
  [
    "Lunch tomorrow on October 23",
    "Lunch",
    "2026-10-23T00:00",
    "2026-10-24T00:00",
  ],
  [
    "Movie Night with Sam, Dario, Altman and I on the 10th at 7pm",
    "Movie Night with Sam, Dario, Altman",
    "2026-10-10T19:00",
    "2026-10-10T20:00",
  ],
  [
    "Work from 9 to 5 on Friday",
    "Work",
    "2026-10-09T09:00",
    "2026-10-09T17:00",
  ],
  ["Work tomorrow from 3 to 5", "Work", "2026-10-09T15:00", "2026-10-09T17:00"],
  [
    "Brunch tomorrow from 10 to 2",
    "Brunch",
    "2026-10-09T10:00",
    "2026-10-09T14:00",
  ],
  [
    "Dinner tomorrow from 6 to 9",
    "Dinner",
    "2026-10-09T18:00",
    "2026-10-09T21:00",
  ],
  [
    "Shift tomorrow from 9am to 5",
    "Shift",
    "2026-10-09T09:00",
    "2026-10-09T17:00",
  ],
  [
    "Party tomorrow from 10pm to 2",
    "Party",
    "2026-10-09T22:00",
    "2026-10-10T02:00",
  ],
  [
    "Produce shift tomorrow 6 to 2",
    "Produce shift",
    "2026-10-09T06:00",
    "2026-10-09T14:00",
  ],
  [
    "Shift tomorrow from 9 to 17",
    "Shift",
    "2026-10-09T09:00",
    "2026-10-09T17:00",
  ],
  [
    "Meet Jordan tomorrow at 3",
    "Meet Jordan",
    "2026-10-09T15:00",
    "2026-10-09T16:00",
  ],
  [
    "Breakfast tomorrow at 8",
    "Breakfast",
    "2026-10-09T08:00",
    "2026-10-09T09:00",
  ],
  ["Lunch tomorrow at 12", "Lunch", "2026-10-09T12:00", "2026-10-09T13:00"],
];
const CLARIFY = [
  "Trip Oct 25 to 23",
  "Trip Oct 25 to Oct 23",
  "Trip Feb 28 to 30",
  "Trip 2026-10-25 to 2026-10-23",
  "Trip Oct 23 8pm to Oct 25",
  "Trip Oct 23 to Oct 25 10am",
  "Trip Oct 23 to Oct 25 at 10am",
  "Trip Oct 23 to Oct 25 for 2 hours",
  "Trip Oct 23 to Oct 25 tomorrow",
  "Trip Oct 23 10am to Oct 23 10am",
  "Trip Oct 23 8pm to Oct 23 10am",
  "Trip Friday to today",
  "Trip Oct 23 to Oct 25 to Oct 27",
  "Trip Oct 23 to Oct 25 Oct 23",
  "",
  "Work",
  "Dinner sometime next week",
  "Meeting tomorrow at 3pm and at 4pm",
  "Delete my meeting tomorrow",
  "Work tomorrow at 3pm all-day",
  "Work tomorrow at 25pm",
  "Work tomorrow at 10:99",
  "Work February 30 at 3pm",
  "Work 2026-02-29 at 3pm",
  "Work tomorrow today at 3pm",
  "Work tomorrow at 3pm for 0 minutes",
  "Work tomorrow for 2 hours",
  "Work tomorrow from 3pm to 5pm for 2 hours",
  "Work tomorrow from 3pm to 3pm",
  "tomorrow at 3pm",
  "Work 10/20 at 3pm",
  "Work every Monday tomorrow at 3pm",
];
test("events match the Android parser", () => {
  for (const [text, title, start, end] of CHECKS) {
    const d = event(text);
    assert.deepEqual(
      [d.title, iso(d.start), iso(d.end)],
      [title, start, end],
      text,
    );
  }
  for (const form of [
    "Oct 23 to Oct 25",
    "Oct 23 - Oct 25",
    "Oct 23 to 25",
    "Oct 23-25",
    "Oct 23–25",
    "from Friday to Sunday",
    "Fri to Sun",
  ]) {
    const r = parse("Weekend trip to Banff " + form, new Date(2026, 9, 11));
    assert.ok(r.ok, form);
    const first = form.includes("Oct") ? 23 : 16;
    assert.equal(r.draft.title, "Weekend trip to Banff");
    assert.ok(r.draft.allDay);
    assert.equal(iso(r.draft.start), `2026-10-${first}T00:00`);
    assert.equal(iso(r.draft.end), `2026-10-${first + 3}T00:00`);
  }
  assert.equal(event("Trip Oct 23 to 25 yearly").recurrence, "YEARLY");
  assert.equal(
    event("Work at 3pm", { durationMinutes: 45 }).end -
      event("Work at 3pm", { durationMinutes: 45 }).start,
    45 * 60000,
  );
  assert.equal(iso(event("Holiday January 2").start), "2027-01-02T00:00");
  assert.equal(iso(event("Leap day February 29").start), "2028-02-29T00:00");
  assert.equal(event("Birthday tomorrow all day").title, "Birthday");
  assert.equal(
    iso(parse("Work on 31st", new Date(2026, 3, 10)).draft.start),
    "2026-05-31T00:00",
  );
  assert.equal(event("Standup every weekday at 9am").recurrence, "WEEKDAYS");
  const guitar = event("Guitar every Tuesday at 7pm");
  assert.equal(guitar.recurrence, "WEEKLY");
  assert.equal(iso(guitar.start), "2026-10-13T19:00");
  assert.equal(event("Stretch every day at 6am").recurrence, "DAILY");
  assert.equal(
    iso(event("Reading every Thursday from 8pm to 9pm").start),
    "2026-10-08T20:00",
  );
  assert.equal(
    iso(
      parse("Standup every weekday at 9am", new Date(2026, 9, 10)).draft.start,
    ),
    "2026-10-12T09:00",
  );
  assert.equal(
    event("Please add a team meeting tomorrow from 2pm to 3pm").title,
    "team meeting",
  );
  assert.equal(cleanTitle("Dinner with Sam AND I"), "Dinner with Sam");
  assert.equal(cleanTitle("Sam and I go bowling"), "Sam go bowling");
  assert.equal(
    cleanTitle("Dinner with Sam and Irene"),
    "Dinner with Sam and Irene",
  );
});

test("unclear input asks for details instead of guessing", () => {
  for (const text of [
    ...CLARIFY,
    "Work at Feb 30th",
    "Work on 32nd",
    "Lunch on the 23rd on the 24th",
  ])
    assert.equal(parse(text, today).ok, false, text);
});

test("tasks match the Android parser", () => {
  const task = (text, options = {}) => {
    const r = parse(text, today, { task: true, ...options });
    assert.ok(r.ok, text + ": " + r.message);
    return r.draft;
  };
  let t = task("Schedule doctors appointment by 9pm today");
  assert.deepEqual(
    [t.title, iso(t.start), t.end.getTime() === t.start.getTime(), t.allDay],
    ["doctors appointment", "2026-10-08T21:00", true, false],
  );
  t = task("Buy groceries tomorrow");
  assert.deepEqual(
    [t.title, t.allDay, iso(t.start), iso(t.end)],
    ["Buy groceries", true, "2026-10-09T00:00", "2026-10-10T00:00"],
  );
  assert.equal(task("Call doctor by Friday").title, "Call doctor");
  assert.equal(iso(task("Call doctor by Friday").start), "2026-10-09T00:00");
  t = task("Take vitamins every day at 8am");
  assert.deepEqual(
    [t.recurrence, t.title, t.start.getHours()],
    ["DAILY", "Take vitamins", 8],
  );
  assert.equal(
    iso(task("Submit report by 21:30 tomorrow").start),
    "2026-10-09T21:30",
  );
  assert.equal(task("Call doctor today by noon").start.getHours(), 12);
  assert.equal(
    iso(task("Submit report by 2026-10-20").start),
    "2026-10-20T00:00",
  );
  assert.equal(iso(task("Pay rent by Oct 17th").start), "2026-10-17T00:00");
  for (const text of [
    "Call doctor",
    "Call doctor tomorrow from 3 to 5pm",
    "Call doctor tomorrow at 3pm for 30 minutes",
    "Call doctor tomorrow at 25pm",
    "Call doctor tomorrow at 3pm and by 4pm",
  ])
    assert.equal(parse(text, today, { task: true }).ok, false, text);
  assert.equal(parse("Call doctor by 9pm today", today).ok, false);
  for (const text of [
    "Pay rent Oct 23 to Oct 25",
    "Pay rent Oct 23-25",
    "Pay rent Fri to Sun",
    "Pay rent Friday 8pm to Sunday 10am",
  ])
    assert.equal(
      parse(text, today, { task: true }).message,
      "Use one due date for a task.",
      text,
    );
  for (const text of [
    "Pay rent by Friday",
    "Call doctor by 9pm today",
    "Submit report by October 20",
    "Renew passport by next Monday",
    "Pay rent by Thursday",
    "Pay rent by Oct 17th",
  ])
    assert.ok(looksLikeTask(text), text);
  for (const text of [
    "Lunch with Sam tomorrow at noon",
    "Drop by the office at 3pm",
    "Stand by me tomorrow",
  ])
    assert.ok(!looksLikeTask(text), text);
  const open = new Date(2026, 9, 15);
  assert.equal(
    iso(task("Call doctor by 5pm", { defaultDate: open }).start),
    "2026-10-15T17:00",
  );
  assert.equal(
    iso(task("Call doctor by 5pm tomorrow", { defaultDate: open }).start),
    "2026-10-09T17:00",
  );
  assert.equal(iso(task("Call doctor by 9 today").start), "2026-10-08T09:00");
  assert.equal(iso(task("Pay rent by 5 tomorrow").start), "2026-10-09T17:00");
});
