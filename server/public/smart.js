// Quick add, ported from domain/SmartEventParser.kt and domain/CalendarMatcher.kt so a sentence
// reads the same on the web as on Android. Dates are local Date objects at midnight; times are
// minutes after midnight.

const DAY_NAMES = [
  "sunday",
  "monday",
  "tuesday",
  "wednesday",
  "thursday",
  "friday",
  "saturday",
];
const MONTH_NAMES = [
  "january",
  "february",
  "march",
  "april",
  "may",
  "june",
  "july",
  "august",
  "september",
  "october",
  "november",
  "december",
];
const weekdays = new Map();
DAY_NAMES.forEach((name, i) => {
  weekdays.set(name, i);
  weekdays.set(name.slice(0, 3), i);
});
const months = new Map();
MONTH_NAMES.forEach((name, i) => {
  months.set(name, i);
  months.set(name.slice(0, 3), i);
});
months.set("sept", 8);
const dayNames = [...weekdays.keys()].join("|");
const monthNames = [...months.keys()].join("|");
const CLOCK = "(?:noon|midnight|\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)";
const regex = (pattern, flags = "i") => new RegExp(pattern, flags);
const matches = (pattern, text) => regex("^(?:" + pattern + ")$").test(text);

const addDays = (d, n) =>
  new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
const sameDay = (a, b) => a && b && a.getTime() === b.getTime();
const daysInMonth = (y, m) => new Date(y, m + 1, 0).getDate();
/** The next date on [weekday], today included when [orSame]. */
function nextWeekday(from, weekday, orSame) {
  let diff = (weekday - from.getDay() + 7) % 7;
  if (diff === 0 && !orSame) diff = 7;
  return addDays(from, diff);
}

export const cleanTitle = (title) =>
  title
    .replace(/\band\s+i\b/gi, "")
    .replace(/\s+/g, " ")
    .replace(/^[ ,.\-–]+|[ ,.\-–]+$/g, "");

/** True when the text reads like a to-do with a deadline, such as "Pay rent by Friday". */
export const looksLikeTask = (input) =>
  regex(
    `\\bby\\s+(?:\\d|noon|midnight|today|tomorrow|next\\b|${dayNames}|${monthNames})`,
  ).test(input);

const problem = (message) => ({ ok: false, message });

/**
 * Returns { ok: true, draft: { title, start, end, allDay, recurrence } } or { ok: false, message }.
 * [defaultDate] is used when the text names a time but no date; relative words like "tomorrow"
 * are always resolved against [today].
 */
