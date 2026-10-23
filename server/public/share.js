import { createCalendarActions } from "./calendar-actions.js";

const title = document.getElementById("title");
const content = document.getElementById("content");
const refresh = document.getElementById("refresh");
const actions = document.getElementById("calendar-actions-container");
let calendarActions;

const date = (value, allDay) =>
  new Intl.DateTimeFormat(undefined, {
    dateStyle: "full",
    ...(allDay ? { timeZone: "UTC" } : { timeStyle: "short" }),
  }).format(value);
function section(label, text) {
  const row = document.createElement("section");
  row.className = "shared-section";
  const heading = document.createElement("h2");
  heading.className = "label-large variant";
  heading.textContent = label;
  const body = document.createElement("p");
  body.className = "body-large shared-notes";
  body.textContent = text;
  row.append(heading, body);
  return row;
}
async function signedInToInstance() {
  try {
    const sessionToken = sessionStorage.getItem("token");
    if (!sessionToken) return false;
    const response = await fetch("/api/v1/profile", {
      headers: { Authorization: "Bearer " + sessionToken },
      cache: "no-store",
      signal: AbortSignal.timeout(1500),
    });
    return response.ok && sessionStorage.getItem("token") === sessionToken;
  } catch {
    // A public event stays usable when storage or the session check is unavailable.
    return false;
  }
}
async function load() {
  refresh.disabled = true;
  calendarActions?.dispose();
  actions.hidden = true;
  try {
    const token = location.pathname.split("/").pop();
    const [res, signedIn] = await Promise.all([
      fetch("/api/v1/shared/" + encodeURIComponent(token)),
      signedInToInstance(),
    ]);
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "Unable to open this event.");
    const e = data.event;
    title.textContent = e.title;
    document.title = e.title + " · calander";
    const last = e.allDay ? Math.max(e.start, e.end - 86400000) : e.end;
    const when =
      date(e.start, e.allDay) +
      (last !== e.start ? " – " + date(last, e.allDay) : "");
    const rows = [section(e.allDay ? "All-day" : "When", when)];
    if (!e.allDay)
      rows.push(
        section("Time zone", Intl.DateTimeFormat().resolvedOptions().timeZone),
      );
    if (e.recurrence !== "NONE") {
      const repeat = {
        DAILY: "Daily",
        WEEKDAYS: "Every weekday",
        WEEKLY: "Weekly",
        MONTHLY: "Monthly",
        YEARLY: "Yearly",
      }[e.recurrence];
      rows.push(
        section(
          "Recurring series",
          repeat +
            (e.recurrenceUntil != null
              ? ", until " + date(e.recurrenceUntil * 86400000, true)
              : "") +
            ". Dates above show the start of the series. Individual occurrences may be skipped.",
        ),
      );
    }
    if (e.kind === "TASK") rows.push(section("Type", "Task"));
    if (e.location) rows.push(section("Location", e.location));
    if (e.description) rows.push(section("Notes", e.description));
    content.replaceChildren(...rows);
    const fallback = new URL("/", location.origin);
    fallback.searchParams.set("addShared", token);
    calendarActions = createCalendarActions({
      event: e, webUrl: fallback.href, signedIn,
      downloadUrl: "/api/v1/shared/" + encodeURIComponent(token) + "/event.ics",
    });
    actions.replaceChildren(calendarActions.element);
    actions.hidden = false;
  } catch (error) {
    title.textContent = "Event unavailable";
    document.title = "Event unavailable · calander";
    content.replaceChildren(section("", error.message));
  } finally {
    refresh.disabled = false;
  }
}
refresh.onclick = load;
window.addEventListener("pageshow", (event) => {
  if (event.persisted) load();
});
// Match the app's first reveal: wait briefly for the font and event to settle.
Promise.race([
  Promise.allSettled([document.fonts.load('1em "Google Sans Flex"'), load()]),
  new Promise((done) => setTimeout(done, 1500)),
]).then(() => {
  document.getElementById("shared-event").hidden = false;
});
