package dev.lmnaide.calendar.ui.event

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.CalendarRepository
import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.data.EventKind
import dev.lmnaide.calendar.data.Importance
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.data.SettingsRepository
import dev.lmnaide.calendar.data.SyncLimits
import dev.lmnaide.calendar.domain.SmartEventDraft
import dev.lmnaide.calendar.domain.SmartEventParser
import dev.lmnaide.calendar.domain.SmartEventResult
import dev.lmnaide.calendar.domain.EditScope
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.ui.EventLink
import dev.lmnaide.calendar.ui.Routes
import dev.lmnaide.calendar.ui.common.appViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class EditorState(
    val title: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(10, 0),
    val recurrence: Recurrence = Recurrence.NONE,
    val recurrenceUntil: LocalDate? = null,
    val location: String = "",
    val description: String = "",
    val people: List<dev.lmnaide.calendar.contacts.EventPerson> = emptyList(),
    val calendarId: Long = 0,
    val color: Int? = null,
    val reminders: List<Int> = emptyList(),
    val kind: EventKind = EventKind.EVENT,
    val importance: Importance? = null,
) {
    val isTask: Boolean get() = kind == EventKind.TASK

    val start: LocalDateTime get() = LocalDateTime.of(startDate, if (allDay) LocalTime.MIDNIGHT else startTime)

    /** Tasks have no duration: they end where they start, or after their one day if all-day. */
    val end: LocalDateTime
        get() = when {
            isTask && allDay -> startDate.plusDays(1).atStartOfDay()
            isTask -> start
            allDay -> endDate.plusDays(1).atStartOfDay()
            else -> LocalDateTime.of(endDate, endTime)
        }

    val validationMessage: String?
        get() = when {
            title.length > SyncLimits.TITLE -> "Title must be 1,000 characters or fewer"
            description.length > SyncLimits.DESCRIPTION -> "Notes must be 20,000 characters or fewer"
            location.length > SyncLimits.LOCATION -> "Location must be 2,000 characters or fewer"
            !SyncLimits.validReminders(reminders) -> "Use up to 100 reminders, each within one year"
            !SyncLimits.validDate(startDate) || !SyncLimits.validDate(endDate) ||
                recurrenceUntil?.let { !SyncLimits.validDate(it) } == true -> "Dates must be between 1900 and 2200"
            !isTask && (if (allDay) endDate < startDate else end < start) -> "The event ends before it starts"
            else -> null
        }

    val isValid: Boolean get() = validationMessage == null
}