export function parse(
  input,
  today,
  { durationMinutes = 60, task = false, defaultDate = today } = {},
) {
  if (!input.trim())
    return problem("Describe an event, including a date or time.");
  if (input.length > 500)
    return problem("Keep the event description under 500 characters.");
  if (
    regex("^(?:please\\s+)?(?:delete|cancel|remove|move|reschedule)\\b").test(
      input.trim(),
    )
  )
    return problem(
      "Create new events or tasks here. Open an existing item to change it.",
    );
  if (!task && regex("\\bby\\s+(?:\\d|noon|midnight)").test(input))
    return problem(
      "Use “at” for an event start time, or create a Smart Task with a due time.",
    );
  let text = input
    .trim()
    .replace(/\s+/g, " ")
    .replace(
      regex("^(?:please\\s+)?(?:add|create|schedule)\\s+(?:an?\\s+)?"),
      "",
    );
  const remove = (m) => {
    text = (text.slice(0, m.index) + text.slice(m.index + m[0].length)).trim();
  };
  const allDayMatch = regex("\\ball[- ]day\\b").exec(text);
  if (allDayMatch) remove(allDayMatch);
  let date = null;
  let recurrence = "NONE";
  const repeat = regex(
    `\\bevery\\s+(weekday|day|week|month|year|${dayNames})s?\\b|\\b(daily|weekly|monthly|yearly)\\b`,
  ).exec(text);
  if (repeat) {
    const rule = (repeat[1] || repeat[2]).toLowerCase();
    recurrence =
      rule === "day" || rule === "daily"
        ? "DAILY"
        : rule === "weekday"
          ? "WEEKDAYS"
          : rule === "month" || rule === "monthly"
            ? "MONTHLY"
            : rule === "year" || rule === "yearly"
              ? "YEARLY"
              : "WEEKLY";
    if (weekdays.has(rule)) date = nextWeekday(today, weekdays.get(rule), true);
    if (recurrence === "WEEKDAYS") {
      date = today;
      while (date.getDay() === 0 || date.getDay() === 6)
        date = addDays(date, 1);
    }
    remove(repeat);
  }
  let endDate = null;
  // Consume both endpoints before the single-date ambiguity check. Bare ending days inherit the
  // starting month; named months and weekdays resolve against the start.
  const dateAtom = `(?:\\d{4}-\\d{2}-\\d{2}|(?:${monthNames})\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?|(?:next\\s+)?(?:${dayNames})|the day after tomorrow|day after tomorrow|tomorrow|today|\\d{1,2}(?:st|nd|rd|th))`;
  let dateRange = regex(
    `\\b(?:from\\s+|on\\s+)?(${dateAtom})(?:\\s+(?:at\\s+)?(${CLOCK}))?\\s*(?:to|[-–])\\s*(${dateAtom}|\\d{1,2})(?![\\w:])(?:\\s+(?:at\\s+)?(${CLOCK})(?![\\w:]))?`,
  ).exec(text);
  if (
    dateRange &&
    matches("\\d{1,2}", dateRange[3]) &&
    !matches(`(?:${monthNames})\\s+.*|\\d{1,2}(?:st|nd|rd|th)`, dateRange[1])
  )
    dateRange = null;
  if (dateRange) {
    if (task) return problem("Use one due date for a task.");
    const first = dateRange[1].toLowerCase();
    const last = dateRange[3].toLowerCase();
    let parsedStart, parsedEnd;
    try {
      parsedStart = resolveDate(first, today);
      if (matches("\\d{1,2}(?:st|nd|rd|th)?", last)) {
        const day = parseInt(last, 10);
        if (
          day < 1 ||
          day > daysInMonth(parsedStart.getFullYear(), parsedStart.getMonth())
        )
          throw new RangeError();
        parsedEnd = new Date(
          parsedStart.getFullYear(),
          parsedStart.getMonth(),
          day,
        );
      } else parsedEnd = resolveDate(last, parsedStart, true, today);
    } catch {
      return problem("Check the date range. Try “October 23 to October 25”.");
    }
    if (parsedEnd < parsedStart)
      return problem("The end date must be on or after the start date.");
    if (date && !sameDay(date, parsedStart))
      return problem("Use one start date for this event.");
    date = parsedStart;
    endDate = parsedEnd;
    const firstTime = dateRange[2] || "";
    const lastTime = dateRange[4] || "";
    if (!!firstTime !== !!lastTime)
      return problem(
        "Add both a start time and an end time for the date range.",
      );
    text = (
      text.slice(0, dateRange.index) +
      (firstTime ? ` from ${firstTime} to ${lastTime} ` : "") +
      text.slice(dateRange.index + dateRange[0].length)
    ).trim();
  }
  const datePrefix = task ? "(?:on|at|by)" : "(?:on|at)";
  const datePattern = `\\b(?:${datePrefix}\\s+)?(?:the\\s+)?(the day after tomorrow|day after tomorrow|tomorrow|today|in\\s+\\d+\\s+(?:days?|weeks?)|\\d{4}-\\d{2}-\\d{2}|(?:next\\s+)?(?:${dayNames})|(?:${monthNames})\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?|\\d{1,2}(?:st|nd|rd|th))\\b`;
  // A concrete date refines casual relative wording, e.g. "tomorrow on the 23rd".
  const dateMatches = [...text.matchAll(regex(datePattern, "gi"))];
  const explicitDates = dateMatches.filter((m) =>
    regex(`^(?:\\d|${monthNames})`).test(m[1]),
  );
  if (!endDate && explicitDates.length === 1)
    dateMatches
      .filter(
        (m) =>
          m !== explicitDates[0] &&
          [
            "today",
            "tomorrow",
            "day after tomorrow",
            "the day after tomorrow",
          ].includes(m[1].toLowerCase()),
      )
      .sort((a, b) => b.index - a.index)
      .forEach(remove);
  const dateMatch = regex(datePattern).exec(text);
  if (dateMatch) {
    if (endDate) return problem("Use one start date for this event.");
    const raw = dateMatch[1].toLowerCase();
    if (raw.startsWith("in ") && Number(raw.split(" ")[1]) > 36500)
      return problem("That date is too far away. Use a specific date.");
    if (matches("\\d{1,2}(?:st|nd|rd|th)", raw)) {
      const day = parseInt(raw, 10);
      if (day < 1 || day > 31)
        return problem("Check the day. Use a number from 1 to 31.");
    }
    let parsed;
    try {
      parsed = resolveDate(raw, today);
    } catch {
      return problem("Check the date. Try “October 20” or “2026-10-20”.");
    }
    if (date && !sameDay(date, parsed))
      return problem("Use one start date for this event.");
    date = parsed;
    remove(dateMatch);
    if (regex(datePattern).test(text))
      return problem("Use one start date for this event.");
  }
  if (recurrence !== "NONE" && !date) date = today;
  const durationMatch = regex(
    "\\bfor\\s+(\\d+)\\s*(minutes?|mins?|hours?|hrs?)\\b",
  ).exec(text);
  let duration = Math.min(1440, Math.max(1, durationMinutes));
  if (durationMatch && task)
    return problem("Tasks have a due time. Try “by 9pm today”.");
  if (durationMatch) {
    const amount = Number(durationMatch[1]);
    duration =
      amount * (durationMatch[2].toLowerCase().startsWith("h") ? 60 : 1);
    if (amount < 1 || amount > 10080 || duration < 1 || duration > 10080)
      return problem("Use a duration between 1 minute and 7 days.");
    remove(durationMatch);
  }
  const range = regex(
    `(?<![\\w:])(?:from\\s+|at\\s+)?(${CLOCK})\\s*(?:to|[-–])\\s*(${CLOCK})(?![\\w:])`,
  ).exec(text);
  let startTime = null,
    endTime = null;
  if (range && task)
    return problem("Use one due time for a task, for example “by 9pm today”.");
  if (range) {
    if (durationMatch) return problem("Use either an end time or a duration.");
    const first = range[1].trim();
    const last = range[2].trim();
    const suffix = /(am|pm)$/i.exec(last)?.[0].toLowerCase() ?? null;
    startTime = clock(first, suffix) ?? likelyTime(bareHour(first));
    if (startTime == null)
      return problem("Check the start time, for example “3 to 5pm”.");
    // A bare end hour is the next one after the start: 9 to 5 means 9am to 5pm.
    endTime = clock(last) ?? nextAfter(bareHour(last), startTime);
    if (endTime == null)
      return problem("Check the end time, or use 24-hour time like “17:00”.");
    const sameDate = !endDate || sameDay(endDate, date);
    // With no am or pm anywhere, prefer the reading that ends the same day: 6 to 2 is a day shift.
    const firstHour = bareHour(first);
    const lastHour = bareHour(last);
    if (
      sameDate &&
      !suffix &&
      firstHour >= 1 &&
      firstHour <= 12 &&
      lastHour >= 1 &&
      lastHour <= 12 &&
      endTime < startTime
    ) {
      const flipped = (startTime + 720) % 1440;
      const flippedEnd = nextAfter(lastHour, flipped);
      if (flippedEnd > flipped) {
        startTime = flipped;
        endTime = flippedEnd;
      }
    }
    // Shared suffix: 11 to 1pm means 11am to 1pm, rather than an overnight range.
    const firstNumber = parseInt(first.split(":")[0].trim(), 10);
    if (
      sameDate &&
      suffix &&
      !/am|pm|noon|midnight/i.test(first) &&
      firstNumber >= 1 &&
      firstNumber <= 12 &&
      endTime <= startTime
    )
      startTime = startTime >= 720 ? startTime - 720 : startTime + 720;
    if (sameDate && endTime === startTime)
      return problem(
        "The start and end times are the same. Add an end time or duration.",
      );
    remove(range);
  } else {
    const timePrefix = task ? "(?:at|by)" : "at";
    const single = regex(
      `\\b${timePrefix}\\s+(${CLOCK})(?![\\w:])|(?<![\\w:])((?:\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)|\\d{1,2}:\\d{2}|noon|midnight))(?![\\w:])`,
    ).exec(text);
    if (single) {
      const raw = single[1] || single[2];
      startTime = clock(raw) ?? likelyTime(bareHour(raw));
      if (startTime == null)
        return problem("Check the time, or use 24-hour time like “15:00”.");
      remove(single);
    }
  }
  if (
    regex(
      "\\b(?:today|tomorrow|every|daily|weekly|monthly|yearly|next|in\\s+\\d+|for\\s+\\d+|at\\s+\\d|from\\s+\\d|am|pm|noon|midnight)\\b|\\d+:\\d+|\\d+\\s*(?:am|pm)\\b|\\b\\d{1,2}[/-]\\d{1,2}\\b",
    ).test(text)
  )
    return problem(
      task
        ? "I couldn't read all the due-date details. Try “Call doctor by 9pm today”."
        : "I couldn't read all the date or time details. Try “Work tomorrow from 3 to 5pm”.",
    );
  if (endDate && startTime != null && endTime == null)
    return problem("Add both a start time and an end time for the date range.");
  if (endDate && durationMatch)
    return problem("Use either an end date or a duration.");
  if (allDayMatch && startTime != null)
    return problem("Use either all-day or a start time.");
  const title = cleanTitle(text);
  if (!title)
    return problem("Add an event title, for example “Work tomorrow at 3pm”.");
  if (!date && startTime == null)
    return problem("Add a date or time, for example “tomorrow” or “at 3pm”.");
  if (durationMatch && startTime == null)
    return problem("Add a start time for the duration.");
  const day = date ?? defaultDate;
  const at = (d, minutes) =>
    new Date(
      d.getFullYear(),
      d.getMonth(),
      d.getDate(),
      Math.floor(minutes / 60),
      minutes % 60,
    );
  const start = at(day, startTime ?? 0);
  let end;
  if (startTime == null) end = addDays(endDate ?? day, 1);
  else if (task) end = start;
  else if (endTime == null) end = new Date(start.getTime() + duration * 60000);
  else if (endDate) end = at(endDate, endTime);
  else {
    end = at(day, endTime);
    if (end < start) end = at(addDays(day, 1), endTime);
  }
  if (end <= start && !task)
    return problem("The end must be after the start of the event.");
  return {
    ok: true,
    draft: { title, start, end, allDay: startTime == null, recurrence },
  };
}

