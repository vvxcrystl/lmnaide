const DAY = 86400000;

export function calendarDate(value, allDay = false) {
  const iso = new Date(value).toISOString();
  return allDay
    ? iso.slice(0, 10).replaceAll("-", "")
    : iso.replaceAll(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
}

export function recurrenceRule(event) {
  const rule = {
    DAILY: "FREQ=DAILY",
    WEEKDAYS: "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
    WEEKLY: "FREQ=WEEKLY",
    MONTHLY: "FREQ=MONTHLY",
    YEARLY: "FREQ=YEARLY",
  }[event.recurrence];
  if (!rule) return null;
  if (event.recurrenceUntil == null) return rule;
  // The stored end day is inclusive. Timed UNTIL values must be UTC timestamps.
  const until = event.allDay
    ? calendarDate(event.recurrenceUntil * DAY, true)
    : calendarDate((event.recurrenceUntil + 1) * DAY - 1000);
  return rule + ";UNTIL=" + until;
}

function exportEnd(event) {
  return event.allDay ? Math.max(event.end, event.start + DAY) : event.end;
}

export function googleCalendarUrl(event) {
  const url = new URL("https://calendar.google.com/calendar/render");
  url.search = new URLSearchParams({
    action: "TEMPLATE",
    text: event.title,
    dates: calendarDate(event.start, event.allDay) + "/" +
      calendarDate(exportEnd(event), event.allDay),
    details: event.description,
    location: event.location,
  });
  const rule = recurrenceRule(event);
  if (rule) url.searchParams.set("recur", "RRULE:" + rule);
  return url.href;
}

export function lmnaideAppUrl(event, fallbackUrl) {
  const params = new URLSearchParams();
  for (const key of ["title", "description", "location", "start", "end", "allDay", "recurrence", "recurrenceUntil", "kind"])
    if (event[key] != null) params.set(key, String(event[key]));
  return "intent://add-event?" + params +
    "#Intent;scheme=lmnaide;package=dev.lmnaide.calendar;" +
    "S.browser_fallback_url=" + encodeURIComponent(fallbackUrl) + ";end";
}

function escapeText(value) {
  return value.replaceAll("\\", "\\\\").replaceAll(/\r\n|\r|\n/g, "\\n")
    .replaceAll(";", "\\;").replaceAll(",", "\\,");
}

// RFC 5545 folds content lines at 75 UTF-8 octets, without splitting a character.
function fold(line) {
  let result = "", bytes = 0;
  const encoder = new TextEncoder();
  for (const char of line) {
    const size = encoder.encode(char).length;
    if (bytes + size > 75) {
      result += "\r\n ";
      bytes = 1;
    }
    result += char;
    bytes += size;
  }
  return result;
}

export function toICalendar(event, uid, now = Date.now()) {
  const suffix = event.allDay ? ";VALUE=DATE" : "";
  const lines = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//lmnaide//Shared events//EN",
    "CALSCALE:GREGORIAN",
    "BEGIN:VEVENT",
    "UID:" + uid,
    "DTSTAMP:" + calendarDate(now),
    "DTSTART" + suffix + ":" + calendarDate(event.start, event.allDay),
  ];
  if (exportEnd(event) > event.start)
    lines.push("DTEND" + suffix + ":" + calendarDate(exportEnd(event), event.allDay));
  lines.push("SUMMARY:" + escapeText(event.title));
  if (event.description) lines.push("DESCRIPTION:" + escapeText(event.description));
  if (event.location) lines.push("LOCATION:" + escapeText(event.location));
  const rule = recurrenceRule(event);
  if (rule) lines.push("RRULE:" + rule);
  lines.push("END:VEVENT", "END:VCALENDAR");
  return lines.map(fold).join("\r\n") + "\r\n";
}
