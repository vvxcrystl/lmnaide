/** Adapt the server's public projection to the records used by the calendar views.
 * These IDs exist only in the visitor's browser, never in the owner's records.
 */
export function publicCalendarRecords(events, hiddenCalendars = {}) {
  const calendars = new Map();
  const records = events.map((event, index) => {
    const name = event.calendar ?? "Busy";
    const calendarId = event.calendar == null ? "public:busy" : "public:calendar:" + name;
    if (!calendars.has(calendarId)) calendars.set(calendarId, {
      kind: "calendar", id: calendarId, version: 0, deleted: false,
      data: { name, color: event.color ?? -10066330, visible: !hiddenCalendars[calendarId], keywords: "", defaultImportance: null },
    });
    return {
      kind: "event", id: "public:event:" + index, version: 0, deleted: false,
      data: {
        ...event, calendarId, description: event.description ?? "", location: event.location ?? "",
        kind: event.kind ?? "EVENT", color: event.color ?? null,
        reminders: [], completions: [], importance: null,
      },
    };
  });
  return [...calendars.values(), ...records];
}
