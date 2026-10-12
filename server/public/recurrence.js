const DAY = 86400000;
const parts = (date, utc) =>
  utc
    ? [
        date.getUTCFullYear(),
        date.getUTCMonth(),
        date.getUTCDate(),
        date.getUTCHours(),
        date.getUTCMinutes(),
        date.getUTCSeconds(),
        date.getUTCMilliseconds(),
      ]
    : [
        date.getFullYear(),
        date.getMonth(),
        date.getDate(),
        date.getHours(),
        date.getMinutes(),
        date.getSeconds(),
        date.getMilliseconds(),
      ];
const epochDay = ([year, month, day]) => Date.UTC(year, month, day) / DAY;

/** Same floating-date recurrence rules as Android, including skipped 31sts and leap days. */
export function expandOccurrences(record, from, days = 60) {
  const d = record.data;
  const utc = d.allDay;
  const base = parts(new Date(d.start), utc);
  const end = parts(new Date(d.end), utc);
  const baseDay = epochDay(base);
  const firstDay = epochDay(parts(new Date(from + "T12:00"), false));
  const lastDay = firstDay + days;
  const duration = Date.UTC(...end) - Date.UTC(...base);
  const exceptions = new Set(d.exceptions);
  const result = [];
  const begin = Math.max(
    baseDay,
    firstDay - Math.ceil(Math.max(0, duration) / DAY),
  );
  const until =
    d.recurrenceUntil == null ? lastDay : Math.min(lastDay, d.recurrenceUntil);
  const makeOccurrence = (day) => {
    const candidate = new Date(day * DAY);
    const p = [
      candidate.getUTCFullYear(),
      candidate.getUTCMonth(),
      candidate.getUTCDate(),
      ...base.slice(3),
    ];
    const occurrence = utc ? Date.UTC(...p) : new Date(...p).getTime();
    const finish = new Date(Date.UTC(...p) + duration);
    const occurrenceEnd = utc
      ? finish.getTime()
      : new Date(...parts(finish, true)).getTime();
    const threshold = utc
      ? firstDay * DAY
      : new Date(from + "T00:00").getTime();
    const overlaps =
      occurrenceEnd === occurrence
        ? occurrence >= threshold
        : occurrenceEnd > threshold;
    if (overlaps && day <= lastDay)
      result.push({ ...record, occurrence, occurrenceEnd, day });
  };
  if (d.recurrence === "NONE") {
    makeOccurrence(baseDay);
    return result;
  }
  for (let day = begin; day <= until; day++) {
    if (exceptions.has(day)) continue;
    const date = new Date(day * DAY);
    const weekday = date.getUTCDay();
    const match =
      d.recurrence === "DAILY" ||
      (d.recurrence === "WEEKDAYS" && weekday !== 0 && weekday !== 6) ||
      (d.recurrence === "WEEKLY" && (day - baseDay) % 7 === 0) ||
      (d.recurrence === "MONTHLY" && date.getUTCDate() === base[2]) ||
      (d.recurrence === "YEARLY" &&
        date.getUTCMonth() === base[1] &&
        date.getUTCDate() === base[2]);
    if (match) makeOccurrence(day);
  }
  return result;
}