/** Resolves range ends relative to their start, including week and year rollover. */
function resolveDate(
  raw,
  reference,
  rangeEnd = false,
  relativeDay = reference,
) {
  if (raw.endsWith("day after tomorrow")) return addDays(relativeDay, 2);
  if (raw === "tomorrow") return addDays(relativeDay, 1);
  if (raw === "today") return relativeDay;
  if (raw.startsWith("in ")) {
    const amount = Number(raw.split(" ")[1]);
    if (amount > 36500) throw new RangeError();
    return addDays(reference, raw.includes("week") ? amount * 7 : amount);
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) {
    const [y, m, d] = raw.split("-").map(Number);
    if (m < 1 || m > 12 || d < 1 || d > daysInMonth(y, m - 1))
      throw new RangeError();
    return new Date(y, m - 1, d);
  }
  if (/^\d{1,2}(?:st|nd|rd|th)$/.test(raw)) {
    const day = parseInt(raw, 10);
    if (day < 1 || day > 31) throw new RangeError();
    let y = reference.getFullYear(),
      m = reference.getMonth();
    while (day > daysInMonth(y, m) || new Date(y, m, day) < reference) {
      m++;
      if (m > 11) {
        m = 0;
        y++;
      }
    }
    return new Date(y, m, day);
  }
  const bare = raw.replace(/^next /, "");
  if (weekdays.has(bare))
    return nextWeekday(
      reference,
      weekdays.get(bare),
      rangeEnd && !raw.startsWith("next "),
    );
  const parts = raw
    .replace(",", "")
    .replace(/(\d)(st|nd|rd|th)\b/g, "$1")
    .split(" ");
  const month = months.get(parts[0]);
  if (month == null) throw new RangeError();
  const day = Number(parts[1]);
  let year = parts[2] ? Number(parts[2]) : reference.getFullYear();
  if (parts.length > 2 || (rangeEnd && month === reference.getMonth())) {
    if (day < 1 || day > daysInMonth(year, month)) throw new RangeError();
    return new Date(year, month, day);
  }
  if (day < 1 || day > (month === 1 ? 29 : daysInMonth(2001, month)))
    throw new RangeError();
  while (
    day > daysInMonth(year, month) ||
    new Date(year, month, day) < reference
  )
    year++;
  return new Date(year, month, day);
}

