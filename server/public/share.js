const title = document.getElementById("title");
const content = document.getElementById("content");
const refresh = document.getElementById("refresh");
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
async function load() {
  refresh.disabled = true;
  try {
    const token = location.pathname.split("/").pop();
    const res = await fetch("/api/v1/shared/" + encodeURIComponent(token));
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "Unable to open this event.");
    const e = data.event;
    title.textContent = e.title;
    document.title = e.title + " · lmnaide";
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
  } catch (error) {
    title.textContent = "Event unavailable";
    document.title = "Event unavailable · lmnaide";
    content.replaceChildren(section("", error.message));
  } finally {
    refresh.disabled = false;
  }
}
refresh.onclick = load;
load();