class EventEditorViewModel(
    private val repository: CalendarRepository,
    settingsRepository: SettingsRepository,
    handle: SavedStateHandle,
) : ViewModel() {
    private val eventId: Long = handle["eventId"] ?: 0L
    private val instanceId: Long = handle["instance"] ?: Routes.NO_VALUE
    private val initialDate: Long = handle["date"] ?: Routes.NO_VALUE
    private val initialMinute: Int = handle["minute"] ?: -1
    private val initialAllDay: Boolean = handle["allDay"] ?: false
    private val initialTask: Boolean = handle["task"] ?: false
    private val settings = settingsRepository.settings.value

    /** Text typed into quick add before choosing More options. */
    private val initialPeople = dev.lmnaide.calendar.data.Converters().stringToPeople(handle["people"] ?: "[]")
    private val initialText: String = handle["text"] ?: ""
    private val initialCalendar: Long = handle["calendar"] ?: Routes.NO_VALUE
    var isSaving by mutableStateOf(false)
        private set

    val isNew = eventId == 0L
    val autoMatchPeople get() = isNew && initialText.isBlank()
    val use24Hour = settings.use24Hour

    val calendars: StateFlow<List<CalendarEntity>> = repository.calendars
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var state by mutableStateOf<EditorState?>(null)
        private set

    private var initial: EditorState? = null

    /** The stored event being edited, if any. */
    private var series: EventEntity? = null
    /** Immutable local snapshot used to distinguish the draft from later sync updates. */
    private var originalEvent: EventEntity? = null

    /** Start of the occurrence that was opened, when editing one instance of a series. */
    private var occurrenceStart: LocalDateTime? = null

    var conflictMessage by mutableStateOf<String?>(null)
        private set
    private var pendingSave: Pair<EventEntity, EditScope?>? = null

    val isDirty: Boolean get() = state != null && state != initial

    /** Editing one instance of a series requires asking which instances the change applies to. */
    val needsScope: Boolean get() = series?.recurrence?.let { it != Recurrence.NONE } == true && occurrenceStart != null

    /** "This event" makes no sense once the repeat rule itself is changed. */
    val scopeOptions: List<EditScope>
        get() = if (state?.recurrence != series?.recurrence) listOf(EditScope.FOLLOWING, EditScope.ALL) else EditScope.entries

    init {
        viewModelScope.launch {
            val loaded = if (isNew) newEventState() else existingEventState()
            initial = loaded
            state = loaded
        }
    }

    private suspend fun newEventState(): EditorState {
        val calendars = repository.calendars.first { it.isNotEmpty() }
        val now = LocalDateTime.now()
        val date = if (initialDate == Routes.NO_VALUE) now.toLocalDate() else LocalDate.ofEpochDay(initialDate)
        val start = when {
            initialMinute >= 0 -> date.atStartOfDay().plusMinutes(initialMinute.toLong())
            date == now.toLocalDate() && now.hour < 23 -> date.atTime(now.hour + 1, 0)
            else -> date.atTime(9, 0)
        }
        val end = start.plusMinutes(settings.defaultDurationMinutes.toLong())
        val calendar = calendars.firstOrNull { it.id == initialCalendar }
            ?: calendars.firstOrNull { it.id == settings.defaultCalendarId }
            ?: calendars.first()
        val blank = EditorState(
            people = initialPeople,
            allDay = initialAllDay,
            startDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = if (initialAllDay) start.toLocalDate() else end.toLocalDate(),
            endTime = end.toLocalTime(),
            calendarId = calendar.id,
            importance = calendar.defaultImportance,
            // Tasks remind when they're due; events use the default notification.
            reminders = if (initialTask) listOf(0) else listOfNotNull(settings.defaultReminderMinutes),
            kind = if (initialTask) EventKind.TASK else EventKind.EVENT,
        )
        if (initialText.isBlank()) return blank
        // Keep whatever quick add understood; otherwise the text becomes the title.
        val parsed = SmartEventParser.parse(initialText, now.toLocalDate(), settings.defaultDurationMinutes, initialTask, date)
        return when (parsed) {
            is SmartEventResult.Success -> blank.withDraft(parsed.event)
            is SmartEventResult.NeedsDetails -> blank.copy(title = initialText)
        }
    }

    private fun EditorState.withDraft(draft: SmartEventDraft) = copy(
        title = draft.title,
        startDate = draft.start.toLocalDate(), startTime = draft.start.toLocalTime(),
        endDate = if (draft.allDay) draft.end.toLocalDate().minusDays(1) else draft.end.toLocalDate(),
        endTime = draft.end.toLocalTime(), allDay = draft.allDay, recurrence = draft.recurrence,
    )

    private suspend fun existingEventState(): EditorState {
        val event = checkNotNull(repository.getEvent(eventId))
        series = event
        originalEvent = event
        val baseStart = event.localStart()
        val length = Duration.between(baseStart, maxOf(event.localEnd(), baseStart))
        val start = if (event.recurrence != Recurrence.NONE && instanceId != Routes.NO_VALUE && instanceId != 0L) {
            Occurrence.instanceStart(instanceId).also { occurrenceStart = it }
        } else {
            baseStart
        }
        val end = start.plus(length)
        return EditorState(
            title = event.title,
            allDay = event.allDay,
            startDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            // All-day events end at midnight after their last day.
            endDate = if (event.allDay) end.toLocalDate().minusDays(1).coerceAtLeast(start.toLocalDate()) else end.toLocalDate(),
            endTime = end.toLocalTime(),
            recurrence = event.recurrence,
            recurrenceUntil = event.recurrenceUntil?.let(LocalDate::ofEpochDay),
            location = event.location,
            description = event.description,
            people = event.people,
            calendarId = event.calendarId,
            color = event.color,
            reminders = event.reminders.sorted(),
            kind = event.kind,
            importance = event.importance,
        )
    }

    /** A new item follows its calendar's default importance, unless it was set by hand. */
    fun setCalendar(calendar: CalendarEntity) = update { s ->
        val old = calendars.value.firstOrNull { it.id == s.calendarId }
        val follows = isNew && s.importance == old?.defaultImportance
        s.copy(calendarId = calendar.id, importance = if (follows) calendar.defaultImportance else s.importance)
    }

    /** Switching between event and task keeps the date and time, and picks sensible reminders. */
    fun setKind(kind: EventKind) = update { s ->
        if (s.kind == kind) return@update s
        val reminders = when {
            kind == EventKind.TASK && s.reminders.isEmpty() -> listOf(0)
            else -> s.reminders
        }
        s.copy(kind = kind, reminders = reminders)
    }

    fun update(transform: (EditorState) -> EditorState) {
        state = state?.let(transform)
    }

    /** Moving the start keeps the event's length, like most calendars do. */
    fun setStart(date: LocalDate = state!!.startDate, time: LocalTime = state!!.startTime) = update { s ->
        val newStart = LocalDateTime.of(date, time)
        if (s.allDay) {
            val days = ChronoUnit.DAYS.between(s.startDate, s.endDate)
            s.copy(startDate = date, startTime = time, endDate = date.plusDays(days))
        } else {
            val newEnd = newStart.plus(Duration.between(s.start, s.end))
            s.copy(startDate = date, startTime = time, endDate = newEnd.toLocalDate(), endTime = newEnd.toLocalTime())
        }
    }

    fun setEnd(date: LocalDate = state!!.endDate, time: LocalTime = state!!.endTime) = update {
        it.copy(endDate = date, endTime = time)
    }

    fun save(scope: EditScope?, onSaved: (EventLink) -> Unit) {
        val s = state ?: return
        if (!s.isValid || isSaving) return
        isSaving = true
        viewModelScope.launch {
            try {
                val existing = series
                val original = originalEvent
                val edited = EventEntity(
                    id = eventId,
                    calendarId = s.calendarId,
                    title = SmartEventParser.cleanTitle(s.title),
                    description = s.description.trim(),
                    people = s.people,
                    location = s.location.trim(),
                    start = EventEntity.toStored(s.start, s.allDay),
                    end = EventEntity.toStored(s.end, s.allDay),
                    allDay = s.allDay,
                    color = s.color,
                    recurrence = s.recurrence,
                    recurrenceUntil = s.recurrenceUntil?.toEpochDay()?.takeIf { s.recurrence != Recurrence.NONE },
                    exceptions = existing?.exceptions.orEmpty(),
                    reminders = s.reminders.distinct().sorted(),
                    kind = s.kind,
                    completions = existing?.completions.orEmpty(),
                    importance = s.importance,
                )
                val latest = if (existing == null) null else repository.getEvent(eventId)
                val merged = if (original != null && latest != null && initial != null) {
                    mergeEventDraft(original, latest, initial!!, s, edited)
                } else null
                if (merged?.conflicts?.isNotEmpty() == true) {
                    pendingSave = merged.event to scope
                    conflictMessage = "This event changed on another device in ${merged.conflicts.joinToString()}. Saving keeps your edits to those fields."
                    return@launch
                }
                persist(merged?.event ?: edited, scope, s, onSaved)
            } finally {
                isSaving = false
            }
        }
    }

    fun confirmConflict(onSaved: (EventLink) -> Unit) {
        val pending = pendingSave ?: return
        val s = state ?: return
        pendingSave = null
        conflictMessage = null
        isSaving = true
        viewModelScope.launch {
            try {
                persist(pending.first, pending.second, s, onSaved)
            } finally {
                isSaving = false
            }
        }
    }

    fun cancelConflict() {
        pendingSave = null
        conflictMessage = null
    }

    private suspend fun persist(
        event: EventEntity,
        scope: EditScope?,
        s: EditorState,
        onSaved: (EventLink) -> Unit,
    ) {
        val existing = series
        val latest = if (existing == null) null else repository.getEvent(eventId)
        val opened = occurrenceStart
        val original = originalEvent
        val baseline = initial
        val occurrenceDraft = if (
            latest != null && original != null && baseline != null && opened != null &&
                scope in listOf(EditScope.THIS, EditScope.FOLLOWING)
        ) alignOccurrence(original, latest, event, opened, baseline, s) else event
        val id = when {
            existing == null || latest == null || opened == null -> repository.saveEvent(event)
            scope == EditScope.THIS -> repository.replaceOccurrence(
                latest,
                opened.toLocalDate(),
                occurrenceDraft.copy(recurrence = Recurrence.NONE, recurrenceUntil = null, exceptions = emptyList()),
            )
            scope == EditScope.FOLLOWING -> repository.replaceFollowing(latest, opened.toLocalDate(), occurrenceDraft)
            else -> repository.saveEvent(shiftSeries(latest, opened, s, event))
        }
        onSaved(EventLink(id, s.start.toEpochSecond(ZoneOffset.UTC)))
    }

    private fun alignOccurrence(
        original: EventEntity,
        latest: EventEntity,
        draft: EventEntity,
        opened: LocalDateTime,
        baseline: EditorState,
        current: EditorState,
    ): EventEntity {
        val startChanged = current.allDay != baseline.allDay || current.startDate != baseline.startDate || current.startTime != baseline.startTime
        val endChanged = current.allDay != baseline.allDay || current.endDate != baseline.endDate || current.endTime != baseline.endTime
        val originalStart = original.localStart()
        val latestStart = latest.localStart()
        val dayOffset = ChronoUnit.DAYS.between(originalStart.toLocalDate(), opened.toLocalDate())
        val start = if (startChanged) draft.localStart() else latestStart.plusDays(dayOffset)
        val latestDuration = Duration.between(latestStart, maxOf(latest.localEnd(), latestStart))
        val end = if (endChanged) draft.localEnd() else start.plus(latestDuration)
        return draft.copy(
            start = EventEntity.toStored(start, draft.allDay),
            end = EventEntity.toStored(end, draft.allDay),
        )
    }

    /**
     * Applies an edit made on one occurrence to the whole series: the series start moves by
     * the same number of days the occurrence was moved, and takes the edited time and length.
     */
    private fun shiftSeries(series: EventEntity, opened: LocalDateTime, s: EditorState, edited: EventEntity): EventEntity {
        val baseline = initial ?: return edited
        val startChanged = s.allDay != baseline.allDay || s.startDate != baseline.startDate || s.startTime != baseline.startTime
        val endChanged = s.allDay != baseline.allDay || s.endDate != baseline.endDate || s.endTime != baseline.endTime
        if (!startChanged && !endChanged) return edited.copy(exceptions = series.exceptions)
        val shiftDays = ChronoUnit.DAYS.between(opened.toLocalDate(), s.startDate)
        val latestStart = series.localStart()
        val seriesDate = latestStart.toLocalDate().plusDays(shiftDays)
        val start = LocalDateTime.of(seriesDate, if (startChanged) s.start.toLocalTime() else latestStart.toLocalTime())
        val duration = if (endChanged) Duration.between(s.start, s.end) else Duration.between(latestStart, maxOf(series.localEnd(), latestStart))
        val end = start.plus(duration)
        return edited.copy(
            start = EventEntity.toStored(start, s.allDay),
            end = EventEntity.toStored(end, s.allDay),
            exceptions = if (shiftDays == 0L) series.exceptions else emptyList(),
        )
    }

    companion object {
        val Factory = appViewModelFactory { container, handle ->
            EventEditorViewModel(container.repository, container.settings, handle)
        }
    }
}