function clock(raw, inheritedSuffix = null) {
  const text = raw.toLowerCase().replace(/ /g, "");
  if (text === "noon") return 720;
  if (text === "midnight") return 0;
  const m = /^(\d{1,2})(?::(\d{2}))?(am|pm)?$/.exec(text);
  if (!m) return null;
  let hour = Number(m[1]);
  const minute = Number(m[2] ?? 0);
  const suffix =
    m[3] || (hour >= 1 && hour <= 12 ? (inheritedSuffix ?? "") : "");
  if (minute > 59) return null;
  if (suffix) {
    if (hour < 1 || hour > 12) return null;
    hour = (hour % 12) + (suffix === "pm" ? 12 : 0);
  } else if (m[2] == null || hour > 23) return null;
  return hour * 60 + minute;
}

const bareHour = (raw) => {
  const t = raw.trim();
  if (!/^\d+$/.test(t)) return null;
  const n = Number(t);
  return n <= 23 ? n : null;
};

/** 7 to 11 are mornings, 12 is noon, and 1 to 6 are afternoons; past 12 is already 24-hour. */
const likelyTime = (hour) =>
  hour == null ? null : hour >= 1 && hour <= 6 ? (hour + 12) * 60 : hour * 60;

/** The first time after [start] with this hour on the clock face, wrapping past midnight if needed. */
function nextAfter(hour, start) {
  if (hour == null) return null;
  if (hour < 1 || hour > 12) return hour * 60;
  const am = (hour % 12) * 60,
    pm = am + 720;
  return am > start ? am : pm > start ? pm : am;
}

