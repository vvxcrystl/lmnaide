// The web client mirrors the Android app's screens (ui/main, ui/event, ui/calendars). Sizes,
// colors, wording and behavior follow the Compose code so both look and work the same.
import { expandOccurrences } from "./recurrence.js";
import {
  THEMES,
  schemeFor,
  isDarkTheme,
  applyScheme,
  css as hexColor,
} from "./themes.js";
import { parse as parseSmart, looksLikeTask, matchCalendar } from "./smart.js";
import { defaultRecords } from "./holidays.js";

const $ = (id) => document.getElementById(id);
const DAY = 86400000;
const SVG = "http://www.w3.org/2000/svg";

/* ---------- Icons ---------- */
const I = {
  share:
    "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7a3.3 3.3 0 0 0 0-1.4l7.05-4.11A3 3 0 1 0 15 5c0 .24.03.47.09.7L8.04 9.81a3 3 0 1 0 0 4.38l7.12 4.16A3 3 0 1 0 18 16.08Z",
  close:
    "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12 19 6.41Z",
  back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2Z",
  add: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2Z",
  edit: "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25ZM5.92 19H5v-.92l9.06-9.06.92.92L5.92 19ZM20.71 5.63l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83a1 1 0 0 0 0-1.41Z",
  keyboard:
    "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2Zm0 12H4V7h16v10Zm-9-9h2v2h-2V8Zm0 3h2v2h-2v-2ZM8 8h2v2H8V8Zm0 3h2v2H8v-2Zm-3 0h2v2H5v-2Zm0-3h2v2H5V8Zm3 6h8v2H8v-2Zm6-3h2v2h-2v-2Zm0-3h2v2h-2V8Zm3 3h2v2h-2v-2Zm0-3h2v2h-2V8Z",
  more: "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2Zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2Zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2Z",
  delete:
    "M16 9v10H8V9h8m-1.5-6h-5l-1 1H5v2h14V4h-3.5l-1-1ZM18 7H6v12c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7Z",
  mail: "M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2Zm0 14H4V8l8 5 8-5v10Zm-8-7L4 6h16l-8 5Z",
  copy: "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1Zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2Zm0 16H8V7h11v14Z",
  flag: "m12.36 6 .4 2H18v6h-3.36l-.4-2H7V6h5.36M14 4H5v17h2v-7h5.6l.4 2h7V6h-5.6L14 4Z",
  place:
    "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7ZM7 9c0-2.76 2.24-5 5-5s5 2.24 5 5c0 2.88-2.88 7.19-5 9.88C9.92 16.21 7 11.85 7 9Zm5 2.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z",
  bell: "M12 22c1.1 0 2-.9 2-2h-4a2 2 0 0 0 2 2Zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2Zm-2 1H8v-6c0-2.48 1.51-4.5 4-4.5s4 2.02 4 4.5v6Z",
  event:
    "M19 4h-1V2h-2v2H8V2H6v2H5a2 2 0 0 0-1.99 2L3 20a2 2 0 0 0 2 2h14c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2Zm0 16H5V10h14v10Zm0-12H5V6h14v2Zm-7 5h5v5h-5v-5Z",
  notes: "M3 18h12v-2H3v2ZM3 6v2h18V6H3Zm0 7h18v-2H3v2Z",
  clock:
    "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2ZM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8Zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67V7Z",
  repeat: "M7 7h10v3l4-4-4-4v3H5v6h2V7Zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4Z",
  palette:
    "M12 22C6.49 22 2 17.51 2 12S6.49 2 12 2s10 4.04 10 9c0 3.31-2.69 6-6 6h-1.77c-.28 0-.5.22-.5.5 0 .12.05.23.13.33.41.47.64 1.06.64 1.67A2.5 2.5 0 0 1 12 22Zm0-18c-4.41 0-8 3.59-8 8s3.59 8 8 8c.28 0 .5-.22.5-.5a.54.54 0 0 0-.14-.35c-.41-.46-.63-1.05-.63-1.65a2.5 2.5 0 0 1 2.5-2.5H16c2.21 0 4-1.79 4-4 0-3.86-3.59-7-8-7ZM6.5 13a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Zm3-4a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Zm5 0a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Zm3 4a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Z",
  check: "M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41L9 16.17Z",
  undo: "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8Z",
  left: "M15.41 16.59 10.83 12l4.58-4.59L14 6l-6 6 6 6 1.41-1.41Z",
  right: "M8.59 16.59 13.17 12 8.59 7.41 10 6l6 6-6 6-1.41-1.41Z",
  down: "M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41Z",
  circle:
    "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm0 18a8 8 0 1 1 0-16 8 8 0 0 1 0 16Z",
  checkCircle:
    "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm-2 15-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9Z",
  cloud:
    "M19.35 10.04A7.49 7.49 0 0 0 12 4C9.11 4 6.6 5.64 5.35 8.04A5.994 5.994 0 0 0 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96ZM10 17l-3.5-3.5 1.41-1.41L10 14.17 15.18 9l1.41 1.41L10 17Z",
  agenda:
    "M19 13H5c-1.1 0-2 .9-2 2v4c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2v-4c0-1.1-.9-2-2-2Zm0 6H5v-4h14v4Zm0-16H5c-1.1 0-2 .9-2 2v4c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2Zm0 6H5V5h14v4Z",
  viewDay:
    "M2 21h19v-3H2v3ZM20 8H3c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h17c.55 0 1-.45 1-1V9c0-.55-.45-1-1-1Zm-1 6H4v-4h15v4ZM2 3v3h19V3H2Z",
  viewColumn:
    "M3 5v14h18V5H3Zm5.33 12H5V7h3.33v10Zm5.34 0h-3.33V7h3.33v10ZM19 17h-3.33V7H19v10Z",
  viewWeek:
    "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2ZM8 18H4V6h4v12Zm6 0h-4V6h4v12Zm6 0h-4V6h4v12Z",
  viewMonth:
    "M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2ZM8 11H4V6h4v5Zm6 0h-4V6h4v5Zm6 0h-4V6h4v5ZM8 18H4v-5h4v5Zm6 0h-4v-5h4v5Zm6 0h-4v-5h4v5Z",
};
function icon(path) {
  const svg = document.createElementNS(SVG, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  const p = document.createElementNS(SVG, "path");
  p.setAttribute("d", path);
  svg.append(p);
  return svg;
}

/** Builds an element: h("div", { class, text, onclick, style, ...attributes }, ...children). */
function h(tag, props = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    // ARIA states need "true" and "false" spelled out.
    if (k.startsWith("aria-") && typeof v === "boolean") {
      el.setAttribute(k, String(v));
      continue;
    }
    if (v == null || v === false) continue;
    if (k === "class") el.className = v;
    else if (k === "text") el.textContent = v;
    else if (k === "style")
      for (const [s, value] of Object.entries(v))
        el.style.setProperty(s, value);
    else if (k.startsWith("on")) el[k] = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  el.append(...children.flat().filter((c) => c != null && c !== false));
  return el;
}
const iconButton = (path, label, onclick, extra = {}) =>
  h(
    "button",
    {
      type: "button",
      class: "icon",
      "aria-label": label,
      title: label,
      onclick,
      ...extra,
    },
    icon(path),
  );

/* ---------- Preferences, kept in this browser like the app's own settings ---------- */
function localeWeekStart() {
  try {
    const locale = new Intl.Locale(navigator.language);
    const info = locale.getWeekInfo?.() ?? locale.weekInfo;
    if (info?.firstDay) return info.firstDay % 7;
  } catch {}
  return 0;
}
const prefs = {
  view: "MONTH",
  showTasks: true,
  use24h: false,
  weekStart: localeWeekStart(),
  // Light, dark or following the system, and the named color theme.
  theme: "system",
  appTheme: "MATERIAL_YOU",
  ...JSON.parse(localStorage.getItem("lmnaide.prefs") || "{}"),
};
function savePrefs() {
  localStorage.setItem("lmnaide.prefs", JSON.stringify(prefs));
}
const systemDark = matchMedia("(prefers-color-scheme: dark)");
const themeIsDark = () =>
  isDarkTheme(prefs.appTheme, prefs.theme, systemDark.matches);
function applyTheme() {
  const dark = themeIsDark();
  applyScheme(schemeFor(prefs.appTheme, dark), dark);
}
applyTheme();
systemDark.addEventListener("change", () => {
  applyTheme();
  if (token) render();
  if ($("settings-page")?.open) renderSettings();
});

/* ---------- Dates ---------- */
const pad = (n) => String(n).padStart(2, "0");
const dayKey = (d) =>
  `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const fromDayKey = (k) => {
  const [y, m, d] = k.split("-").map(Number);
  return new Date(y, m - 1, d);
};
const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const addDays = (d, n) =>
  new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
const startOfMonth = (d) => new Date(d.getFullYear(), d.getMonth(), 1);
const addMonths = (d, n) => new Date(d.getFullYear(), d.getMonth() + n, 1);
const endOfMonth = (d) => new Date(d.getFullYear(), d.getMonth() + 1, 0);
const epochDay = (d) =>
  Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / DAY;
const fromEpochDay = (n) => {
  const u = new Date(n * DAY);
  return new Date(u.getUTCFullYear(), u.getUTCMonth(), u.getUTCDate());
};
const daysBetween = (a, b) => epochDay(b) - epochDay(a);
const sameDay = (a, b) => !!a && !!b && dayKey(a) === dayKey(b);
const sameMonth = (a, b) =>
  a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth();
const maxDate = (a, b) => (a > b ? a : b);
const minDate = (a, b) => (a < b ? a : b);
const weekStartOf = (d) =>
  addDays(d, -((d.getDay() - prefs.weekStart + 7) % 7));
const today = () => startOfDay(new Date());
const minutesOf = (d) => d.getHours() * 60 + d.getMinutes();
const atMinute = (date, minute) =>
  new Date(
    date.getFullYear(),
    date.getMonth(),
    date.getDate(),
    Math.floor(minute / 60),
    minute % 60,
  );

/* ---------- Formatting, as in ui/common/Formatters.kt ---------- */
// Locale calls otherwise allocate a native formatter for every label on every refresh.
// Cache formatting styles, not dates, and bound the cache independently of navigation.
const dateFormatters = new Map();
function fmt(d, o) {
  const key = JSON.stringify(o);
  let formatter = dateFormatters.get(key);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(undefined, o);
    if (dateFormatters.size >= 32)
      dateFormatters.delete(dateFormatters.keys().next().value);
    dateFormatters.set(key, formatter);
  }
  return formatter.format(d);
}
function time(d) {
  if (prefs.use24h) return pad(d.getHours()) + ":" + pad(d.getMinutes());
  return fmt(
    d,
    d.getMinutes()
      ? { hour: "numeric", minute: "2-digit", hour12: true }
      : { hour: "numeric", hour12: true },
  );
}
const timeRange = (a, b) => `${time(a)} – ${time(b)}`;
const hourLabel = (hour) =>
  prefs.use24h ? pad(hour) + ":00" : time(new Date(2000, 0, 1, hour));
const withYear = (d, o) =>
  d.getFullYear() === today().getFullYear() ? o : { ...o, year: "numeric" };
const dayLong = (d) =>
  fmt(d, withYear(d, { weekday: "long", month: "long", day: "numeric" }));
const dayMedium = (d) =>
  fmt(d, withYear(d, { weekday: "short", month: "short", day: "numeric" }));
const dayShort = (d) =>
  fmt(d, { month: "short", day: "numeric", year: "numeric" });
const monthYear = (d) => fmt(d, withYear(d, { month: "long" }));
const monthYearFull = (d) => fmt(d, { month: "long", year: "numeric" });
const weekdayShort = (d) => fmt(d, { weekday: "short" });
function weekRange(a, b) {
  const first = fmt(a, { month: "short", day: "numeric" });
  return sameDay(a, b) ? first : `${first} – ${b.getDate()}`;
}
/** "October", "September – October", or with years when outside the current one. */
function rangeTitle([a, b]) {
  if (sameMonth(a, b)) return monthYear(a);
  const year = today().getFullYear();
  if (a.getFullYear() === year && b.getFullYear() === year)
    return `${fmt(a, { month: "short" })} – ${fmt(b, { month: "short" })}`;
  const o = { month: "short", year: "numeric" };
  return `${fmt(a, o)} – ${fmt(b, o)}`;
}
function occurrenceTime(o) {
  if (o.allDay) return "All day";
  if (!sameDay(o.startDate, o.lastDate))
    return `${dayMedium(o.startDate)}, ${time(o.start)} – ${dayMedium(o.lastDate)}, ${time(o.end)}`;
  return timeRange(o.start, o.end);
}
function occurrenceWhen(o) {
  const first = o.startDate,
    last = o.lastDate,
    one = sameDay(first, last);
  if (o.allDay && one) return [dayLong(first)];
  if (o.allDay) return [`${dayMedium(first)} – ${dayMedium(last)}`];
  if (one) return [dayLong(first), timeRange(o.start, o.end)];
  return [
    `${dayMedium(first)}, ${time(o.start)} –`,
    `${dayMedium(last)}, ${time(o.end)}`,
  ];
}
function recurrenceText(rule, start) {
  return {
    NONE: "Does not repeat",
    DAILY: "Every day",
    WEEKDAYS: "Every weekday (Mon–Fri)",
    WEEKLY: `Every week on ${fmt(start, { weekday: "long" })}`,
    MONTHLY: `Every month on day ${start.getDate()}`,
    YEARLY: `Every year on ${fmt(start, { month: "long", day: "numeric" })}`,
  }[rule];
}
const plural = (n, unit) => (n === 1 ? `1 ${unit}` : `${n} ${unit}s`);
function duration(m) {
  if (m % 10080 === 0) return plural(m / 10080, "week");
  if (m % 1440 === 0) return plural(m / 1440, "day");
  if (m % 60 === 0) return plural(m / 60, "hour");
  if (m > 60)
    return `${plural(Math.floor(m / 60), "hour")} ${plural(m % 60, "minute")}`;
  return plural(m, "minute");
}
function reminderText(m, allDay) {
  if (allDay) {
    const at = time(new Date(2000, 0, 1, 9));
    if (m === 0) return `On the day at ${at}`;
    if (m === 1440) return `The day before at ${at}`;
    if (m === 10080) return `1 week before at ${at}`;
    return `${duration(m)} before ${at}`;
  }
  return m === 0 ? "At time of event" : `${duration(m)} before`;
}

/* ---------- Colors, as in ui/common/EventColors.kt ---------- */
const EVENT_COLORS = [
  ["Tomato", 0xffd50000],
  ["Flamingo", 0xffe67c73],
  ["Tangerine", 0xfff4511e],
  ["Banana", 0xfff6bf26],
  ["Sage", 0xff33b679],
  ["Basil", 0xff0b8043],
  ["Peacock", 0xff039be5],
  ["Blueberry", 0xff3f51b5],
  ["Lavender", 0xff7986cb],
  ["Grape", 0xff8e24aa],
  ["Graphite", 0xff616161],
].map(([label, argb]) => ({ label, argb: argb | 0 }));
const IMPORTANCE = {
  LOW: { label: "Low", color: "#34A853" },
  MEDIUM: { label: "Medium", color: "#FBBC04" },
  HIGH: { label: "High", color: "#EA4335" },
};
const IMPORTANCE_HINTS = {
  LOW: "Reminders arrive silently",
  MEDIUM: "Standard reminders with sound",
  HIGH: "Alerts at start and repeats until you respond",
};
const colorLabel = (argb) =>
  EVENT_COLORS.find((c) => c.argb === argb)?.label ?? "Custom";
const rgbOf = (argb) => [(argb >> 16) & 255, (argb >> 8) & 255, argb & 255];
const hexRgb = (hex) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16));
const css = (argb) => `rgb(${rgbOf(argb)})`;
function luminance(c) {
  const f = (v) => {
    v /= 255;
    return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]);
}
const contentColor = (c) => (luminance(c) > 0.55 ? "#1F1F1F" : "#FFFFFF");
/** Background and text colors for an event chip; finished items fade toward the surface. */
function chipColors(o) {
  let c = rgbOf(o.color);
  if (!isPast(o)) return [`rgb(${c})`, contentColor(c)];
  const s = hexRgb(
    getComputedStyle(document.documentElement)
      .getPropertyValue("--surface")
      .trim(),
  );
  c = c.map((v, i) => Math.round(v + (s[i] - v) * 0.55));
  return [
    `rgb(${c})`,
    luminance(c) > 0.3 ? "rgba(31,31,31,.75)" : "rgba(255,255,255,.75)",
  ];
}
function paint(el, o) {
  const [bg, fg] = chipColors(o);
  el.style.background = bg;
  el.style.color = fg;
  return el;
}

/* ---------- Data ---------- */
let token = sessionStorage.getItem("token"),
  email = sessionStorage.getItem("email"),
  records = [],
  cursor = 0,
  busy = false,
  lastSync = 0,
  syncMessage = "";

const calendars = () =>
  records.filter((r) => r.kind === "calendar" && !r.deleted);
const calendarById = (id) => calendars().find((c) => c.id === id);
const eventById = (id) =>
  records.find((r) => r.kind === "event" && r.id === id && !r.deleted);

/** Converts a stored time to local wall-clock time; all-day dates float in UTC. */
function wall(ms, allDay) {
  if (!allDay) return new Date(ms);
  const u = new Date(ms);
  return new Date(
    u.getUTCFullYear(),
    u.getUTCMonth(),
    u.getUTCDate(),
    u.getUTCHours(),
    u.getUTCMinutes(),
  );
}
const toStored = (date, allDay) =>
  allDay
    ? Date.UTC(date.getFullYear(), date.getMonth(), date.getDate())
    : date.getTime();

/** One instance of an event, like domain/Occurrence.kt. */
function makeOccurrence(record, calendar, expanded) {
  const d = record.data;
  const start = wall(expanded.occurrence, d.allDay);
  const end = wall(expanded.occurrenceEnd, d.allDay);
  const startDate = startOfDay(start);
  // An end at midnight belongs to the previous day.
  let lastDate =
    end > start && minutesOf(end) === 0 && end.getSeconds() === 0
      ? addDays(startOfDay(end), -1)
      : startOfDay(end);
  if (lastDate < startDate) lastDate = startDate;
  const isTask = d.kind === "TASK";
  return {
    record,
    event: d,
    calendar,
    start,
    end,
    startDate,
    lastDate,
    allDay: d.allDay,
    isTask,
    completed: isTask && d.completions.includes(epochDay(startDate)),
    inAllDayLane: d.allDay || end - start >= DAY,
    color: d.color ?? calendar.data.color,
  };
}
const occursOn = (o, date) => o.startDate <= date && o.lastDate >= date;
/** Done tasks and finished events are drawn faded; open tasks stay prominent even when overdue. */
function isPast(o) {
  if (o.isTask) return o.completed;
  if (o.allDay) return o.lastDate < today();
  return o.end <= new Date();
}
/** All-day and multi-day first, then by local start, then longer first. */
function occurrenceOrder(a, b) {
  return (
    !a.inAllDayLane - !b.inAllDayLane ||
    a.start - b.start ||
    b.end - a.end ||
    a.event.title.localeCompare(b.event.title)
  );
}
function visibleEvents() {
  const cals = new Map(calendars().map((c) => [c.id, c]));
  return records.flatMap((r) => {
    if (r.kind !== "event" || r.deleted) return [];
    const c = cals.get(r.data.calendarId);
    if (!c?.data.visible || (!prefs.showTasks && r.data.kind === "TASK"))
      return [];
    return [[r, c]];
  });
}
function occurrences(from, to) {
  const out = [];
  for (const [r, c] of visibleEvents())
    for (const x of expandOccurrences(
      r,
      dayKey(from),
      daysBetween(from, to) + 1,
    )) {
      const o = makeOccurrence(r, c, x);
      if (o.startDate <= to && o.lastDate >= from) out.push(o);
    }
  return out.sort(occurrenceOrder);
}
function byDay(list, from, to) {
  const days = new Map();
  for (const o of list)
    for (
      let d = maxDate(o.startDate, from);
      d <= minDate(o.lastDate, to);
      d = addDays(d, 1)
    ) {
      const k = dayKey(d);
      if (!days.has(k)) days.set(k, []);
      days.get(k).push(o);
    }
  return days;
}
/** The instance of event [id] starting at [start], or null once it's gone. */
function findOccurrence(id, start) {
  const r = eventById(id);
  const c = r && calendarById(r.data.calendarId);
  if (!c) return null;
  const match = expandOccurrences(r, dayKey(addDays(startOfDay(start), -1)), 2)
    .map((x) => makeOccurrence(r, c, x))
    .find((o) => o.start.getTime() === start.getTime());
  if (match || r.data.recurrence !== "NONE") return match ?? null;
  // A one-off event that was moved: follow it.
  const first = startOfDay(wall(r.data.start, r.data.allDay));
  const x = expandOccurrences(r, dayKey(first), 1)[0];
  return x ? makeOccurrence(r, c, x) : null;
}
/** The next occurrence from today for a series, or the first one if none are upcoming. */
function representative(r, c) {
  const upcoming = expandOccurrences(r, dayKey(today()), 5 * 366)[0];
  if (upcoming) return makeOccurrence(r, c, upcoming);
  const first = startOfDay(wall(r.data.start, r.data.allDay));
  const x = expandOccurrences(r, dayKey(first), 1)[0];
  return x ? makeOccurrence(r, c, x) : null;
}

/* ---------- Server ---------- */
async function api(path, body) {
  const res = await fetch("/api/" + path, {
    method: body ? "POST" : "GET",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || "Request failed");
  return data;
}
let snackTimer;
/** A message along the bottom, with an optional action like "View". */
function snackbar(text, action) {
  clearTimeout(snackTimer);
  const bar = $("snackbar");
  bar.replaceChildren(
    ...(text ? [h("span", { text })] : []),
    ...(text && action
      ? [
          h("button", {
            type: "button",
            class: "text",
            text: action.label,
            onclick: () => {
              bar.replaceChildren();
              action.run();
            },
          }),
        ]
      : []),
  );
  if (text)
    snackTimer = setTimeout(() => bar.replaceChildren(), action ? 6000 : 4000);
}
function signedIn() {
  $("auth").hidden = !!token;
  $("calendar").hidden = !token;
  if (token) render({ jump: true });
}
async function authenticate(register) {
  snackbar();
  try {
    const result = await api(register ? "register" : "login", {
      email: $("email").value,
      password: $("password").value,
      ...(register ? { username: $("username").value.trim() } : {}),
    });
    token = result.token;
    email = result.email;
    username = result.username ?? null;
    sessionStorage.setItem("token", token);
    sessionStorage.setItem("email", email);
    $("password").value = "";
    records = [];
    cursor = 0;
    seedTried = false;
    signedIn();
    loadProfile();
    await sync();
  } catch (e) {
    snackbar(e.message);
  }
}
$("login").onsubmit = (e) => {
  e.preventDefault();
  authenticate(false);
};
$("register").onclick = () => {
  if ($("login").reportValidity()) authenticate(true);
};

// Syncs run one at a time, so polling can never drop a save.
let syncQueue = Promise.resolve();
function sync(changes = []) {
  const task = syncQueue.then(() => performSync(changes));
  syncQueue = task.catch(() => {});
  return task;
}
async function performSync(changes) {
  busy = true;
  try {
    const data = await api("sync", { cursor, changes });
    for (const r of data.records) {
      const i = records.findIndex((x) => x.kind === r.kind && x.id === r.id);
      if (i < 0) records.push(r);
      else records[i] = r;
    }
    cursor = data.cursor;
    lastSync = Date.now();
    syncMessage = "Up to date";
    seedNewAccount();
    refresh();
    if (data.conflicts.length)
      snackbar(
        "An item changed on another device. Your edit is saved in Edit history.",
      );
  } catch (e) {
    syncMessage = "Could not sync: " + e.message;
    snackbar(e.message);
    if ($("settings-page").open) renderSettings();
    throw e;
  } finally {
    busy = false;
  }
}
/**
 * A brand-new account starts with the same calendars as the app: Personal, Work and Holidays.
 * They share the Android seed's record IDs, so a phone joining later doesn't add them twice.
 */
let seedTried = false;
function seedNewAccount() {
  if (seedTried || records.some((r) => r.kind === "calendar")) return;
  seedTried = true;
  sync(defaultRecords().map((r) => change(r, r.data))).catch(() => {});
}
const change = (record, data, deleted = false) => ({
  kind: record.kind,
  id: record.id,
  baseVersion: record.version || 0,
  operationId: crypto.randomUUID(),
  deleted,
  data: deleted ? null : data,
});
const newRecord = (kind) => ({ kind, id: crypto.randomUUID(), version: 0 });
async function save(changes) {
  try {
    await sync(changes);
    return true;
  } catch {
    return false;
  }
}

/* ---------- Main screen state ---------- */
const VIEWS = [
  ["SCHEDULE", "Schedule", I.agenda],
  ["DAY", "Day", I.viewDay],
  ["THREE_DAY", "3 days", I.viewColumn],
  ["WEEK", "Week", I.viewWeek],
  ["MONTH", "Month", I.viewMonth],
];
let selected = today(),
  visible = [selected, selected],
  pickerOpen = false,
  miniMonth = startOfMonth(selected),
  returnView = null;

function selectDate(date) {
  selected = startOfDay(date);
  miniMonth = startOfMonth(selected);
}
/** Explicit jumps: the date button, the mini month, and saving an event. */
function jumpTo(date) {
  selectDate(date);
  pickerOpen = false;
  render({ jump: true });
}
function setView(view) {
  if (view === "THREE_DAY" && prefs.view !== "THREE_DAY")
    grid.anchor = selected;
  prefs.view = view;
  savePrefs();
  render({ jump: true });
}
/** Opening a day from another view switches to the day view; Escape returns to where you were. */
function openDay(date) {
  if (prefs.view !== "DAY") returnView = prefs.view;
  selectDate(date);
  setView("DAY");
}

/** Re-renders after data changes, keeping each view where it is. */
function refresh() {
  if (!token) return;
  render();
  if ($("detail").open) renderDetail();
  if ($("calendars").open) renderCalendars();
  if ($("search-page").open) renderResults();
  if ($("settings-page").open) renderSettings();
}
/** Wide screens get Google Calendar's web layout: a sidebar, and paging in the top bar. */
const wide = matchMedia("(min-width: 1000px)");
function render({ jump = false, slide = 0 } = {}) {
  // Reload startup fetches data before revealing the calendar. Hidden layouts have
  // zero dimensions and must not trigger schedule growth or allocate view resources.
  if (!token || $("calendar").hidden) return;
  renderDrawer();
  $("today-date").textContent = new Date().getDate();
  $("view-label").textContent = VIEWS.find(([id]) => id === prefs.view)[1];
  $("period").setAttribute("aria-expanded", pickerOpen);
  $("mini").hidden = !pickerOpen || wide.matches;
  if (wide.matches) renderMini(0, $("side-mini"));
  else if (pickerOpen) renderMini();
  if (prefs.view === "SCHEDULE") renderSchedule(jump);
  else if (prefs.view === "MONTH") renderMonth(slide);
  else renderGrid(slide);
  updateTitle();
}
function updateTitle() {
  $("period-title").textContent = rangeTitle(visible);
}

/* ---------- Drawer, as in ui/main/AppDrawer.kt ---------- */
function renderDrawer() {
  $("views").replaceChildren(
    ...VIEWS.map(([id, label, path]) =>
      h(
        "button",
        {
          type: "button",
          class: "nav-item" + (prefs.view === id ? " selected" : ""),
          "aria-current": prefs.view === id ? "page" : null,
          onclick: () => {
            returnView = null;
            closeDrawer();
            setView(id);
          },
        },
        icon(path),
        label,
      ),
    ),
  );
  const row = (name, color, checked, onclick) =>
    h(
      "button",
      {
        type: "button",
        class: "visibility",
        role: "checkbox",
        "aria-checked": checked,
        onclick,
      },
      h(
        "span",
        { class: "checkbox", style: { "--box": color } },
        icon(I.check),
      ),
      h("span", { class: "title-small", text: name }),
    );
  $("visibility").replaceChildren(
    ...calendars().map((c) =>
      row(c.data.name, css(c.data.color), c.data.visible, () =>
        save([change(c, { ...c.data, visible: !c.data.visible })]),
      ),
    ),
    row("Tasks", "var(--primary)", prefs.showTasks, () => {
      prefs.showTasks = !prefs.showTasks;
      savePrefs();
      render();
    }),
  );
}
/** On phones the drawer slides over the calendar; on wide screens it's a sidebar that folds away. */
let sidebarHidden = localStorage.getItem("lmnaide.sidebar") === "hidden";
function setDrawer(open) {
  if (wide.matches) {
    sidebarHidden = !open;
    localStorage.setItem("lmnaide.sidebar", open ? "shown" : "hidden");
    $("calendar").classList.toggle("collapsed", !open);
    $("drawer").classList.remove("open");
    $("drawer").inert = !open;
    $("scrim").hidden = true;
    $("menu").setAttribute("aria-expanded", open);
    // The calendar takes the freed width.
    setTimeout(() => render(), 260);
    return;
  }
  $("calendar").classList.remove("collapsed");
  $("drawer").classList.toggle("open", open);
  $("drawer").inert = !open;
  $("scrim").hidden = !open;
  $("menu").setAttribute("aria-expanded", open);
  if (open) $("drawer").querySelector(".nav-item")?.focus();
}
const closeDrawer = () => !wide.matches && setDrawer(false);
$("menu").onclick = () =>
  setDrawer(
    wide.matches ? sidebarHidden : !$("drawer").classList.contains("open"),
  );
$("scrim").onclick = () => setDrawer(false);
wide.addEventListener("change", () => {
  pickerOpen = false;
  setDrawer(wide.matches ? !sidebarHidden : false);
});
$("manage").onclick = () => {
  closeDrawer();
  openCalendars();
};
$("settings").onclick = () => {
  closeDrawer();
  openSettings();
};

/* ---------- Top bar, as in ui/main/MainScreen.kt ---------- */
$("period").onclick = () => {
  if (wide.matches) return;
  pickerOpen = !pickerOpen;
  miniMonth = startOfMonth(selected);
  render();
};
$("today-pill").onclick = () => jumpTo(today());
$("prev").onclick = () => page(-1);
$("next").onclick = () => page(1);
/** The top bar arrows: a period back or forward, or a month in the schedule. */
function page(dir) {
  if (prefs.view !== "SCHEDULE") return shiftPeriod(dir);
  jumpTo(addMonths(visible[0], dir));
}
$("view-menu").onclick = () =>
  popupMenu(
    $("view-menu"),
    VIEWS.map(([id, label, path]) => ({
      label,
      icon: path,
      checked: prefs.view === id,
      action: () => {
        returnView = null;
        setView(id);
      },
    })),
    { alignEnd: true },
  );
$("create").onclick = () => openQuickAdd();
$("avatar").onclick = openAccount;
$("today").onclick = () => {
  const box = $("today-date");
  box.classList.remove("pop");
  void box.offsetWidth;
  box.classList.add("pop");
  jumpTo(today());
};
// Like the app, the create button opens quick add, which can lead on to the full editor.
$("add").onclick = () => openQuickAdd();
$("search").onclick = openSearch;

/** A day-of-month number, circled when it's today. */
function dayNumber(date, size, fontSize, { dim = false, chosen = false } = {}) {
  const isToday = sameDay(date, today());
  return h("span", {
    class:
      "num" + (isToday ? " today" : chosen ? " selected" : dim ? " dim" : ""),
    text: date.getDate(),
    style: {
      width: size + "px",
      height: size + "px",
      "font-size": fontSize + "px",
    },
  });
}
function weekdayHeader() {
  const first = weekStartOf(today());
  return h(
    "div",
    { class: "weekdays label-small" },
    [...Array(7)].map((_, i) =>
      h("span", { text: weekdayShort(addDays(first, i)).slice(0, 1) }),
    ),
  );
}
const slideClass = (n) => (n > 0 ? " slide-next" : n < 0 ? " slide-prev" : "");

/* ---------- Mini month, as in ui/main/MiniMonth.kt ---------- */
function renderMini(slide = 0, target = $("mini")) {
  // The sidebar's copy on wide screens is smaller, like Google Calendar's.
  const compact = target !== $("mini");
  const first = weekStartOf(miniMonth);
  const last = addDays(first, 41);
  const eventDays = byDay(occurrences(first, last), first, last);
  const days = [...Array(42)].map((_, i) => {
    const date = addDays(first, i);
    return h(
      "button",
      {
        type: "button",
        "aria-label": dayLong(date),
        onclick: () => jumpTo(date),
      },
      dayNumber(date, compact ? 24 : 32, compact ? 10 : 13, {
        dim: !sameMonth(date, miniMonth),
        chosen: sameDay(date, selected),
      }),
      eventDays.has(dayKey(date)) && h("span", { class: "dot" }),
    );
  });
  const move = (n) => {
    miniMonth = addMonths(miniMonth, n);
    renderMini(n, target);
  };
  target.replaceChildren(
    h(
      "div",
      { class: "mini-head" },
      h("p", { class: "title-small", text: monthYearFull(miniMonth) }),
      iconButton(I.left, "Previous month", () => move(-1)),
      iconButton(I.right, "Next month", () => move(1)),
    ),
    weekdayHeader(),
    h("div", { class: "mini-grid" + slideClass(slide) }, days),
  );
}

/* ---------- Event chips, as in ui/common/EventBlocks.kt ---------- */
const importanceDot = (importance) =>
  importance &&
  h("span", {
    class: "importance",
    title: IMPORTANCE[importance].label + " importance",
    style: { background: IMPORTANCE[importance].color },
  });

/** A full-width event card used in the schedule and search lists. */
function eventCard(o, timeText, detail, onToggle) {
  const card = h(
    "div",
    {
      class:
        "event-card" + (o.isTask ? " task" : "") + (o.completed ? " done" : ""),
      role: "button",
      tabindex: 0,
      onclick: () => openDetail(o),
      onkeydown: (e) => {
        if (e.target === card && (e.key === "Enter" || e.key === " ")) {
          e.preventDefault();
          openDetail(o);
        }
      },
    },
    o.isTask &&
      h(
        "button",
        {
          type: "button",
          class: "check",
          disabled: !onToggle,
          "aria-label": o.completed ? "Mark uncompleted" : "Mark completed",
          onclick: (e) => {
            e.stopPropagation();
            onToggle();
          },
        },
        icon(o.completed ? I.checkCircle : I.circle),
      ),
    h(
      "div",
      { class: "text-col" },
      h("strong", { text: o.event.title || "(No title)" }),
      h("small", { text: timeText }),
      detail && h("small", { text: detail }),
    ),
    importanceDot(o.event.importance),
  );
  return paint(card, o);
}
/** A one-line chip for all-day lanes and the month grid. */
function eventBar(o, fontSize, onclick) {
  const bar = h(
    onclick ? "button" : "div",
    {
      type: onclick ? "button" : null,
      class: "bar" + (o.completed ? " done" : ""),
      onclick,
      style: {
        "font-size": fontSize + "px",
        "line-height": fontSize + 2 + "px",
      },
    },
    importanceDot(o.event.importance),
    o.isTask && icon(o.completed ? I.checkCircle : I.circle),
    h("span", { class: "bar-title", text: o.event.title || "(No title)" }),
  );
  return paint(bar, o);
}
async function toggleTask(o) {
  const d = o.event,
    day = epochDay(o.startDate);
  await save([
    change(o.record, {
      ...d,
      completions: o.completed
        ? d.completions.filter((x) => x !== day)
        : [...d.completions, day],
    }),
  ]);
}

/* ---------- Schedule, as in ui/main/ScheduleView.kt ---------- */
const sched = { anchor: null, end: null };
const listStart = (anchor) => addMonths(anchor, -1);
const listLimit = (anchor) =>
  new Date(anchor.getFullYear() + 5, anchor.getMonth(), anchor.getDate());

function renderSchedule(jump) {
  const view = $("view");
  const old = view.querySelector(".schedule");
  const target = selected;
  let rebuilt = false;
  if (
    !sched.anchor ||
    target < listStart(sched.anchor) ||
    target > listLimit(sched.anchor)
  ) {
    sched.anchor = target;
    sched.end = endOfMonth(addMonths(target, 6));
    rebuilt = true;
  }
  sched.end = maxDate(sched.end, endOfMonth(addMonths(target, 1)));
  const start = listStart(sched.anchor);
  const now = today();
  const days = byDay(occurrences(start, sched.end), start, sched.end);
  const rows = [];
  let month = null,
    labelledWeek = null;
  for (let day = start; day <= sched.end; day = addDays(day, 1)) {
    if (!month || !sameMonth(day, month)) {
      month = startOfMonth(day);
      rows.push(banner(month));
      // A new month banner restarts the week label, like Google Calendar does.
      labelledWeek = null;
    }
    const list = days.get(dayKey(day)) || [];
    if (
      list.length ||
      sameDay(day, now) ||
      sameDay(day, sched.anchor) ||
      sameDay(day, target)
    ) {
      const week = weekStartOf(day);
      if (!sameDay(week, labelledWeek)) {
        labelledWeek = week;
        const from = maxDate(week, month),
          to = minDate(addDays(week, 6), endOfMonth(month));
        rows.push(
          h("p", {
            class: "week-label label-medium",
            "data-date": dayKey(from),
            "data-kind": "w",
            text: weekRange(from, to),
          }),
        );
      }
      rows.push(dayRow(day, list));
    }
  }
  // Data refreshes keep the row at the top where it was.
  let keep = null;
  if (old && !jump && !rebuilt) {
    const top = firstVisibleRow(old);
    if (top)
      keep = [
        top.dataset.kind + top.dataset.date,
        top.offsetTop - old.scrollTop,
      ];
  }
  const from = old && !rebuilt ? firstVisibleRow(old)?.dataset.date : null;
  const list = h(
    "div",
    { class: "schedule", onscroll: onScheduleScroll },
    rows,
  );
  view.replaceChildren(list);
  drawBanners(list);
  if (keep) {
    const row = [...list.children].find(
      (r) => r.dataset.kind + r.dataset.date === keep[0],
    );
    if (row) list.scrollTop = row.offsetTop - keep[1];
  } else {
    const to = scheduleIndex(list, target)?.offsetTop ?? 0;
    const prev =
      from && [...list.children].find((r) => r.dataset.date === from);
    if (prev) {
      // Scroll over from where the list was, so you see which way it went.
      list.scrollTop = prev.offsetTop;
      list.scrollTo({ top: to, behavior: "smooth" });
    } else list.scrollTop = to;
  }
  onScheduleScroll.call(list);
}
/** The row for [date], or the month banner and week label sitting directly above it. */
function scheduleIndex(list, date) {
  const key = dayKey(date);
  let row = [...list.children].find(
    (r) => r.dataset.kind === "d" && r.dataset.date >= key,
  );
  if (!row) return null;
  const month = row.dataset.date.slice(0, 7);
  while (row.previousElementSibling) {
    const above = row.previousElementSibling;
    if (
      above.dataset.kind === "w" ||
      (above.dataset.kind === "m" && above.dataset.date.slice(0, 7) === month)
    )
      row = above;
    else break;
  }
  return row;
}
function firstVisibleRow(list) {
  return [...list.children].find(
    (r) => r.offsetTop + r.offsetHeight > list.scrollTop + 1,
  );
}
function onScheduleScroll() {
  if (!this.isConnected || !this.clientHeight) return;
  const row = firstVisibleRow(this);
  if (row) {
    const d = fromDayKey(row.dataset.date);
    visible = [d, d];
    updateTitle();
  }
  // The list grows as it's scrolled, up to five years out.
  if (
    this.scrollTop + this.clientHeight > this.scrollHeight - 800 &&
    sched.end < listLimit(sched.anchor) &&
    !this.dataset.growing
  ) {
    this.dataset.growing = "1";
    sched.end = endOfMonth(addMonths(sched.end, 6));
    requestAnimationFrame(
      () => this.isConnected && prefs.view === "SCHEDULE" && renderSchedule(false),
    );
  }
}
function dayRow(date, list) {
  const now = new Date();
  const isToday = sameDay(date, now);
  const cards = [];
  if (!list.length)
    cards.push(
      h("p", {
        class: "nothing body-medium",
        text: isToday ? "Nothing planned today" : "Nothing planned",
      }),
    );
  // Untimed items and anything already started sit above the line; the rest below it.
  let nowAt = -1;
  if (isToday) {
    nowAt = list.findIndex((o) => !o.allDay && o.start > now);
    if (nowAt < 0) nowAt = list.length;
  }
  const nowLine = () => h("div", { class: "now-line", "aria-hidden": "true" });
  list.forEach((o, i) => {
    if (i === nowAt) cards.push(nowLine());
    let text;
    if (o.isTask && o.allDay) text = "Task";
    else if (o.isTask) text = `Task, due ${time(o.start)}`;
    else if (o.allDay) text = "All day";
    else if (sameDay(o.startDate, o.lastDate)) text = occurrenceTime(o);
    else if (sameDay(date, o.startDate)) text = `From ${time(o.start)}`;
    else if (sameDay(date, o.lastDate)) text = `Until ${time(o.end)}`;
    else text = "All day";
    cards.push(eventCard(o, text, o.event.location, () => toggleTask(o)));
  });
  if (isToday && nowAt === list.length) cards.push(nowLine());
  return h(
    "div",
    { class: "day", "data-date": dayKey(date), "data-kind": "d" },
    h(
      "button",
      {
        type: "button",
        class: "date" + (isToday ? " today" : ""),
        "aria-label": dayLong(date),
        onclick: () => openDay(date),
      },
      h("span", { class: "label-small", text: weekdayShort(date) }),
      dayNumber(date, 34, 18),
    ),
    h("div", { class: "cards" }, cards),
  );
}

/* ---------- Month banners, ported from ui/main/MonthBanner.kt ---------- */
function banner(month) {
  return h(
    "div",
    { class: "banner", "data-date": dayKey(month), "data-kind": "m" },
    h("canvas", { "aria-hidden": "true" }),
    h("h3", { text: monthYearFull(month) }),
  );
}
/** kotlin.random.Random(seed), an XorWow generator, so each month gets the same art as on Android. */
function kotlinRandom(seed) {
  let x = seed | 0,
    y = seed >> 31,
    z = 0,
    w = 0,
    v = ~seed,
    addend = (seed << 10) ^ (y >>> 4);
  const nextInt = () => {
    let t = x;
    t ^= t >>> 2;
    x = y;
    y = z;
    z = w;
    const v0 = v;
    w = v0;
    t = t ^ (t << 1) ^ v0 ^ (v0 << 4);
    v = t;
    addend = (addend + 362437) | 0;
    return (t + addend) | 0;
  };
  for (let i = 0; i < 64; i++) nextInt();
  return () => (nextInt() >>> 8) / 16777216;
}
function drawBanners(root) {
  for (const div of root.querySelectorAll(".banner")) drawBanner(div);
}
function drawBanner(div) {
  const canvas = div.firstChild,
    dpr = devicePixelRatio || 1,
    w = div.clientWidth,
    h = div.clientHeight;
  if (!w) return;
  canvas.width = w * dpr;
  canvas.height = h * dpr;
  const g = canvas.getContext("2d");
  g.scale(dpr, dpr);
  const date = fromDayKey(div.dataset.date),
    m = date.getMonth(),
    random = kotlinRandom(date.getFullYear() * 12 + m + 1);
  const rect = (c, x = 0, y = 0, rw = w, rh = h) => {
    g.fillStyle = c;
    g.fillRect(x, y, rw, rh);
  };
  const circle = (c, r, x, y) => {
    g.fillStyle = c;
    g.beginPath();
    g.arc(x, y, r, 0, Math.PI * 2);
    g.fill();
  };
  const hill = (x, y, r, c) => circle(c, r, w * x, h * y);
  if (m === 11 || m < 2) {
    rect("#D7E7F7");
    hill(0.3 + random() * 0.1, 1.5, h * 0.85, "#EAF2FB");
    hill(0.82, 1.55, h * 0.9, "#FFFFFF");
    for (const [x, top] of [
      [0.7, 0.42],
      [0.78, 0.3],
      [0.88, 0.38],
    ]) {
      const cx = w * (x + (random() - 0.5) * 0.03),
        base = h * 0.82;
      g.fillStyle = "#2F6B57";
      g.beginPath();
      g.moveTo(cx, h * top);
      g.lineTo(cx + h * 0.13, base);
      g.lineTo(cx - h * 0.13, base);
      g.closePath();
      g.fill();
    }
    for (let i = 0; i < 22; i++)
      circle(
        `rgba(255,255,255,${0.6 + random() * 0.4})`,
        1.5 + random() * 2,
        random() * w,
        random() * h * 0.9,
      );
  } else if (m < 5) {
    rect("#E3F4E8");
    circle("#FFE08A", h * 0.17, w * (0.8 + random() * 0.08), h * 0.3);
    hill(0.25, 1.55, h * 0.9, "#A9DDB2");
    hill(0.78, 1.65, h * 0.95, "#7CC795");
    const petals = ["#F49CBB", "#FFFFFF", "#FFCF5C"];
    for (let i = 0; i < 14; i++) {
      const x = w * (0.45 + random() * 0.55),
        y = h * (0.78 + random() * 0.18),
        r = 2.5 + random() * 1.5;
      circle(petals[i % 3], r, x, y);
      circle("#F2A541", r * 0.4, x, y);
    }
  } else if (m < 8) {
    rect("#FFEFC9");
    const sx = w * (0.78 + random() * 0.1),
      sy = h * 0.36;
    circle("#FFDDA0", h * 0.3, sx, sy);
    circle("#FFB74D", h * 0.2, sx, sy);
    rect("#4FB3E8", 0, h * 0.68, w, h * 0.32);
    g.strokeStyle = "rgba(255,255,255,.8)";
    g.lineWidth = 2;
    for (let i = 0; i < 6; i++) {
      const x = w * (0.35 + random() * 0.6),
        y = h * (0.76 + random() * 0.18);
      g.beginPath();
      g.moveTo(x, y);
      g.quadraticCurveTo(x + 6, y - 4, x + 12, y);
      g.quadraticCurveTo(x + 18, y + 4, x + 24, y);
      g.stroke();
    }
    hill(0.12, 1.3, h * 0.6, "#F3D193");
  } else {
    rect("#FDE6D2");
    circle("#F9C87A", h * 0.15, w * (0.62 + random() * 0.08), h * 0.32);
    hill(0.3, 1.6, h * 0.95, "#EDB083");
    hill(0.88, 1.5, h * 0.8, "#CF7B4E");
    const tx = w * 0.86;
    rect("#6D4030", tx - 2, h * 0.42, 4, h * 0.3);
    circle("#D9603B", h * 0.2, tx, h * 0.36);
    circle("#E98A4C", h * 0.12, tx - h * 0.12, h * 0.46);
    const leaves = ["#D9603B", "#F2A541", "#B5462B"];
    for (let i = 0; i < 12; i++) {
      const x = w * (0.55 + random() * 0.25),
        y = h * (0.12 + random() * 0.6);
      g.save();
      g.translate(x, y);
      g.rotate(random() * Math.PI);
      g.fillStyle = leaves[i % 3];
      g.beginPath();
      g.ellipse(0, 0, 4, 2, 0, 0, Math.PI * 2);
      g.fill();
      g.restore();
    }
  }
}

/* ---------- Lanes, as in ui/main/Paging.kt ---------- */
function assignLanes(list, firstDay, count) {
  const lastDay = addDays(firstDay, count - 1);
  const laneEnds = [];
  return list
    .filter((o) => o.startDate <= lastDay && o.lastDate >= firstDay)
    .map((o) => ({
      o,
      start: daysBetween(firstDay, maxDate(o.startDate, firstDay)),
      end: daysBetween(firstDay, minDate(o.lastDate, lastDay)),
    }))
    .sort(
      (a, b) =>
        a.start - b.start ||
        !a.o.inAllDayLane - !b.o.inAllDayLane ||
        b.end - b.start - (a.end - a.start) ||
        occurrenceOrder(a.o, b.o),
    )
    .map((item) => {
      let lane = laneEnds.findIndex((e) => e < item.start);
      if (lane < 0) {
        lane = laneEnds.length;
        laneEnds.push(item.end);
      } else laneEnds[lane] = item.end;
      return { ...item, lane };
    });
}

/* ---------- Month, as in ui/main/MonthView.kt ---------- */
const DATE_HEADER = 26,
  CHIP = 17,
  CHIP_GAP = 1;
function renderMonth(slide) {
  const month = startOfMonth(selected);
  visible = [month, endOfMonth(month)];
  const first = weekStartOf(month);
  const weeks = Math.floor(daysBetween(first, endOfMonth(month)) / 7) + 1;
  const all = occurrences(first, addDays(first, weeks * 7 - 1));
  // Wide screens follow Google Calendar on the web: weekday names in the first row of cells,
  // timed events as a dot and a time, and roomier chips.
  const web = wide.matches;
  const header = web ? 26 : DATE_HEADER,
    chip = web ? 22 : CHIP,
    gap = web ? 2 : CHIP_GAP;
  const page = h("div", { class: "month-page" + slideClass(slide) });
  $("view").replaceChildren(
    h(
      "div",
      { class: "month" + (web ? " web" : "") },
      !web && weekdayHeader(),
      page,
    ),
  );
  const weekHeight = page.clientHeight / weeks;
  for (let wk = 0; wk < weeks; wk++) {
    const firstDay = addDays(first, wk * 7);
    const top = web && wk === 0 ? header + 18 : header;
    const maxLanes = Math.max(
      1,
      Math.floor((weekHeight - top - 4) / (chip + gap)),
    );
    const items = assignLanes(all, firstDay, 7);
    const laneCount = items.reduce((n, i) => Math.max(n, i.lane + 1), 0);
    // When everything doesn't fit, the last visible lane becomes a "+N" summary.
    const shown = laneCount <= maxLanes ? laneCount : maxLanes - 1;
    const week = h(
      "div",
      { class: "month-week" },
      h(
        "div",
        { class: "month-days" },
        [...Array(7)].map((_, d) => {
          const date = addDays(firstDay, d);
          const number = dayNumber(date, web ? 24 : 22, 12, {
            dim: !sameMonth(date, month),
          });
          if (!web)
            return h(
              "button",
              {
                type: "button",
                "aria-label": dayLong(date),
                onclick: () => openDay(date),
              },
              number,
            );
          // On the web an empty part of a day starts a new event there; the date opens the day.
          return h(
            "div",
            {
              class: "month-cell",
              onclick: (e) =>
                e.target === e.currentTarget && openQuickAdd({ date }),
            },
            wk === 0 &&
              h("span", {
                class: "month-weekday",
                text: weekdayShort(date).toUpperCase(),
              }),
            h(
              "button",
              {
                type: "button",
                class: "month-date",
                "aria-label": dayLong(date),
                onclick: () => openDay(date),
              },
              number,
            ),
          );
        }),
      ),
    );
    for (const item of items.filter((i) => i.lane < shown)) {
      const o = item.o;
      const bar =
        web && !o.inAllDayLane
          ? timedChip(o)
          : eventBar(o, web ? 12 : 10, web ? () => openDetail(o) : null);
      Object.assign(bar.style, {
        left: `calc(${(item.start * 100) / 7}% + ${web ? 4 : 1.5}px)`,
        width: `calc(${((item.end - item.start + 1) * 100) / 7}% - ${web ? 8 : 3}px)`,
        top: top + (chip + gap) * item.lane + "px",
        height: chip + "px",
        pointerEvents: web ? "" : "none",
      });
      week.append(bar);
    }
    if (shown < laneCount)
      for (let col = 0; col < 7; col++) {
        const hidden = items.filter(
          (i) => i.lane >= shown && col >= i.start && col <= i.end,
        ).length;
        if (!hidden) continue;
        const date = addDays(firstDay, col);
        week.append(
          h(web ? "button" : "span", {
            type: web ? "button" : null,
            class: "more",
            text: web ? `${hidden} more` : "+" + hidden,
            onclick: web ? () => openDay(date) : null,
            style: {
              left: `calc(${(col * 100) / 7}% + ${web ? 4 : 0}px)`,
              width: web ? `calc(${100 / 7}% - 8px)` : 100 / 7 + "%",
              top: top + (chip + gap) * shown + "px",
            },
          }),
        );
      }
    page.append(week);
  }
}
/** A timed event in the web month grid: a colored dot, its start time and title. */
function timedChip(o) {
  return h(
    "button",
    {
      type: "button",
      class:
        "timed-chip" +
        (isPast(o) ? " past" : "") +
        (o.completed ? " done" : ""),
      onclick: () => openDetail(o),
    },
    o.isTask
      ? (() => {
          const svg = icon(o.completed ? I.checkCircle : I.circle);
          svg.style.color = css(o.color);
          return svg;
        })()
      : h("span", { class: "dot", style: { background: css(o.color) } }),
    !o.allDay && h("span", { class: "chip-time", text: time(o.start) }),
    h("span", { class: "bar-title", text: o.event.title || "(No title)" }),
  );
}

/* ---------- Day, 3-day and week grids, as in ui/main/TimeGridView.kt ---------- */
const HOUR = 56,
  GUTTER = 52,
  LANE = 22,
  COLLAPSED_LANES = 2,
  MIN_BLOCK = 22;
const grid = { anchor: today(), scrollTop: null, expanded: false, draft: null };
const gridDays = () => ({ DAY: 1, THREE_DAY: 3, WEEK: 7 })[prefs.view];
function gridStart(days = gridDays(), date = selected) {
  if (days === 7) return weekStartOf(date);
  if (days === 1) return date;
  return addDays(
    grid.anchor,
    Math.floor(daysBetween(grid.anchor, date) / 3) * 3,
  );
}
/** Positions overlapping events side by side, as in ui/main/TimeGridLayout.kt. */
function layoutDay(list, date) {
  const items = list
    .map((o) => {
      const start = o.startDate < date ? 0 : minutesOf(o.start);
      const rawEnd = startOfDay(o.end) > date ? 1440 : minutesOf(o.end);
      const end = Math.min(Math.max(rawEnd, start + MIN_BLOCK), 1440);
      return {
        o,
        start: Math.min(start, end - MIN_BLOCK),
        end,
        column: 0,
        columns: 1,
        span: 1,
      };
    })
    .sort((a, b) => a.start - b.start || b.end - a.end);
  const result = [];
  let cluster = [],
    columnEnds = [],
    clusterEnd = -1;
  const flush = () => {
    for (const item of cluster) {
      item.columns = columnEnds.length;
      // Let an event widen into free columns to its right.
      let span = 1;
      while (
        item.column + span < columnEnds.length &&
        !cluster.some(
          (x) =>
            x.column === item.column + span &&
            x.start < item.end &&
            x.end > item.start,
        )
      )
        span++;
      item.span = span;
    }
    result.push(...cluster);
    cluster = [];
    columnEnds = [];
    clusterEnd = -1;
  };
  for (const item of items) {
    if (cluster.length && item.start >= clusterEnd) flush();
    const column = columnEnds.findIndex((e) => e <= item.start);
    if (column < 0) {
      item.column = columnEnds.length;
      columnEnds.push(item.end);
    } else {
      item.column = column;
      columnEnds[column] = item.end;
    }
    cluster.push(item);
    clusterEnd = Math.max(clusterEnd, item.end);
  }
  flush();
  return result;
}
function gridBlock(p, columnWidth) {
  const height = ((p.end - p.start) * HOUR) / 60;
  const compact = columnWidth / p.columns < 64;
  const titleSize = compact ? 11 : 13,
    lineHeight = titleSize + 3;
  const showTime = height >= (compact ? 56 : 40);
  const titleLines = Math.min(
    4,
    Math.max(
      1,
      Math.floor((height - 6 - (showTime ? lineHeight : 0)) / lineHeight),
    ),
  );
  const check = p.o.isTask && icon(p.o.completed ? I.checkCircle : I.circle);
  if (check) {
    check.style.width = check.style.height = titleSize + 1 + "px";
  }
  const block = h(
    "button",
    {
      type: "button",
      class:
        "block" + (compact ? " compact" : "") + (p.o.completed ? " done" : ""),
      onclick: (e) => {
        e.stopPropagation();
        openDetail(p.o);
      },
      style: {
        left: `calc(${(p.column * 100) / p.columns}% + 1px)`,
        width: `calc(${(p.span * 100) / p.columns}% - 3px)`,
        top: (p.start * HOUR) / 60 + "px",
        height: height - 1 + "px",
        "font-size": titleSize + "px",
        "line-height": lineHeight + "px",
      },
    },
    h(
      "span",
      { class: "row" },
      check,
      h("strong", {
        text: p.o.event.title || "(No title)",
        style: { "-webkit-line-clamp": String(titleLines) },
      }),
      importanceDot(p.o.event.importance),
    ),
    showTime &&
      h("small", {
        text: compact ? time(p.o.start) : timeRange(p.o.start, p.o.end),
        style: { "font-size": titleSize - 1 + "px" },
      }),
  );
  return paint(block, p.o);
}
function renderGrid(slide) {
  const days = gridDays();
  const start = gridStart();
  const dates = [...Array(days)].map((_, i) => addDays(start, i));
  visible = [start, dates[days - 1]];
  const all = occurrences(start, dates[days - 1]);
  const now = new Date();
  const view = $("view");
  const oldScroll = view.querySelector(".grid-scroll");
  if (oldScroll) grid.scrollTop = oldScroll.scrollTop;

  // A lone date centers on the screen; several dates sit over their grid columns.
  const head = h(
    "div",
    { class: "grid-head" },
    days > 1 && h("span", { class: "gutter-space" }),
    dates.map((date) =>
      h(
        "button",
        {
          type: "button",
          class: sameDay(date, now) ? "today" : null,
          disabled: days === 1,
          "aria-label": dayLong(date),
          onclick: () => openDay(date),
        },
        h("span", { class: "label-small", text: weekdayShort(date) }),
        dayNumber(date, 32, 16),
      ),
    ),
  );

  // All-day lanes, collapsing to two lanes and a "+N" row when there are many.
  const laneItems = assignLanes(
    all.filter((o) => o.inAllDayLane),
    start,
    days,
  );
  let allDay = null;
  if (laneItems.length) {
    const laneCount = Math.max(...laneItems.map((i) => i.lane)) + 1;
    const collapsible = laneCount > COLLAPSED_LANES + 1;
    const collapsed = collapsible && !grid.expanded;
    const shownLanes = collapsed ? COLLAPSED_LANES + 1 : laneCount;
    const toggle = () => {
      grid.expanded = !grid.expanded;
      render();
    };
    const lanes = h("div", {
      class: "lanes",
      style: { height: LANE * shownLanes + 4 + "px" },
    });
    for (const item of laneItems.filter(
      (i) => !collapsed || i.lane < COLLAPSED_LANES,
    )) {
      const bar = eventBar(item.o, 12, () => openDetail(item.o));
      Object.assign(bar.style, {
        left: `calc(${(item.start * 100) / days}% + 1px)`,
        width: `calc(${((item.end - item.start + 1) * 100) / days}% - 2px)`,
        top: LANE * item.lane + 1 + "px",
        height: LANE - 2 + "px",
      });
      lanes.append(bar);
    }
    if (collapsed)
      dates.forEach((_, col) => {
        const hidden = laneItems.filter(
          (i) => i.lane >= COLLAPSED_LANES && col >= i.start && col <= i.end,
        ).length;
        if (hidden)
          lanes.append(
            h("span", {
              class: "more",
              text: "+" + hidden,
              onclick: toggle,
              style: {
                left: (col * 100) / days + "%",
                width: 100 / days + "%",
                top: LANE * COLLAPSED_LANES + "px",
              },
            }),
          );
      });
    allDay = h(
      "div",
      { class: "allday" },
      h(
        "span",
        { class: "gutter-space" },
        collapsible &&
          iconButton(
            I.down,
            grid.expanded ? "Collapse all-day events" : "Expand all-day events",
            toggle,
            { "aria-expanded": grid.expanded },
          ),
      ),
      lanes,
    );
  }

  const hours = h(
    "div",
    { class: "hours label-small" },
    [...Array(23)].map((_, i) =>
      h("span", {
        text: hourLabel(i + 1),
        style: { top: HOUR * (i + 1) - 8 + "px" },
      }),
    ),
  );
  const columns = h(
    "div",
    { class: "columns" },
    [...Array(23)].map((_, i) =>
      h("span", { class: "hour-line", style: { top: HOUR * (i + 1) + "px" } }),
    ),
  );
  const columnWidth = (view.clientWidth - GUTTER) / days;
  for (const date of dates) {
    const column = h("div", {
      class: "column",
      "data-date": dayKey(date),
      onclick: (e) => {
        const hour = Math.min(23, Math.max(0, Math.floor(e.offsetY / HOUR)));
        grid.draft = atMinute(date, hour * 60);
        renderDraft(columns, columnWidth);
      },
    });
    const timed = all.filter((o) => !o.inAllDayLane && occursOn(o, date));
    for (const p of layoutDay(timed, date))
      column.append(gridBlock(p, columnWidth));
    if (sameDay(date, now))
      column.append(
        h("div", {
          class: "grid-now",
          style: { top: (minutesOf(now) * HOUR) / 60 + "px" },
        }),
      );
    columns.append(column);
  }
  const scroll = h(
    "div",
    {
      class: "grid-scroll",
      onscroll: () => (grid.scrollTop = scroll.scrollTop),
    },
    h("div", { class: "grid-body" }, hours, columns),
  );
  view.replaceChildren(
    h("div", { class: "grid" + slideClass(slide) }, head, allDay, scroll),
  );
  // Opens an hour before now on today, or at 7 AM, and keeps that across pages.
  if (grid.scrollTop == null) {
    const hour = dates.some((d) => sameDay(d, now))
      ? Math.min(15, Math.max(0, now.getHours() - 1))
      : 7;
    grid.scrollTop = hour * HOUR;
  }
  scroll.scrollTop = grid.scrollTop;
  renderDraft(columns, columnWidth);
}
/** A tapped hour shows a "New event" slot that glides to the next hour tapped. */
function renderDraft(columns, columnWidth) {
  const old = columns.querySelector(".draft");
  const slot = grid.draft;
  const column =
    slot && columns.querySelector(`.column[data-date="${dayKey(slot)}"]`);
  if (!column) return old?.remove();
  const top = slot.getHours() * HOUR + 1 + "px";
  if (old && old.parentElement === column) {
    old.style.top = top;
    return;
  }
  old?.remove();
  const narrow = columnWidth < 96;
  column.append(
    h(
      "button",
      {
        type: "button",
        class: "draft" + (narrow ? " narrow" : ""),
        "aria-label": "New event",
        style: { top },
        onclick: (e) => {
          e.stopPropagation();
          const at = grid.draft;
          grid.draft = null;
          renderDraft(columns, columnWidth);
          openEditor({ date: startOfDay(at), minute: minutesOf(at) });
        },
      },
      icon(I.add),
      !narrow && h("span", { text: "New event" }),
    ),
  );
}

/* ---------- Paging between months and periods ---------- */
function shiftPeriod(dir) {
  if (prefs.view === "SCHEDULE") return;
  let range;
  if (prefs.view === "MONTH") {
    const m = addMonths(selected, dir);
    range = [m, endOfMonth(m)];
  } else {
    const days = gridDays();
    const s = addDays(gridStart(), dir * days);
    range = [s, addDays(s, days - 1)];
  }
  // Swiping to another period moves the selection into it, onto today when it's there.
  const now = today();
  selectDate(now >= range[0] && now <= range[1] ? now : range[0]);
  grid.draft = null;
  render({ slide: dir });
}
let swipe = null,
  swiped = false,
  wheelAt = 0,
  wheelSum = 0;
$("view").addEventListener("pointerdown", (e) => {
  if (e.pointerType !== "mouse") swipe = { x: e.clientX, y: e.clientY };
});
$("view").addEventListener("pointerup", (e) => {
  if (!swipe) return;
  const dx = e.clientX - swipe.x,
    dy = e.clientY - swipe.y;
  swipe = null;
  if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) {
    swiped = true;
    shiftPeriod(dx < 0 ? 1 : -1);
  }
});
$("view").addEventListener(
  "click",
  (e) => {
    if (!swiped) return;
    swiped = false;
    e.stopPropagation();
    e.preventDefault();
  },
  true,
);
// Horizontal trackpad swipes page too.
$("view").addEventListener(
  "wheel",
  (e) => {
    if (prefs.view === "SCHEDULE" || Math.abs(e.deltaX) <= Math.abs(e.deltaY))
      return;
    e.preventDefault();
    const at = Date.now();
    if (at < wheelAt) return;
    if (at - wheelAt > 300) wheelSum = 0;
    wheelAt = at;
    wheelSum += e.deltaX;
    if (Math.abs(wheelSum) > 80) {
      shiftPeriod(wheelSum > 0 ? 1 : -1);
      wheelSum = 0;
      wheelAt = at + 500;
    }
  },
  { passive: false },
);
addEventListener("keydown", (e) => {
  if (
    !token ||
    document.querySelector("dialog[open]") ||
    e.target.closest?.("input, textarea")
  )
    return;
  if (e.key === "Escape" && $("drawer").classList.contains("open"))
    setDrawer(false);
  else if (e.key === "Escape" && pickerOpen) {
    pickerOpen = false;
    render();
  } else if (e.key === "Escape" && returnView) {
    const back = returnView;
    returnView = null;
    setView(back);
  } else if (e.key === "ArrowLeft") shiftPeriod(-1);
  else if (e.key === "ArrowRight") shiftPeriod(1);
});
addEventListener("resize", () => {
  if (!token) return;
  if (prefs.view === "SCHEDULE") drawBanners($("view"));
  else render();
});

/* ---------- Alert dialogs, as in ui/common/Dialogs.kt ---------- */
let alertDone = null;
/** Shows the alert dialog and resolves with whatever [finish] is called with. */
function showAlert(title, body, buttons, { form = false, cls = "" } = {}) {
  const dialog = $("alert");
  dialog.className = "alert" + (cls ? " " + cls : "");
  alertDone?.(undefined);
  let finish;
  const promise = new Promise((resolve) => {
    finish = alertDone = (value) => {
      if (alertDone === finish) alertDone = null;
      if (dialog.open) dialog.close();
      resolve(value);
    };
  });
  dialog.replaceChildren(
    ...(title ? [h("h2", { class: "headline-small", text: title })] : []),
    h(
      "div",
      { class: "alert-body body-medium" + (form ? " form" : "") },
      typeof body === "function" ? body(finish) : body,
    ),
    h("div", { class: "actions" }, buttons(finish)),
  );
  dialog.oncancel = (e) => {
    e.preventDefault();
    finish(undefined);
  };
  dialog.onclick = (e) => {
    // A tap outside the dialog dismisses it.
    const r = dialog.getBoundingClientRect();
    if (
      e.target === dialog &&
      (e.clientX < r.left ||
        e.clientX > r.right ||
        e.clientY < r.top ||
        e.clientY > r.bottom)
    )
      finish(undefined);
  };
  showModal(dialog);
  return promise;
}
/**
 * Opens a dialog with focus on the dialog itself, so the first control doesn't show a focus ring
 * after a tap. Keyboard users tab in from there.
 */
function showModal(dialog) {
  if (!dialog.open) dialog.showModal();
  dialog.tabIndex = -1;
  dialog.focus({ preventScroll: true });
}

/* ---------- Date and time pickers, as Material 3's DatePickerDialog and TimePicker ---------- */
/** Resolves with the picked date, or undefined when cancelled. */
function datePicker(initial, title = "Select date") {
  let picked = startOfDay(initial);
  let month = startOfMonth(picked);
  let choosingYear = false;
  const body = h("div", { class: "date-picker" });
  const draw = (slide = 0) => {
    const first = weekStartOf(month);
    const weeks = Math.floor(daysBetween(first, endOfMonth(month)) / 7) + 1;
    const headline = fmt(picked, {
      weekday: "short",
      month: "short",
      day: "numeric",
    });
    const grid = choosingYear
      ? h(
          "div",
          { class: "years" },
          [...Array(201)].map((_, i) => {
            const year = 1900 + i;
            return h("button", {
              type: "button",
              class: year === month.getFullYear() ? "selected" : null,
              text: year,
              onclick: () => {
                month = new Date(year, month.getMonth(), 1);
                choosingYear = false;
                draw();
              },
            });
          }),
        )
      : h(
          "div",
          { class: "days" + slideClass(slide) },
          weekdayHeader(),
          h(
            "div",
            { class: "day-grid" },
            [...Array(weeks * 7)].map((_, i) => {
              const date = addDays(first, i);
              if (!sameMonth(date, month)) return h("span");
              const isToday = sameDay(date, today());
              const chosen = sameDay(date, picked);
              return h("button", {
                type: "button",
                class: (chosen ? "chosen" : "") + (isToday ? " today" : ""),
                "aria-label": dayLong(date),
                "aria-pressed": chosen,
                text: date.getDate(),
                onclick: () => {
                  picked = date;
                  draw();
                },
              });
            }),
          ),
        );
    body.replaceChildren(
      h("p", { class: "picker-label label-large", text: title }),
      h("p", { class: "picker-headline", text: headline }),
      h("hr"),
      h(
        "div",
        { class: "month-row" },
        h(
          "button",
          {
            type: "button",
            class: "year-toggle",
            "aria-expanded": choosingYear,
            onclick: () => {
              choosingYear = !choosingYear;
              draw();
            },
          },
          monthYearFull(month),
          icon(I.down),
        ),
        !choosingYear &&
          h(
            "span",
            {},
            iconButton(I.left, "Previous month", () => {
              month = addMonths(month, -1);
              draw(-1);
            }),
            iconButton(I.right, "Next month", () => {
              month = addMonths(month, 1);
              draw(1);
            }),
          ),
      ),
      grid,
    );
    if (choosingYear)
      body
        .querySelector(".years .selected")
        ?.scrollIntoView({ block: "center" });
  };
  draw();
  return showAlert(
    null,
    body,
    (finish) => [
      textButton("Cancel", () => finish(undefined)),
      textButton("OK", () => finish(picked)),
    ],
    { cls: "picker" },
  );
}

/** Resolves with the picked minutes after midnight, or undefined when cancelled. */
function timePicker(initialMinutes) {
  let hour = Math.floor(initialMinutes / 60),
    minute = initialMinutes % 60,
    mode = "hour",
    keyboard = false;
  const use24h = prefs.use24h;
  const body = h("div", { class: "time-picker" });
  const shownHour = () => (use24h ? hour : hour % 12 || 12);
  const setPm = (pm) => {
    if (pm && hour < 12) hour += 12;
    if (!pm && hour >= 12) hour -= 12;
    draw();
  };
  // The dial: 12 outer positions, plus an inner ring for 24-hour time.
  const SIZE = 256,
    C = SIZE / 2,
    OUTER = 100,
    INNER = 64;
  const position = (index, radius) => {
    const angle = (index / 12) * 2 * Math.PI - Math.PI / 2;
    return [C + radius * Math.cos(angle), C + radius * Math.sin(angle)];
  };
  const fromPointer = (e, dial) => {
    const r = dial.getBoundingClientRect();
    const x = e.clientX - r.left - C,
      y = e.clientY - r.top - C;
    const angle =
      (Math.atan2(y, x) + Math.PI / 2 + 2 * Math.PI) % (2 * Math.PI);
    if (mode === "minute") {
      minute = Math.round((angle / (2 * Math.PI)) * 60) % 60;
      return;
    }
    const slot = Math.round((angle / (2 * Math.PI)) * 12) % 12;
    const inner = use24h && Math.hypot(x, y) < (OUTER + INNER) / 2;
    if (use24h)
      hour = inner ? (slot === 0 ? 0 : slot + 12) : slot === 0 ? 12 : slot;
    else hour = (slot === 0 ? 0 : slot) + (hour >= 12 ? 12 : 0);
  };
  /** Draws the hand and numbers into [el], which keeps its pointer handlers while dragging. */
  const paintDial = (el) => {
    const labels = [];
    let hand;
    if (mode === "minute") {
      for (let i = 0; i < 12; i++)
        labels.push({
          text: pad(i * 5),
          at: position(i, OUTER),
          on: minute === i * 5,
        });
      hand = { at: position(minute / 5, OUTER), label: minute % 5 === 0 };
    } else {
      for (let i = 0; i < 12; i++) {
        const value = use24h ? (i === 0 ? 12 : i) : i === 0 ? 12 : i;
        labels.push({
          text: String(value),
          at: position(i, OUTER),
          on: use24h ? hour === (i === 0 ? 12 : i) : shownHour() === value,
        });
        if (use24h)
          labels.push({
            text: i === 0 ? "00" : String(i + 12),
            at: position(i, INNER),
            on: hour === (i === 0 ? 0 : i + 12),
            inner: true,
          });
      }
      const inner = use24h && (hour === 0 || hour > 12);
      hand = { at: position(hour % 12, inner ? INNER : OUTER), label: true };
    }
    const svg = document.createElementNS(SVG, "svg");
    svg.setAttribute("viewBox", `0 0 ${SIZE} ${SIZE}`);
    svg.classList.add("dial-hand");
    const line = document.createElementNS(SVG, "line");
    Object.entries({ x1: C, y1: C, x2: hand.at[0], y2: hand.at[1] }).forEach(
      ([k, v]) => line.setAttribute(k, v),
    );
    const dot = document.createElementNS(SVG, "circle");
    Object.entries({ cx: C, cy: C, r: 4 }).forEach(([k, v]) =>
      dot.setAttribute(k, v),
    );
    const knob = document.createElementNS(SVG, "circle");
    Object.entries({ cx: hand.at[0], cy: hand.at[1], r: 24 }).forEach(
      ([k, v]) => knob.setAttribute(k, v),
    );
    svg.append(line, dot, knob);
    if (!hand.label) {
      const center = document.createElementNS(SVG, "circle");
      Object.entries({
        cx: hand.at[0],
        cy: hand.at[1],
        r: 2,
        class: "knob-dot",
      }).forEach(([k, v]) => center.setAttribute(k, v));
      svg.append(center);
    }
    el.replaceChildren(
      svg,
      ...labels.map((l) =>
        h("span", {
          class: (l.on ? "on" : "") + (l.inner ? " inner" : ""),
          text: l.text,
          style: { left: l.at[0] + "px", top: l.at[1] + "px" },
        }),
      ),
    );
  };
  const dial = () => {
    const el = h("div", { class: "dial", "aria-hidden": "true" });
    paintDial(el);
    let dragging = false;
    el.onpointerdown = (e) => {
      dragging = true;
      el.setPointerCapture(e.pointerId);
      fromPointer(e, el);
      draw(true);
    };
    el.onpointermove = (e) => {
      if (!dragging) return;
      fromPointer(e, el);
      draw(true);
    };
    el.onpointerup = () => {
      if (!dragging) return;
      dragging = false;
      // Picking the hour moves on to the minutes, as on Android.
      if (mode === "hour") mode = "minute";
      draw();
    };
    return el;
  };
  const field = (which) => {
    const value = which === "hour" ? pad(shownHour()) : pad(minute);
    if (!keyboard)
      return h("button", {
        type: "button",
        class: "time-field" + (mode === which ? " on" : ""),
        "aria-label": which === "hour" ? "Hour" : "Minute",
        text: which === "hour" && !use24h ? String(shownHour()) : value,
        onclick: () => {
          mode = which;
          draw();
        },
      });
    const input = h("input", {
      class: "time-field" + (mode === which ? " on" : ""),
      inputmode: "numeric",
      maxlength: 2,
      "aria-label": which === "hour" ? "Hour" : "Minute",
    });
    input.value = value;
    input.onfocus = () => {
      mode = which;
      input.select();
      body
        .querySelectorAll(".time-field")
        .forEach((f) => f.classList.toggle("on", f === input));
    };
    input.oninput = () => {
      const n = parseInt(input.value, 10);
      if (Number.isNaN(n)) return;
      if (which === "minute") minute = Math.min(59, n);
      else if (use24h) hour = Math.min(23, n);
      else hour = (Math.min(12, Math.max(1, n)) % 12) + (hour >= 12 ? 12 : 0);
    };
    return input;
  };
  const draw = (quiet = false) => {
    if (quiet) {
      // While dragging, only the numbers and hand move.
      paintDial(body.querySelector(".dial"));
      body.querySelectorAll(".time-field").forEach((f, i) => {
        f.textContent =
          i === 0
            ? use24h
              ? pad(shownHour())
              : String(shownHour())
            : pad(minute);
      });
      return;
    }
    body.replaceChildren(
      h("p", {
        class: "picker-label label-medium",
        text: keyboard ? "Enter time" : "Select time",
      }),
      h(
        "div",
        { class: "time-row" },
        field("hour"),
        h("span", { class: "time-colon", text: ":" }),
        field("minute"),
        !use24h &&
          h(
            "div",
            { class: "ampm" },
            h("button", {
              type: "button",
              "aria-pressed": hour < 12,
              text: "AM",
              onclick: () => setPm(false),
            }),
            h("button", {
              type: "button",
              "aria-pressed": hour >= 12,
              text: "PM",
              onclick: () => setPm(true),
            }),
          ),
      ),
      keyboard
        ? h(
            "div",
            { class: "time-hints body-small variant" },
            h("span", { text: "Hour" }),
            h("span", { text: "Minute" }),
          )
        : dial(),
    );
  };
  draw();
  const toggle = iconButton(
    I.keyboard,
    "Use keyboard",
    () => {
      keyboard = !keyboard;
      toggle.replaceChildren(icon(keyboard ? I.clock : I.keyboard));
      toggle.title = toggle.ariaLabel = keyboard ? "Use clock" : "Use keyboard";
      draw();
      if (keyboard) body.querySelector("input")?.focus();
    },
    { class: "icon leading-action" },
  );
  return showAlert(
    null,
    body,
    (finish) => [
      toggle,
      textButton("Cancel", () => finish(undefined)),
      textButton("OK", () => {
        // A typed value that hasn't been applied yet still counts.
        body.querySelectorAll("input").forEach((input) => input.oninput());
        finish(hour * 60 + minute);
      }),
    ],
    { cls: "picker time" },
  );
}
const textButton = (label, onclick, disabled = false) =>
  h("button", {
    type: "button",
    class: "text",
    text: label,
    onclick,
    disabled,
  });
/** A single-choice list that closes as soon as an option is picked. */
function choose(title, options, selectedValue) {
  return showAlert(
    title,
    (finish) =>
      options.map((o) =>
        h(
          "button",
          {
            type: "button",
            class: "option",
            role: "radio",
            "aria-checked": o.value === selectedValue,
            onclick: () => finish(o.value),
          },
          h("span", { class: "radio" }),
          o.dot && h("span", { class: "dot", style: { background: o.dot } }),
          o.label,
        ),
      ),
    (finish) => [textButton("Cancel", () => finish(undefined))],
  );
}
function confirmAlert(title, text, confirmLabel) {
  return showAlert(title, text, (finish) => [
    textButton("Cancel", () => finish(false)),
    textButton(confirmLabel, () => finish(true)),
  ]).then(Boolean);
}
const SCOPES = [
  { value: "THIS", label: "This event" },
  { value: "FOLLOWING", label: "This and following events" },
  { value: "ALL", label: "All events" },
];

/* ---------- Pages ---------- */
function openPage(dialog) {
  if (!dialog.open) dialog.showModal();
}
function pageBar(navPath, navLabel, onNav, title, ...actions) {
  return h(
    "header",
    { class: "page-bar" },
    iconButton(navPath, navLabel, onNav),
    h("h2", { class: "title-large", text: title || "" }),
    actions,
  );
}

/* Event page, as in ui/event/EventDetailScreen.kt. On wide screens it opens as a card beside the
   clicked event, like Google Calendar's. */
let detailOf = null,
  clicked = null;
addEventListener(
  "click",
  (e) => {
    clicked = e.target;
    setTimeout(() => (clicked = null));
  },
  true,
);
function openDetail(o) {
  const dialog = $("detail"),
    anchor =
      clicked?.closest("#view") &&
      (clicked.closest("button, [role=button]") || clicked);
  detailOf = { id: o.record.id, start: o.start };
  dialog.classList.toggle("popover", wide.matches);
  renderDetail();
  openPage(dialog);
  dialog.style.left = dialog.style.top = "";
  if (wide.matches && anchor) placePopover(dialog, anchor);
}
/** Puts the card to the side of the event with more room, level with it and kept on screen. */
function placePopover(dialog, anchor) {
  const a = anchor.getBoundingClientRect(),
    w = dialog.offsetWidth,
    ht = dialog.offsetHeight,
    gap = 8,
    edge = 16;
  const right = innerWidth - a.right,
    left =
      right >= w + gap + edge || right >= a.left
        ? a.right + gap
        : a.left - gap - w;
  dialog.style.left =
    Math.max(edge, Math.min(left, innerWidth - w - edge)) + "px";
  dialog.style.top =
    Math.max(edge, Math.min(a.top, innerHeight - ht - edge)) + "px";
}
function renderDetail() {
  const dialog = $("detail");
  const o = detailOf && findOccurrence(detailOf.id, detailOf.start);
  // The event was deleted, here or on another device.
  if (!o) {
    if (dialog.open) dialog.close();
    return;
  }
  detailOf.start = o.start;
  const d = o.event,
    popover = dialog.classList.contains("popover");
  const closeMenu = () => dialog.querySelector(".menu")?.remove();
  const menuButton = iconButton(I.more, "More options", () => {
    if (dialog.querySelector(".menu")) return closeMenu();
    const rect = menuButton.getBoundingClientRect(),
      box = dialog.getBoundingClientRect();
    const item = (path, label, action) =>
      h(
        "button",
        {
          type: "button",
          role: "menuitem",
          onclick: () => {
            closeMenu();
            action();
          },
        },
        icon(path),
        label,
      );
    const menu = h(
      "div",
      {
        class: "menu",
        role: "menu",
        style: {
          top: rect.bottom - box.top + "px",
          right: box.right - rect.right + 4 + "px",
        },
      },
      item(I.share, "Share event", () => openShare(o)),
      item(I.copy, "Duplicate", () => duplicate(o)),
      !popover && item(I.delete, "Delete", () => deleteOccurrence(o)),
    );
    dialog.append(menu);
    menu.querySelector("button").focus();
  });
  dialog.onclick = (e) => {
    if (!e.target.closest(".menu") && !menuButton.contains(e.target))
      closeMenu();
    // A click on the backdrop lands on the dialog itself, outside its box.
    const box = dialog.getBoundingClientRect();
    if (
      popover &&
      e.target === dialog &&
      (e.clientX < box.left ||
        e.clientX > box.right ||
        e.clientY < box.top ||
        e.clientY > box.bottom)
    )
      dialog.close();
  };
  const row = (path, content, { tint, onclick, gap } = {}) => {
    const svg = icon(path);
    if (tint) svg.style.color = tint;
    return h(
      onclick ? "button" : "div",
      { type: onclick ? "button" : null, class: "detail-row", onclick },
      svg,
      h("div", { class: gap ? "gap" : null }, content),
    );
  };
  let [whenLine, secondLine] = occurrenceWhen(o);
  if (popover && secondLine && sameDay(o.startDate, o.lastDate)) {
    whenLine += "\u00a0\u00a0·\u00a0\u00a0" + secondLine;
    secondLine = null;
  }
  const seriesStart = startOfDay(wall(d.start, d.allDay));
  const editButton = iconButton(I.edit, "Edit", () =>
    openEditor({ occurrence: o }),
  );
  dialog.replaceChildren(
    popover
      ? h(
          "header",
          { class: "page-bar" },
          editButton,
          iconButton(I.delete, "Delete", () => deleteOccurrence(o)),
          iconButton(I.mail, "Email event", () => mailEvent(o)),
          menuButton,
          iconButton(I.close, "Close", () => dialog.close()),
        )
      : pageBar(I.close, "Close", () => dialog.close(), "", editButton, menuButton),
    h(
      "div",
      { class: "page-body detail-page" },
      h(
        "div",
        { class: "detail-head" },
        h("span", { class: "swatch", style: { background: css(o.color) } }),
        h(
          "div",
          {},
          h("h2", { class: "headline-small", text: d.title || "(No title)" }),
          o.isTask &&
            h("p", {
              class: "body-medium variant",
              text: o.completed ? "Task, completed" : "Task",
            }),
          h("p", { class: "body-large", text: whenLine }),
          secondLine && h("p", { class: "body-large", text: secondLine }),
          d.recurrence !== "NONE" &&
            h("p", {
              class: "body-medium variant",
              text:
                recurrenceText(d.recurrence, seriesStart) +
                (d.recurrenceUntil != null
                  ? `, until ${dayShort(fromEpochDay(d.recurrenceUntil))}`
                  : ""),
            }),
        ),
      ),
      d.importance &&
        row(
          I.flag,
          [
            h("span", {
              class: "body-large",
              text: IMPORTANCE[d.importance].label + " importance",
            }),
            h("span", {
              class: "body-medium variant",
              text: IMPORTANCE_HINTS[d.importance],
            }),
          ],
          { tint: IMPORTANCE[d.importance].color },
        ),
      d.location.trim() &&
        row(I.place, h("span", { class: "body-large", text: d.location }), {
          onclick: () =>
            open(
              "https://www.google.com/maps/search/?api=1&query=" +
                encodeURIComponent(d.location),
              "_blank",
              "noopener",
            ),
        }),
      d.reminders.length > 0 &&
        row(
          I.bell,
          [...d.reminders]
            .sort((a, b) => a - b)
            .map((m) =>
              h("span", {
                class: "body-large",
                text: reminderText(m, d.allDay),
              }),
            ),
          { gap: true },
        ),
      row(
        I.event,
        h("span", { class: "body-large", text: o.calendar.data.name }),
      ),
      d.description.trim() &&
        row(I.notes, h("span", { class: "body-large", text: d.description })),
      o.isTask &&
        h(
          "button",
          { type: "button", class: "tonal", onclick: () => toggleTask(o) },
          icon(o.completed ? I.undo : I.check),
          o.completed ? "Mark uncompleted" : "Mark completed",
        ),
    ),
  );
}
/** Opens a new email with the event's title, time and place. */
function mailEvent(o) {
  const d = o.event;
  const body = [
    ...occurrenceWhen(o),
    d.location.trim(),
    d.description.trim(),
  ].filter(Boolean);
  location.href =
    "mailto:?subject=" +
    encodeURIComponent(d.title || "(No title)") +
    "&body=" +
    encodeURIComponent(body.join("\n"));
}
async function openShare(o) {
  try {
    await sync();
    const share = await api("share", {
      eventId: o.record.id,
      action: "status",
    });
    showShare(o, share);
  } catch (e) {
    snackbar(e.message);
  }
}
function showShare(o, share) {
  const url = share.active
    ? share.url || new URL(share.path, location.origin).href
    : "";
  const input = h("input", {
    value: url,
    readonly: true,
    "aria-label": "Event link",
    onclick: (e) => e.target.select(),
  });
  let working = false;
  showAlert(
    "Share event",
    [
      h("p", {
        text: "Anyone with the link can read this event’s title, dates, location and notes. Edits update the shared page.",
      }),
      o.event.recurrence !== "NONE" &&
        h("p", { text: "This link shares the recurring series." }),
      url &&
        h(
          "label",
          { class: "field share-link" },
          h("span", { text: "Event link" }),
          input,
        ),
    ],
    (finish) => {
      const action = (label, callback) =>
        textButton(label, async () => {
          if (working) return;
          working = true;
          try {
            await callback();
          } catch (e) {
            snackbar(e.message);
          } finally {
            working = false;
          }
        });
      return [
        textButton("Close", () => finish()),
        url
          ? action("Stop sharing", async () => {
              await api("share", { eventId: o.record.id, action: "revoke" });
              if (alertDone === finish) finish();
              snackbar("Link disabled");
            })
          : action("Create link", async () => {
              const created = await api("share", {
                eventId: o.record.id,
                action: "create",
              });
              if (alertDone === finish) {
                finish();
                showShare(o, created);
              }
            }),
        url &&
          action("Copy link", async () => {
            try {
              await navigator.clipboard.writeText(url);
              snackbar("Link copied");
            } catch {
              input.focus();
              input.select();
              snackbar("Select and copy the link above");
            }
          }),
        url &&
          navigator.share &&
          action("Share", async () => {
            try {
              await navigator.share({ title: o.event.title, url });
            } catch (e) {
              if (e.name !== "AbortError") throw e;
            }
          }),
      ];
    },
  );
}
async function duplicate(o) {
  const d = o.event;
  const start = toStored(o.start, d.allDay);
  const record = newRecord("event");
  if (
    await save([
      change(record, {
        ...d,
        start,
        end: start + (d.end - d.start),
        exceptions: [],
        completions: [],
      }),
    ])
  )
    openDetail({ record, start: o.start });
}
async function deleteOccurrence(o) {
  const d = o.event,
    r = o.record;
  let scope = "ALL";
  if (d.recurrence === "NONE") {
    const title = d.title || "(No title)";
    if (
      !(await confirmAlert(
        "Delete event?",
        `“${title}” will be removed from your calendar.`,
        "Delete",
      ))
    )
      return;
  } else {
    scope = await choose("Delete recurring event", SCOPES, null);
    if (!scope) return;
  }
  const day = epochDay(o.startDate);
  const seriesDay = epochDay(wall(d.start, d.allDay));
  let ch;
  if (scope === "THIS")
    ch = change(r, { ...d, exceptions: [...d.exceptions, day] });
  // Ends the series before this day, deleting it entirely if nothing remains.
  else if (scope === "FOLLOWING" && day > seriesDay)
    ch = change(r, { ...d, recurrenceUntil: day - 1 });
  else ch = change(r, null, true);
  if (await save([ch])) $("detail").close();
}

/* Event editor, as in ui/event/EventEditorScreen.kt */
let ed = null;
const TIMED_REMINDERS = [0, 5, 10, 15, 30, 60, 120, 1440, 10080];
const ALL_DAY_REMINDERS = [0, 1440, 10080];
const edStart = (s = ed.state) =>
  s.allDay ? s.startDate : atMinute(s.startDate, s.startTime);
const edEnd = (s = ed.state) =>
  s.allDay ? s.endDate : atMinute(s.endDate, s.endTime);
const edValid = (s = ed.state) =>
  s.kind === "TASK" ||
  (s.allDay ? s.endDate >= s.startDate : edEnd(s) >= edStart(s));
const edCanSave = () => edValid() && !!ed.state.title.trim() && !ed.saving;
/**
 * Opens the full editor on an occurrence, or for a new item on [date] at [minute]. Quick add's
 * More options passes what it understood: [title], [task], [calendarId] and a parsed [draft].
 */
function openEditor({
  occurrence,
  date,
  minute,
  title = "",
  task = false,
  calendarId,
  draft,
}) {
  const cals = calendars();
  if (!cals.length) {
    snackbar("Add a calendar first");
    openCalendars();
    return;
  }
  let state,
    series = null,
    opened = null;
  if (occurrence) {
    series = occurrence.record;
    const d = series.data;
    const baseStart = wall(d.start, d.allDay);
    const length = Math.max(0, wall(d.end, d.allDay) - baseStart);
    if (d.recurrence !== "NONE") opened = occurrence.start;
    const start = opened ?? baseStart;
    const end = d.allDay
      ? addDays(start, Math.round(length / DAY))
      : new Date(start.getTime() + length);
    state = {
      title: d.title,
      kind: d.kind,
      allDay: d.allDay,
      startDate: startOfDay(start),
      startTime: minutesOf(start),
      // All-day events end at midnight after their last day.
      endDate: d.allDay
        ? maxDate(addDays(startOfDay(end), -1), startOfDay(start))
        : startOfDay(end),
      endTime: minutesOf(end),
      recurrence: d.recurrence,
      until: d.recurrenceUntil != null ? fromEpochDay(d.recurrenceUntil) : null,
      importance: d.importance,
      location: d.location,
      reminders: [...d.reminders].sort((a, b) => a - b),
      calendarId: d.calendarId,
      color: d.color,
      description: d.description,
    };
  } else {
    const now = new Date();
    const day = startOfDay(date);
    const start =
      minute != null
        ? atMinute(day, minute)
        : sameDay(day, now) && now.getHours() < 23
          ? atMinute(day, (now.getHours() + 1) * 60)
          : atMinute(day, 540);
    const end = new Date(start.getTime() + 3600000);
    const calendar = cals.find((c) => c.id === calendarId) ?? cals[0];
    state = {
      title,
      kind: task ? "TASK" : "EVENT",
      allDay: false,
      startDate: startOfDay(start),
      startTime: minutesOf(start),
      endDate: startOfDay(end),
      endTime: minutesOf(end),
      recurrence: "NONE",
      until: null,
      importance: calendar.data.defaultImportance,
      location: "",
      // Tasks remind when they're due; events use the default notification.
      reminders: task ? [0] : [30],
      calendarId: calendar.id,
      color: null,
      description: "",
    };
    // Keep whatever quick add understood.
    if (draft)
      Object.assign(state, {
        title: draft.title,
        allDay: draft.allDay,
        startDate: startOfDay(draft.start),
        startTime: minutesOf(draft.start),
        endDate: draft.allDay
          ? addDays(startOfDay(draft.end), -1)
          : startOfDay(draft.end),
        endTime: minutesOf(draft.end),
        recurrence: draft.recurrence,
      });
  }
  ed = { state, initial: JSON.stringify(state), series, opened, saving: false };
  renderEditor();
  openPage($("editor"));
  if (!occurrence) $("editor").querySelector(".title-input").focus();
}
function updateEditor(fn) {
  fn(ed.state);
  renderEditor();
}
/** Moving the start keeps the event's length, like most calendars do. */
function setStart(date, minute) {
  updateEditor((s) => {
    if (s.allDay) {
      const days = daysBetween(s.startDate, s.endDate);
      Object.assign(s, {
        startDate: date,
        startTime: minute,
        endDate: addDays(date, days),
      });
    } else {
      const end = new Date(
        atMinute(date, minute).getTime() + (edEnd(s) - edStart(s)),
      );
      Object.assign(s, {
        startDate: date,
        startTime: minute,
        endDate: startOfDay(end),
        endTime: minutesOf(end),
      });
    }
  });
}
async function closeEditor() {
  if (
    JSON.stringify(ed.state) === ed.initial ||
    (await confirmAlert(
      "Discard changes?",
      "Your changes to this event won't be saved.",
      "Discard",
    ))
  )
    $("editor").close();
}
$("editor").oncancel = (e) => {
  e.preventDefault();
  if (!$("alert").open) closeEditor();
};
function renderEditor() {
  const s = ed.state;
  const dialog = $("editor");
  const calendar = calendarById(s.calendarId);
  const color = s.color ?? calendar?.data.color;
  const isTask = s.kind === "TASK";
  const valid = edValid();
  const dateTimeRow = (date, minute, onDate, onTime, error) =>
    h(
      "div",
      { class: "datetime" + (error ? " error" : "") },
      h(
        "span",
        { class: "grow" },
        h("button", {
          type: "button",
          text: dayMedium(date),
          onclick: async () => {
            const picked = await datePicker(date);
            if (picked) onDate(picked);
          },
        }),
      ),
      !s.allDay &&
        h("button", {
          type: "button",
          text: time(atMinute(date, minute)),
          onclick: async () => {
            const picked = await timePicker(minute);
            if (picked != null) onTime(picked);
          },
        }),
    );
  const row = (path, content, { onclick, top, indent } = {}) =>
    h(
      onclick ? "button" : "div",
      {
        type: onclick ? "button" : null,
        class: "edit-row" + (top ? " top" : "") + (indent ? " indent" : ""),
        onclick,
      },
      path && icon(path),
      h("div", { class: "grow" }, content),
    );
  const plain = (tag, value, placeholder, oninput) => {
    const el = h(tag, {
      class: "plain body-large",
      placeholder,
      "aria-label": placeholder,
      rows: tag === "textarea" ? 1 : null,
    });
    el.value = value;
    el.oninput = () => oninput(el.value);
    return el;
  };
  const saveButton = h("button", {
    type: "button",
    class: "filled",
    text: "Save",
    disabled: !edCanSave(),
    onclick: saveEditor,
  });
  const title = h("input", {
    class: "title-input",
    placeholder: "Add title",
    "aria-label": "Title",
    maxlength: 1000,
  });
  title.value = s.title;
  title.oninput = () => {
    s.title = title.value;
    saveButton.disabled = !edCanSave();
  };
  title.onkeydown = (e) => {
    if (e.key === "Enter" && edCanSave()) saveEditor();
  };
  const chip = (label, pressed, onclick) =>
    h(
      "button",
      { type: "button", class: "chip", "aria-pressed": pressed, onclick },
      pressed && icon(I.check),
      label,
    );
  const scrollTop = dialog.querySelector(".page-body")?.scrollTop ?? 0;
  dialog.replaceChildren(
    pageBar(I.close, "Close", closeEditor, "", saveButton),
    h(
      "div",
      { class: "page-body" },
      title,
      h(
        "div",
        { class: "chips kind-chips" },
        chip("Event", !isTask, () => updateEditor((x) => (x.kind = "EVENT"))),
        // A task keeps the date and time and reminds when it's due.
        chip("Task", isTask, () =>
          updateEditor((x) => {
            if (x.kind !== "TASK" && !x.reminders.length) x.reminders = [0];
            x.kind = "TASK";
          }),
        ),
      ),
      h("hr"),
      row(I.clock, [
        h("span", { text: "All-day", style: { flex: "1" } }),
        h("button", {
          type: "button",
          class: "switch",
          role: "switch",
          "aria-checked": s.allDay,
          "aria-label": "All-day",
          onclick: () => updateEditor((x) => (x.allDay = !x.allDay)),
        }),
      ]),
      dateTimeRow(
        s.startDate,
        s.startTime,
        (d) => setStart(d, s.startTime),
        (m) => setStart(s.startDate, m),
      ),
      !isTask &&
        dateTimeRow(
          s.endDate,
          s.endTime,
          (d) => updateEditor((x) => (x.endDate = d)),
          (m) => updateEditor((x) => (x.endTime = m)),
          !valid,
        ),
      !valid &&
        h("p", {
          class: "editor-error",
          text: "The event ends before it starts",
        }),
      row(I.repeat, recurrenceText(s.recurrence, s.startDate), {
        onclick: async () => {
          const rule = await choose(
            "Repeat",
            ["NONE", "DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY", "YEARLY"].map(
              (r) => ({ value: r, label: recurrenceText(r, s.startDate) }),
            ),
            s.recurrence,
          );
          if (rule) updateEditor((x) => (x.recurrence = rule));
        },
      }),
      s.recurrence !== "NONE" &&
        h(
          "div",
          { class: "until" },
          row(null, s.until ? `Ends on ${dayShort(s.until)}` : "Never ends", {
            indent: true,
            onclick: async () => {
              const ends = await choose(
                "Ends",
                [
                  { value: "never", label: "Never" },
                  { value: "date", label: "On a date" },
                ],
                s.until ? "date" : "never",
              );
              if (ends === "never") updateEditor((x) => (x.until = null));
              else if (ends === "date") {
                const until = await datePicker(
                  s.until ?? addMonths(s.startDate, 1),
                  "Ends on",
                );
                if (until)
                  updateEditor((x) => (x.until = maxDate(until, x.startDate)));
              }
            },
          }),
        ),
      h("hr"),
      row(
        I.flag,
        h(
          "div",
          {},
          h("p", { class: "body-large chip-label", text: "Importance" }),
          importanceChips(s.importance, (v) =>
            updateEditor((x) => (x.importance = v)),
          ),
          s.importance &&
            h("p", {
              class: "hint",
              text: {
                ...IMPORTANCE_HINTS,
                HIGH: "Urgent alerts at start that repeat until you respond",
              }[s.importance],
            }),
        ),
        { top: true },
      ),
      h("hr"),
      !isTask &&
        row(
          I.place,
          plain("input", s.location, "Add location", (v) => (s.location = v)),
        ),
      !isTask && h("hr"),
      row(
        I.bell,
        h(
          "div",
          { style: { flex: "1" } },
          s.reminders.map((m) =>
            h(
              "div",
              { class: "reminder body-large" },
              h("span", { text: reminderText(m, s.allDay) }),
              iconButton(I.close, "Remove notification", () =>
                updateEditor(
                  (x) => (x.reminders = x.reminders.filter((r) => r !== m)),
                ),
              ),
            ),
          ),
          h("button", {
            type: "button",
            class: "add-reminder",
            text: "Add notification",
            onclick: async () => {
              const options = (
                s.allDay ? ALL_DAY_REMINDERS : TIMED_REMINDERS
              ).filter((m) => !s.reminders.includes(m));
              const m = await choose(
                "Notification",
                options.map((v) => ({
                  value: v,
                  label: reminderText(v, s.allDay),
                })),
                null,
              );
              if (m != null)
                updateEditor(
                  (x) =>
                    (x.reminders = [...x.reminders, m].sort((a, b) => a - b)),
                );
            },
          }),
        ),
        { top: true },
      ),
      h("hr"),
      row(
        I.event,
        [
          calendar &&
            h("span", {
              class: "dot",
              style: { background: css(calendar.data.color) },
            }),
          calendar?.data.name ?? "",
        ],
        {
          onclick: async () => {
            const id = await choose(
              "Calendar",
              calendars().map((c) => ({
                value: c.id,
                label: c.data.name,
                dot: css(c.data.color),
              })),
              s.calendarId,
            );
            if (!id) return;
            updateEditor((x) => {
              // A new item follows its calendar's default importance, unless it was set by hand.
              const old = calendarById(x.calendarId);
              if (
                !ed.series &&
                x.importance === (old?.data.defaultImportance ?? null)
              )
                x.importance = calendarById(id).data.defaultImportance;
              x.calendarId = id;
            });
          },
        },
      ),
      row(
        I.palette,
        [
          color != null &&
            h("span", { class: "dot", style: { background: css(color) } }),
          s.color == null ? "Calendar color" : colorLabel(s.color),
        ],
        {
          onclick: async () => {
            const picked = await choose(
              "Event color",
              [
                {
                  value: "calendar",
                  label: "Calendar color",
                  dot: css(calendar?.data.color ?? 0),
                },
                ...EVENT_COLORS.map((c) => ({
                  value: c.argb,
                  label: c.label,
                  dot: css(c.argb),
                })),
              ],
              s.color == null ? "calendar" : s.color,
            );
            if (picked !== undefined)
              updateEditor(
                (x) => (x.color = picked === "calendar" ? null : picked),
              );
          },
        },
      ),
      h("hr"),
      row(
        I.notes,
        plain(
          "textarea",
          s.description,
          "Add description",
          (v) => (s.description = v),
        ),
        {
          top: true,
        },
      ),
    ),
  );
  dialog.querySelector(".page-body").scrollTop = scrollTop;
}
function importanceChips(value, onSelect) {
  return h(
    "div",
    { class: "chips" },
    [null, "LOW", "MEDIUM", "HIGH"].map((option) => {
      const pressed = value === option;
      const color = option && IMPORTANCE[option].color;
      return h(
        "button",
        {
          type: "button",
          class: "chip soft",
          "aria-pressed": pressed,
          onclick: () => onSelect(option),
          style: option
            ? { "--chip": color, "--chip-ink": contentColor(hexRgb(color)) }
            : {},
        },
        pressed && icon(I.check),
        option &&
          h("span", { class: "importance", style: { background: color } }),
        option ? IMPORTANCE[option].label : "None",
      );
    }),
  );
}
async function saveEditor() {
  const s = ed.state;
  if (!edCanSave()) return;
  const { series, opened } = ed;
  let scope = null;
  // Editing one instance of a series asks which instances the change applies to.
  // "This event" makes no sense once the repeat rule itself is changed.
  if (series && opened) {
    const options =
      s.recurrence !== series.data.recurrence ? SCOPES.slice(1) : SCOPES;
    scope = await choose("Save recurring event", options, null);
    if (!scope) return;
  }
  const isTask = s.kind === "TASK";
  const startAt = edStart();
  const start = toStored(startAt, s.allDay);
  // All-day end dates are inclusive here and exclusive on the wire.
  const end = isTask
    ? s.allDay
      ? start + DAY
      : start
    : s.allDay
      ? toStored(addDays(s.endDate, 1), true)
      : edEnd().getTime();
  const edited = {
    calendarId: s.calendarId,
    title: s.title.trim(),
    description: s.description.trim(),
    location: s.location.trim(),
    start,
    end,
    allDay: s.allDay,
    color: s.color,
    recurrence: s.recurrence,
    recurrenceUntil:
      s.until && s.recurrence !== "NONE" ? epochDay(s.until) : null,
    exceptions: series?.data.exceptions ?? [],
    reminders: [...new Set(s.reminders)].sort((a, b) => a - b),
    kind: s.kind,
    completions: series?.data.completions ?? [],
    importance: s.importance,
  };
  const changes = [];
  let target = series ?? newRecord("event");
  if (!series || !opened) changes.push(change(target, edited));
  else {
    const d = series.data;
    const openedDay = epochDay(opened);
    const seriesDay = epochDay(wall(d.start, d.allDay));
    if (scope === "THIS") {
      changes.push(
        change(series, { ...d, exceptions: [...d.exceptions, openedDay] }),
      );
      target = newRecord("event");
      changes.push(
        change(target, {
          ...edited,
          recurrence: "NONE",
          recurrenceUntil: null,
          exceptions: [],
          completions: [],
        }),
      );
    } else if (scope === "FOLLOWING" && openedDay > seriesDay) {
      // Splits the series; the edit becomes its new tail.
      changes.push(change(series, { ...d, recurrenceUntil: openedDay - 1 }));
      target = newRecord("event");
      changes.push(change(target, { ...edited, exceptions: [] }));
    } else if (scope === "FOLLOWING") changes.push(change(series, edited));
    else {
      // The series start moves by as many days as this occurrence was moved.
      const shift = daysBetween(startOfDay(opened), s.startDate);
      const seriesDate = addDays(fromEpochDay(seriesDay), shift);
      const newStart = toStored(
        s.allDay ? seriesDate : atMinute(seriesDate, s.startTime),
        s.allDay,
      );
      changes.push(
        change(series, {
          ...edited,
          start: newStart,
          end: newStart + (end - start),
          exceptions: shift === 0 ? d.exceptions : [],
        }),
      );
    }
  }
  ed.saving = true;
  renderEditor();
  const ok = await save(changes);
  ed.saving = false;
  if (!ok) return renderEditor();
  $("editor").close();
  if ($("detail").open) {
    detailOf = { id: target.id, start: startAt };
    renderDetail();
  }
  jumpTo(s.startDate);
}

/* Calendars page, as in ui/calendars/ManageCalendarsScreen.kt */
function openCalendars() {
  renderCalendars();
  openPage($("calendars"));
}
const keywordList = (k) =>
  k
    .split(",")
    .map((x) => x.trim())
    .filter(Boolean);
function renderCalendars() {
  const cals = calendars();
  const dialog = $("calendars");
  dialog.replaceChildren(
    pageBar(
      I.back,
      "Back",
      () => dialog.close(),
      "Calendars",
      iconButton(I.add, "Add calendar", () => {
        const unused = EVENT_COLORS.find(
          (c) => !cals.some((x) => x.data.color === c.argb),
        );
        editCalendar(null, {
          name: "",
          color: (unused ?? EVENT_COLORS[6]).argb,
          visible: true,
          keywords: "",
          defaultImportance: null,
        });
      }),
    ),
    h(
      "div",
      { class: "page-body list-pad" },
      cals.length > 0 &&
        h(
          "div",
          { class: "group" },
          cals.map((c) => {
            const keywords = keywordList(c.data.keywords);
            return h(
              "div",
              { class: "cal-row" },
              h(
                "button",
                { type: "button", onclick: () => editCalendar(c, c.data) },
                h("span", {
                  class: "dot",
                  style: { background: css(c.data.color) },
                }),
                h(
                  "span",
                  { class: "names" },
                  h("span", { class: "body-large", text: c.data.name }),
                  keywords.length > 0 &&
                    h("span", {
                      class: "body-small variant",
                      text: keywords.join(", "),
                    }),
                ),
              ),
              iconButton(
                I.delete,
                "Delete " + c.data.name,
                () => deleteCalendar(c),
                {
                  disabled: cals.length <= 1,
                },
              ),
            );
          }),
        ),
      h("p", {
        class: "note body-small variant",
        text: cals.length
          ? "Hidden calendars stay here; toggle visibility from the menu on the main screen."
          : "Add a calendar to start adding events.",
      }),
    ),
  );
}
function editCalendar(record, data) {
  const state = { ...data };
  const field = (label, value, extra = {}) => {
    const input = h("input", { "aria-label": label, ...extra });
    input.value = value;
    return [
      input,
      h("label", { class: "field" }, h("span", { text: label }), input),
    ];
  };
  const [name, nameField] = field("Name", state.name, { maxlength: 200 });
  const [keywords, keywordField] = field("Keywords", state.keywords, {
    placeholder: "produce, shift",
  });
  keywordField.append(
    h("small", { text: "Quick add sends matching items here" }),
  );
  const importance = h("div");
  const swatches = h("div", { class: "swatches" });
  const draw = () => {
    importance.replaceChildren(
      h("p", { class: "body-large chip-label", text: "Default importance" }),
      importanceChips(state.defaultImportance, (v) => {
        state.defaultImportance = v;
        draw();
      }),
    );
    swatches.replaceChildren(
      ...EVENT_COLORS.map((c) => {
        const check = icon(I.check);
        check.style.color = contentColor(rgbOf(c.argb));
        return h(
          "button",
          {
            type: "button",
            "aria-label": c.label,
            title: c.label,
            "aria-pressed": c.argb === state.color,
            onclick: () => {
              state.color = c.argb;
              draw();
            },
          },
          h("span", { style: { background: css(c.argb) } }),
          check,
        );
      }),
    );
  };
  draw();
  let saveButton;
  name.oninput = () => (saveButton.disabled = !name.value.trim());
  showAlert(
    record ? "Edit calendar" : "New calendar",
    [nameField, keywordField, importance, swatches],
    (finish) => {
      saveButton = textButton(
        "Save",
        () => {
          finish(true);
          save([
            change(record ?? newRecord("calendar"), {
              ...state,
              name: name.value.trim(),
              keywords: keywordList(keywords.value).join(", "),
            }),
          ]);
        },
        !state.name.trim(),
      );
      return [textButton("Cancel", () => finish(false)), saveButton];
    },
    { form: true },
  );
  name.focus();
}
async function deleteCalendar(c) {
  if (
    !(await confirmAlert(
      `Delete “${c.data.name}”?`,
      "All events in this calendar will be deleted. This can't be undone.",
      "Delete",
    ))
  )
    return;
  const events = records.filter(
    (r) => r.kind === "event" && !r.deleted && r.data.calendarId === c.id,
  );
  await save([
    ...events.map((r) => change(r, null, true)),
    change(c, null, true),
  ]);
}

/* Search page, as in ui/search/SearchScreen.kt */
let query = "";
function openSearch() {
  const dialog = $("search-page");
  const input = h("input", {
    class: "search-input",
    placeholder: "Search events",
    "aria-label": "Search events",
    type: "search",
  });
  input.value = query;
  const clear = iconButton(I.close, "Clear search", () => {
    query = input.value = "";
    clear.hidden = true;
    renderResults();
    input.focus();
  });
  clear.hidden = !query;
  input.oninput = () => {
    query = input.value;
    clear.hidden = !query;
    renderResults();
  };
  dialog.replaceChildren(
    h(
      "header",
      { class: "page-bar" },
      iconButton(I.back, "Back", () => dialog.close()),
      input,
      clear,
    ),
    h("hr"),
    h("div", { class: "page-body", id: "results" }),
  );
  renderResults();
  openPage(dialog);
  input.focus();
}
/** Matching events, each shown at its next occurrence. Upcoming results come first. */
function renderResults() {
  const needle = query.trim().toLowerCase();
  const now = today();
  const found = needle
    ? visibleEvents()
        .filter(([r]) =>
          [r.data.title, r.data.location, r.data.description].some((t) =>
            t.toLowerCase().includes(needle),
          ),
        )
        .map(([r, c]) => representative(r, c))
        .filter(Boolean)
    : [];
  const results = [
    ...found.filter((o) => o.lastDate >= now).sort((a, b) => a.start - b.start),
    ...found.filter((o) => o.lastDate < now).sort((a, b) => b.start - a.start),
  ];
  $("results").replaceChildren(
    results.length
      ? h(
          "div",
          { class: "results" },
          results.map((o) =>
            eventCard(
              o,
              `${dayMedium(o.startDate)}, ${occurrenceTime(o)}`,
              o.event.location,
            ),
          ),
        )
      : h("p", {
          class: "empty body-medium",
          text: needle
            ? `No events match “${query.trim()}”`
            : "Search by title, location, or description",
        }),
  );
}

/* Settings page: the parts of the app's settings that apply to this browser */
function openSettings() {
  renderSettings();
  openPage($("settings-page"));
}
function renderSettings() {
  const dialog = $("settings-page");
  const setting = (title, value, onclick) =>
    h(
      "button",
      { type: "button", class: "setting", onclick },
      h(
        "div",
        {},
        h("span", { class: "body-large", text: title }),
        value && h("span", { class: "body-medium variant", text: value }),
      ),
    );
  const weekdays = [0, 1, 6].map((d) => ({
    value: d,
    label: fmt(new Date(2023, 0, 1 + d), { weekday: "long" }),
  }));
  const scrollTop = dialog.querySelector(".page-body")?.scrollTop ?? 0;
  dialog.replaceChildren(
    pageBar(I.back, "Back", () => dialog.close(), "Settings"),
    h(
      "div",
      { class: "page-body settings-body" },
      h("p", { class: "settings-label label-large", text: "Cloud sync" }),
      h(
        "div",
        { class: "settings-card" },
        h(
          "div",
          { class: "head" },
          icon(I.cloud),
          h(
            "div",
            {},
            h("p", { class: "title-medium", text: "Your calendar, connected" }),
            h("p", { class: "body-small variant", text: syncMessage }),
          ),
        ),
        h("p", { class: "body-medium", text: email || "" }),
        h("p", { class: "body-small variant", text: location.origin }),
        lastSync > 0 &&
          h("p", {
            class: "body-small",
            text:
              "Last synced " +
              new Date(lastSync).toLocaleString(undefined, {
                dateStyle: "short",
                timeStyle: "short",
              }),
          }),
        h("p", {
          class: "body-small variant",
          text: "Changes sync automatically while this page is open.",
        }),
        h(
          "div",
          { class: "actions" },
          h("button", {
            type: "button",
            class: "filled",
            text: "Sync now",
            disabled: busy,
            onclick: () => sync().catch(() => {}),
          }),
          textButton("Sign out", signOut),
        ),
      ),
      h("p", { class: "settings-label label-large", text: "Appearance" }),
      themePicker(),
      h("p", { class: "settings-label label-large", text: "General" }),
      h(
        "div",
        { class: "group" },
        setting(
          "Start of the week",
          weekdays.find((w) => w.value === prefs.weekStart)?.label,
          async () => {
            const day = await choose(
              "Start of the week",
              weekdays,
              prefs.weekStart,
            );
            if (day == null) return;
            prefs.weekStart = day;
            savePrefs();
            renderSettings();
            render({ jump: true });
          },
        ),
        h(
          "div",
          { class: "setting" },
          h(
            "div",
            {},
            h("span", { class: "body-large", text: "24-hour time" }),
            h("span", { class: "body-medium variant", text: hourLabel(13) }),
          ),
          h("button", {
            type: "button",
            class: "switch",
            role: "switch",
            "aria-label": "24-hour time",
            "aria-checked": prefs.use24h,
            onclick: () => {
              prefs.use24h = !prefs.use24h;
              savePrefs();
              renderSettings();
              render();
            },
          }),
        ),
        setting("Edit history", "Earlier and conflicting edits", openHistory),
      ),
    ),
  );
  dialog.querySelector(".page-body").scrollTop = scrollTop;
}
/** The color scheme row and theme gallery, as in ui/settings/ThemePicker.kt. */
function themePicker() {
  const pick = (change) => {
    Object.assign(prefs, change);
    savePrefs();
    applyTheme();
    renderSettings();
    render();
  };
  const card = (selected, label, preview, onclick, cls = "") =>
    h(
      "button",
      {
        type: "button",
        class: "theme-card " + cls,
        "aria-pressed": selected,
        disabled: selected,
        onclick,
      },
      preview,
      h("span", { class: "label-large", text: label }),
    );
  const light = schemeFor(prefs.appTheme, false),
    dark = schemeFor(prefs.appTheme, true);
  return h(
    "div",
    { class: "theme-picker" },
    h("p", { class: "title-small variant", text: "Color scheme" }),
    h(
      "div",
      { class: "scheme-row" },
      [
        ["system", "System"],
        ["light", "Light"],
        ["dark", "Dark"],
      ].map(([mode, label]) =>
        card(
          prefs.theme === mode,
          label,
          schemePreview(mode, light, dark),
          () => pick({ theme: mode }),
          "scheme",
        ),
      ),
    ),
    h("p", { class: "title-small variant", text: "Themes" }),
    h(
      "div",
      { class: "theme-grid" },
      THEMES.map((theme) => {
        const scheme = schemeFor(theme.id, themeIsDark());
        const orb = h("span", {
          class: "orb",
          style: {
            "--bg": hexColor(scheme.surface),
            "--accent": hexColor(scheme.primary),
            "--soft": hexColor(scheme.primaryContainer),
          },
        });
        return card(
          prefs.appTheme === theme.id,
          theme.label,
          orb,
          () => pick({ appTheme: theme.id }),
          "theme",
        );
      }),
    ),
  );
}
/** A tiny schedule in the theme's colors. System splits it light and dark. */
function schemePreview(mode, light, dark) {
  const canvas = h("canvas", {
    width: 216,
    height: 160,
    "aria-hidden": "true",
  });
  const g = canvas.getContext("2d");
  const w = 216,
    hgt = 160;
  const [left, right] =
    mode === "light"
      ? [light, light]
      : mode === "dark"
        ? [dark, dark]
        : [light, dark];
  g.fillStyle = hexColor(left.surface);
  g.fillRect(0, 0, w / 2, hgt);
  g.fillStyle = hexColor(right.surface);
  g.fillRect(w / 2, 0, w / 2, hgt);
  // Shades step toward the other mode's accent, so they stay solid.
  const mix = (a, b, t) => a.map((v, i) => v + (b[i] - v) * t);
  const shade = (scheme, t) =>
    mix(scheme.primary, scheme === light ? dark.primary : light.primary, t);
  const accent = (t) => {
    const gradient = g.createLinearGradient(0, 0, w, 0);
    gradient.addColorStop(0.42, hexColor(shade(left, t)));
    gradient.addColorStop(0.58, hexColor(shade(right, t)));
    return gradient;
  };
  g.fillStyle = accent(0);
  g.beginPath();
  g.arc(w * 0.17, hgt * 0.27, hgt * 0.1, 0, Math.PI * 2);
  g.fill();
  [0, 0.4, 0.8].forEach((t, i) => {
    g.fillStyle = accent(t);
    g.beginPath();
    g.roundRect(
      w * 0.34,
      hgt * (0.17 + i * 0.24),
      w * 0.56,
      hgt * 0.18,
      hgt * 0.06,
    );
    g.fill();
  });
  return canvas;
}
async function signOut() {
  if (
    !(await confirmAlert(
      "Sign out of cloud sync?",
      "This browser forgets your session. Your events stay in your account.",
      "Sign out",
    ))
  )
    return;
  try {
    await api("logout", {});
  } catch {}
  token = null;
  records = [];
  cursor = 0;
  sessionStorage.clear();
  photo = null;
  for (const d of document.querySelectorAll("dialog[open]")) d.close();
  signedIn();
}
async function openHistory() {
  let history;
  try {
    ({ history } = await api("history"));
  } catch (e) {
    return snackbar(e.message);
  }
  const dialog = $("history-page");
  dialog.replaceChildren(
    pageBar(I.back, "Back", () => dialog.close(), "Edit history"),
    h(
      "div",
      { class: "page-body" },
      h("p", {
        class: "body-medium variant history-intro",
        text: "Earlier and conflicting edits are kept here. Restore an item to get it back.",
      }),
      history.length
        ? history.map((item) =>
            h(
              "div",
              { class: "history-item" },
              h(
                "p",
                {},
                h("span", {
                  class: "body-large",
                  text: item.data?.title || item.data?.name || "Deleted item",
                }),
                h("br"),
                h("span", {
                  class: "body-small variant",
                  text:
                    item.reason +
                    " · " +
                    new Date(item.created).toLocaleString(),
                }),
              ),
              item.data &&
                textButton("Restore", async () => {
                  const current = records.find(
                    (r) => r.id === item.recordId && r.kind === item.kind,
                  );
                  if (
                    await save([
                      change(
                        current || {
                          id: item.recordId,
                          kind: item.kind,
                          version: 0,
                        },
                        item.data,
                      ),
                    ])
                  )
                    dialog.close();
                }),
            ),
          )
        : h("p", {
            class: "empty body-medium",
            text: "No previous edits yet.",
          }),
    ),
  );
  openPage(dialog);
}

/* ---------- Popup menus ---------- */
let closeOpenMenu = null;
/**
 * A Material menu under (or above) [anchor]. Items are { label, icon?, dot?, checked?, action }.
 * Inside a dialog it opens in that dialog, so it stays above it.
 */
function popupMenu(anchor, items, { alignEnd = false, above = false } = {}) {
  closeOpenMenu?.();
  const host = anchor.closest("dialog") ?? document.body;
  const rect = anchor.getBoundingClientRect();
  const hostRect =
    host === document.body ? { left: 0, top: 0 } : host.getBoundingClientRect();
  const menu = h(
    "div",
    { class: "menu popup", role: "menu" },
    items.map((item) =>
      h(
        "button",
        {
          type: "button",
          role: "menuitemradio",
          "aria-checked": !!item.checked,
          onclick: () => {
            close();
            item.action();
          },
        },
        item.dot
          ? h("span", { class: "menu-dot", style: { background: item.dot } })
          : item.icon && icon(item.icon),
        h("span", { class: "menu-label", text: item.label }),
        item.checked && icon(I.check),
      ),
    ),
  );
  menu.style.position = host === document.body ? "fixed" : "absolute";
  host.append(menu);
  const width = menu.offsetWidth,
    height = menu.offsetHeight;
  const left = alignEnd ? rect.right - width : rect.left;
  menu.style.left =
    Math.max(8, Math.min(left, innerWidth - width - 8)) - hostRect.left + "px";
  menu.style.top =
    (above
      ? Math.max(8, rect.top - height)
      : Math.min(rect.bottom + 4, innerHeight - height - 8)) -
    hostRect.top +
    "px";
  const outside = (e) => {
    if (!menu.contains(e.target) && !anchor.contains(e.target)) close();
  };
  const key = (e) => {
    if (e.key === "Escape") {
      e.stopPropagation();
      e.preventDefault();
      close();
      anchor.focus();
    }
  };
  function close() {
    menu.remove();
    removeEventListener("pointerdown", outside, true);
    removeEventListener("keydown", key, true);
    if (closeOpenMenu === close) closeOpenMenu = null;
  }
  addEventListener("pointerdown", outside, true);
  addEventListener("keydown", key, true);
  closeOpenMenu = close;
  menu.querySelector("[aria-checked=true]")?.focus({ preventScroll: true });
}

/* ---------- Quick add, as in ui/event/QuickAddSheet.kt ---------- */
const EVENT_EXAMPLES = [
  "Lunch with Sam tomorrow at noon",
  "Gym every Monday at 6pm",
  "Work from 9 to 5 on Friday",
];
const TASK_EXAMPLES = [
  "Pay rent by Friday",
  "Call the dentist by 5pm",
  "Take vitamins every day at 8am",
];
I.taskAlt =
  "M22 5.18 10.59 16.6l-4.24-4.24 1.41-1.41 2.83 2.83 10-10L22 5.18Zm-2.21 5.04c.13.57.21 1.17.21 1.78 0 4.42-3.58 8-8 8s-8-3.58-8-8 3.58-8 8-8c1.58 0 3.04.46 4.28 1.25l1.44-1.44A9.9 9.9 0 0 0 12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10c0-1.19-.22-2.33-.6-3.39l-1.61 1.61Z";
I.info =
  "M11 7h2v2h-2V7Zm0 4h2v6h-2v-6Zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2Zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8Z";
I.dropDown = "m7 10 5 5 5-5H7Z";

/** "Tomorrow · 3 – 5 PM", "Due Fri, Oct 9 by 9 PM", or "Today · All day". */
function whenLabel(d, task) {
  const now = today();
  const day = (date) =>
    sameDay(date, now)
      ? "Today"
      : sameDay(date, addDays(now, 1))
        ? "Tomorrow"
        : dayMedium(date);
  const relative = (text) =>
    text === "Today" || text === "Tomorrow" ? text.toLowerCase() : text;
  const startDay = day(startOfDay(d.start));
  const lastDay = addDays(startOfDay(d.end), -1);
  if (task && d.allDay) return `Due ${relative(startDay)}`;
  if (task) return `Due ${relative(startDay)} by ${time(d.start)}`;
  if (d.allDay && !sameDay(lastDay, startOfDay(d.start)))
    return `${startDay} – ${day(lastDay)} · All day`;
  if (d.allDay) return `${startDay} · All day`;
  if (!sameDay(d.start, d.end))
    return `${startDay}, ${time(d.start)} – ${day(startOfDay(d.end))}, ${time(d.end)}`;
  return `${startDay} · ${timeRange(d.start, d.end)}`;
}

/** Google-style quick add: one sentence in, an event or task out. */
function openQuickAdd({ date } = {}) {
  closeDrawer();
  if (!calendars().length) {
    snackbar("Add a calendar first");
    return openCalendars();
  }
  const dialog = $("quick");
  // A time without a day goes on the day being viewed, unless that day is already past.
  const defaultDate = date ?? maxDate(selected, today());
  let taskChoice = null,
    calendarChoice = null,
    importanceChoice; // undefined until picked; null means none
  let saving = false;
  const input = h("textarea", {
    class: "quick-input",
    rows: 1,
    maxlength: 500,
    "aria-label": "What, and when?",
  });
  const preview = h("div", { class: "quick-preview" });
  const kinds = h("div", { class: "chips" });
  const saveButton = h("button", {
    type: "button",
    class: "filled",
    text: "Save",
  });
  const state = () => {
    const text = input.value;
    const isTask = taskChoice ?? looksLikeTask(text);
    const result = parseSmart(text, today(), { task: isTask, defaultDate });
    const cals = calendars();
    const history = records
      .filter((r) => r.kind === "event" && !r.deleted)
      .map((r) => r.data)
      .reverse();
    const match = matchCalendar(
      result.ok ? result.draft.title : text,
      cals.map((c) => ({
        id: c.id,
        name: c.data.name,
        keywords: c.data.keywords,
      })),
      history,
    );
    const calendar =
      cals.find((c) => c.id === calendarChoice) ??
      (match && cals.find((c) => c.id === match.calendar.id)) ??
      cals[0];
    const importance =
      importanceChoice === undefined
        ? calendar.data.defaultImportance
        : importanceChoice;
    const reason =
      calendarChoice == null && match && match.calendar.id === calendar.id
        ? match.reason
        : null;
    return { text, isTask, result, calendar, importance, reason };
  };
  const row = (path, content, { emphasized = false, muted = false } = {}) => {
    const svg = icon(path);
    if (emphasized) svg.style.color = "var(--primary)";
    return h(
      "div",
      {
        class:
          "quick-row" +
          (muted ? " muted" : "") +
          (emphasized ? " emphasized" : ""),
      },
      svg,
      content,
    );
  };
  const draw = () => {
    const st = state();
    kinds.replaceChildren(
      ...[
        ["Event", I.event, false],
        ["Task", I.taskAlt, true],
      ].map(([label, path, task]) =>
        h(
          "button",
          {
            type: "button",
            class: "chip kind",
            "aria-pressed": st.isTask === task,
            onclick: () => {
              taskChoice = task;
              draw();
              input.focus();
            },
          },
          icon(path),
          label,
        ),
      ),
    );
    input.placeholder = st.isTask
      ? "What needs doing, and by when?"
      : "What, and when?";
    if (!st.text.trim())
      preview.replaceChildren(
        h(
          "div",
          { class: "examples" },
          (st.isTask ? TASK_EXAMPLES : EVENT_EXAMPLES).map((example) =>
            h("button", {
              type: "button",
              class: "suggestion",
              text: example,
              onclick: () => {
                input.value = example;
                draw();
                input.focus();
              },
            }),
          ),
        ),
      );
    else if (!st.result.ok)
      preview.replaceChildren(
        row(I.info, h("span", { text: st.result.message }), { muted: true }),
      );
    else {
      const d = st.result.draft;
      const calendarButton = h(
        "button",
        {
          type: "button",
          class: "quick-calendar",
          "aria-label": "Change calendar",
          onclick: () =>
            popupMenu(
              calendarButton,
              calendars().map((c) => ({
                label: c.data.name,
                dot: css(c.data.color),
                checked: c.id === st.calendar.id,
                action: () => {
                  calendarChoice = c.id;
                  draw();
                },
              })),
              { above: true },
            ),
        },
        h(
          "span",
          { class: "dot-box" },
          h("span", {
            class: "dot",
            style: { background: css(st.calendar.data.color) },
          }),
        ),
        h(
          "span",
          { class: "names" },
          h("span", { class: "body-large", text: st.calendar.data.name }),
          st.reason &&
            h("span", { class: "body-small variant", text: st.reason }),
        ),
        icon(I.dropDown),
      );
      const importanceButton = h(
        "button",
        {
          type: "button",
          class: "quick-importance",
          "aria-label": "Change importance",
          onclick: () =>
            popupMenu(
              importanceButton,
              [null, "LOW", "MEDIUM", "HIGH"].map((option) => ({
                label: option ? IMPORTANCE[option].label : "None",
                dot: option && IMPORTANCE[option].color,
                icon: option ? null : I.flag,
                checked: option === st.importance,
                action: () => {
                  importanceChoice = option;
                  draw();
                },
              })),
              { above: true, alignEnd: true },
            ),
        },
        st.importance
          ? h("span", {
              class: "dot",
              style: { background: IMPORTANCE[st.importance].color },
            })
          : icon(I.flag),
        h("span", {
          text: st.importance ? IMPORTANCE[st.importance].label : "No priority",
        }),
        icon(I.dropDown),
      );
      preview.replaceChildren(
        ...[
          row(
            st.isTask ? I.taskAlt : I.event,
            h("span", { class: "title-medium", text: d.title }),
            { emphasized: true },
          ),
          row(I.clock, h("span", { text: whenLabel(d, st.isTask) })),
          d.recurrence !== "NONE" &&
            row(
              I.repeat,
              h("span", {
                text: recurrenceText(d.recurrence, startOfDay(d.start)),
              }),
            ),
          h(
            "div",
            { class: "quick-calendar-row" },
            calendarButton,
            importanceButton,
          ),
        ].filter(Boolean),
      );
    }
    saveButton.disabled = !st.result.ok || saving;
    // The box grows with the text, up to four lines.
    input.style.height = "auto";
    input.style.height = Math.min(input.scrollHeight, 4 * 32) + "px";
  };
  const submit = async () => {
    const st = state();
    if (!st.result.ok || saving) return;
    const d = st.result.draft;
    saving = true;
    draw();
    const record = newRecord("event");
    const ok = await save([
      change(record, {
        calendarId: st.calendar.id,
        title: d.title,
        description: "",
        location: "",
        start: toStored(d.start, d.allDay),
        end: toStored(d.end, d.allDay),
        allDay: d.allDay,
        color: null,
        recurrence: d.recurrence,
        recurrenceUntil: null,
        exceptions: [],
        reminders: st.isTask ? [0] : [30],
        kind: st.isTask ? "TASK" : "EVENT",
        completions: [],
        importance: st.importance,
      }),
    ]);
    saving = false;
    if (!ok) return draw();
    dialog.close();
    jumpTo(startOfDay(d.start));
    snackbar(
      `${st.isTask ? "Task" : "Event"} added · ${whenLabel(d, st.isTask)}`,
      {
        label: "View",
        run: () => openDetail({ record, start: d.start }),
      },
    );
  };
  saveButton.onclick = submit;
  input.oninput = draw;
  input.onkeydown = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  };
  dialog.replaceChildren(
    h("div", { class: "sheet-handle", "aria-hidden": "true" }),
    kinds,
    input,
    preview,
    h(
      "div",
      { class: "quick-actions" },
      textButton("More options", () => {
        const st = state();
        dialog.close();
        openEditor({
          date: defaultDate,
          title: st.text.trim(),
          task: st.isTask,
          calendarId: st.calendar.id,
          draft: st.result.ok ? st.result.draft : null,
        });
      }),
      saveButton,
    ),
  );
  dialog.oncancel = () => closeOpenMenu?.();
  dialog.onclick = (e) => {
    if (e.target === dialog) {
      const r = dialog.getBoundingClientRect();
      if (
        e.clientY < r.top ||
        e.clientY > r.bottom ||
        e.clientX < r.left ||
        e.clientX > r.right
      )
        dialog.close();
    }
  };
  showModal(dialog);
  // Drawn once it's open, so the text box can measure its height.
  draw();
  input.focus();
}

/* ---------- Profile photo and account card ---------- */
let photo = null, username = null;
const initial = () => (username || email || "?").trim().charAt(0).toUpperCase();
/** A stable color for the letter avatar, picked from the event colors by the email address. */
function avatarColor() {
  let hash = 0;
  for (const ch of email || "") hash = (hash * 31 + ch.charCodeAt(0)) | 0;
  return EVENT_COLORS[Math.abs(hash) % EVENT_COLORS.length].argb;
}
function avatar(size) {
  if (photo)
    return h("img", {
      class: "avatar-img",
      src: photo,
      alt: "",
      width: size,
      height: size,
    });
  const color = avatarColor();
  return h("span", {
    class: "avatar-letter",
    text: initial(),
    style: {
      background: css(color),
      color: contentColor(rgbOf(color)),
      width: size + "px",
      height: size + "px",
      "font-size": Math.round(size * 0.45) + "px",
    },
  });
}
function renderAvatar() {
  $("avatar").replaceChildren(avatar(32));
  $("avatar").title = username || email || "Account";
  if ($("account").open) renderAccount();
}
async function loadProfile() {
  renderAvatar();
  try {
    ({ photo, username } = await api("profile"));
  } catch {
    photo = null;
  }
  renderAvatar();
}
function openAccount() {
  renderAccount();
  showModal($("account"));
}
function renderAccount() {
  const dialog = $("account");
  const usernameInput = h("input", {
    type: "text", value: username || "", minlength: "3", maxlength: "30",
    pattern: "[A-Za-z0-9_]{3,30}", autocomplete: "off",
    placeholder: "3 to 30 letters, numbers or underscores",
  });
  const usernameForm = h("form", {
    class: "account-username",
    onsubmit: async (e) => {
      e.preventDefault();
      const button = usernameForm.querySelector("button");
      button.disabled = true;
      try {
        ({ username } = await api("profile", { username: usernameInput.value.trim() }));
        renderAvatar();
        snackbar("Username updated");
      } catch (error) {
        snackbar(error.message);
      } finally { button.disabled = false; }
    },
  }, h("label", { class: "field" }, h("span", { text: "Username" }), usernameInput),
    h("button", { type: "submit", class: "outlined", text: "Save" }));
  dialog.replaceChildren(
    iconButton(I.close, "Close", () => dialog.close(), {
      class: "icon account-close",
    }),
    h("p", { class: "account-email body-medium", text: email || "" }),
    h(
      "button",
      {
        type: "button",
        class: "account-photo",
        "aria-label": photo ? "Change profile photo" : "Add profile photo",
        title: photo ? "Change profile photo" : "Add profile photo",
        onclick: () => $("photo-input").click(),
      },
      avatar(80),
      h("span", { class: "camera" }, icon(I.camera)),
    ),
    h("p", {
      class: "account-hello title-large",
      text: "Hi, " + (username || (email || "").split("@")[0]) + "!",
    }),
    usernameForm,
    h(
      "div",
      { class: "account-actions" },
      h("button", {
        type: "button",
        class: "outlined",
        text: photo ? "Change photo" : "Add photo",
        onclick: () => $("photo-input").click(),
      }),
      photo &&
        h("button", {
          type: "button",
          class: "outlined",
          text: "Remove photo",
          onclick: () => setPhoto(null),
        }),
    ),
    h(
      "div",
      { class: "account-links" },
      h(
        "button",
        {
          type: "button",
          onclick: () => {
            dialog.close();
            openSettings();
          },
        },
        icon(I.settings),
        "Settings",
      ),
      h(
        "button",
        { type: "button", onclick: signOut },
        icon(I.logout),
        "Sign out",
      ),
    ),
  );
  dialog.onclick = (e) => {
    if (e.target === dialog) {
      const r = dialog.getBoundingClientRect();
      if (
        e.clientY < r.top ||
        e.clientY > r.bottom ||
        e.clientX < r.left ||
        e.clientX > r.right
      )
        dialog.close();
    }
  };
}
async function setPhoto(value) {
  try {
    ({ photo } = await api("profile", { photo: value }));
    renderAvatar();
    snackbar(value ? "Profile photo updated" : "Profile photo removed");
  } catch (e) {
    snackbar(e.message);
  }
}
/** Crops the picked image to a 256-pixel square JPEG before uploading it. */
$("photo-input").onchange = async () => {
  const file = $("photo-input").files[0];
  $("photo-input").value = "";
  if (!file) return;
  try {
    const bitmap = await createImageBitmap(file);
    const side = Math.min(bitmap.width, bitmap.height);
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 256;
    canvas
      .getContext("2d")
      .drawImage(
        bitmap,
        (bitmap.width - side) / 2,
        (bitmap.height - side) / 2,
        side,
        side,
        0,
        0,
        256,
        256,
      );
    let quality = 0.9,
      data = canvas.toDataURL("image/jpeg", quality);
    while (data.length > 190000 && quality > 0.3)
      data = canvas.toDataURL("image/jpeg", (quality -= 0.15));
    await setPhoto(data);
  } catch {
    snackbar("That image couldn't be read. Try a JPEG or PNG.");
  }
};
I.camera =
  "M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4ZM9 2 7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9Zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5Z";
I.settings =
  "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.49.49 0 0 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.61l-2.01-1.58ZM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2Z";
I.logout =
  "m17 7-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5-5-5ZM4 5h8V3H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h8v-2H4V5Z";

/* ---------- Start ---------- */
// The first screen waits for the font, events and photo so a reload fades in finished.
// A slow server only holds it back briefly; the rest then arrives as usual.
const ready = [document.fonts.load('1em "Google Sans Flex"')];
if (token) ready.push(loadProfile(), sync().catch(() => {}));
Promise.race([
  Promise.all(ready),
  new Promise((done) => setTimeout(done, 1500)),
]).then(signedIn);
if (wide.matches) setDrawer(!sidebarHidden);
setInterval(() => {
  if (
    token &&
    !document.hidden &&
    !busy &&
    !$("editor").open &&
    !$("alert").open
  )
    sync().catch(() => {});
}, 10000);
// Past events fade and the now line moves as time passes.
setInterval(() => {
  if (token && !document.hidden && !document.querySelector("dialog[open]"))
    render();
}, 60000);
