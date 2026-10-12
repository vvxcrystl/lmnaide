package dev.lmnaide.calendar.ui.event

import dev.lmnaide.calendar.data.EventEntity

internal data class EventDraftMerge(
    val event: EventEntity,
    val conflicts: List<String>,
)

/** Applies only fields changed in the editor over the newest database version. */
internal fun mergeEventDraft(
    original: EventEntity,
    latest: EventEntity,
    opened: EditorState,
    draftState: EditorState,
    draft: EventEntity,
): EventDraftMerge {
    val conflicts = mutableListOf<String>()

    fun changed(userChanged: Boolean, remoteChanged: Boolean, label: String): Boolean {
        if (userChanged && remoteChanged) conflicts += label
        return userChanged
    }

    val title = changed(draftState.title != opened.title, original.title != latest.title, "title")
    val description = changed(draftState.description != opened.description, original.description != latest.description, "notes")
    val location = changed(draftState.location != opened.location, original.location != latest.location, "location")
    val calendar = changed(draftState.calendarId != opened.calendarId, original.calendarId != latest.calendarId, "calendar")
    val color = changed(draftState.color != opened.color, original.color != latest.color, "color")
    val reminders = changed(draftState.reminders != opened.reminders, original.reminders != latest.reminders, "reminders")
    val kind = changed(draftState.kind != opened.kind, original.kind != latest.kind, "event type")
    val importance = changed(draftState.importance != opened.importance, original.importance != latest.importance, "importance")
    val recurrence = changed(draftState.recurrence != opened.recurrence, original.recurrence != latest.recurrence, "repeat rule")
    val recurrenceUntil = changed(
        draftState.recurrenceUntil != opened.recurrenceUntil,
        original.recurrenceUntil != latest.recurrenceUntil,
        "repeat end date",
    )
    val people = draftState.people != opened.people

    val allDayChanged = draftState.allDay != opened.allDay
    val startChanged = allDayChanged || draftState.startDate != opened.startDate || draftState.startTime != opened.startTime
    val endChanged = allDayChanged || draftState.endDate != opened.endDate || draftState.endTime != opened.endTime
    val remoteStartChanged = original.start != latest.start || original.allDay != latest.allDay
    val remoteEndChanged = original.end != latest.end || original.allDay != latest.allDay
    if (startChanged && remoteStartChanged) conflicts += "start time"
    if (endChanged && remoteEndChanged) conflicts += "end time"

    return EventDraftMerge(
        event = latest.copy(
            calendarId = if (calendar) draft.calendarId else latest.calendarId,
            title = if (title) draft.title else latest.title,
            description = if (description) draft.description else latest.description,
            location = if (location) draft.location else latest.location,
            start = if (startChanged) draft.start else latest.start,
            end = if (endChanged) draft.end else latest.end,
            allDay = if (allDayChanged) draft.allDay else latest.allDay,
            color = if (color) draft.color else latest.color,
            recurrence = if (recurrence) draft.recurrence else latest.recurrence,
            recurrenceUntil = if (recurrenceUntil) draft.recurrenceUntil else latest.recurrenceUntil,
            reminders = if (reminders) draft.reminders else latest.reminders,
            kind = if (kind) draft.kind else latest.kind,
            importance = if (importance) draft.importance else latest.importance,
            people = if (people) draft.people else latest.people,
            // These fields are not edited here; retain completions and exceptions from the newest row.
            exceptions = latest.exceptions,
            completions = latest.completions,
        ),
        conflicts = conflicts.distinct(),
    )
}