/* ---------- CalendarMatcher ---------- */

const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
/** Whole words only, ignoring case and allowing a plural: "shift" matches "Shifts" but not "shifty". */
function containsWord(text, word) {
  if (!word.trim()) return false;
  return new RegExp(
    `(?<![\\p{L}\\p{N}])${escape(word.trim())}(?:e?s)?(?![\\p{L}\\p{N}])`,
    "iu",
  ).test(text);
}

/**
 * Picks a calendar for a quick add title: a keyword in the title, then the calendar the same title
 * went into last time, then a calendar named in the title. [calendars] are { id, name, keywords };
 * [history] is events newest first. Returns { calendar, reason } or null.
 */
export function matchCalendar(title, calendars, history) {
  if (!title.trim()) return null;
  let best = null;
  for (const c of calendars)
    for (const keyword of c.keywords
      .split(",")
      .map((k) => k.trim())
      .filter(Boolean))
      if (
        containsWord(title, keyword) &&
        (!best || keyword.length > best.keyword.length)
      )
        best = { c, keyword };
  if (best) return { calendar: best.c, reason: `Matched “${best.keyword}”` };
  for (const e of history)
    if (e.title.toLowerCase() === title.trim().toLowerCase()) {
      const c = calendars.find((x) => x.id === e.calendarId);
      if (c) return { calendar: c, reason: "Same as last time" };
    }
  const named = calendars
    .filter((c) => containsWord(title, c.name))
    .sort((a, b) => b.name.length - a.name.length)[0];
  return named ? { calendar: named, reason: "Named in the title" } : null;
}
