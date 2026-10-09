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
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.data.SettingsRepository
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
    val calendarId: Long = 0,
    val color: Int? = null,
    val reminders: List<Int> = emptyList(),
) {
    val start: LocalDateTime get() = LocalDateTime.of(startDate, if (allDay) LocalTime.MIDNIGHT else startTime)

    val end: LocalDateTime
        get() = if (allDay) endDate.plusDays(1).atStartOfDay() else LocalDateTime.of(endDate, endTime)

    val isValid: Boolean get() = if (allDay) endDate >= startDate else end >= start
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
    private val settings = settingsRepository.settings.value

    val isNew = eventId == 0L
    val use24Hour = settings.use24Hour

    val calendars: StateFlow<List<CalendarEntity>> = repository.calendars
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var state by mutableStateOf<EditorState?>(null)
        private set

    private var initial: EditorState? = null

    /** The stored event being edited, if any. */
    private var series: EventEntity? = null

    /** Start of the occurrence that was opened, when editing one instance of a series. */
    private var occurrenceStart: LocalDateTime? = null

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
        return EditorState(
            allDay = initialAllDay,
            startDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = if (initialAllDay) start.toLocalDate() else end.toLocalDate(),
            endTime = end.toLocalTime(),
            calendarId = calendars.firstOrNull { it.id == settings.defaultCalendarId }?.id ?: calendars.first().id,
            reminders = listOfNotNull(settings.defaultReminderMinutes),
        )
    }

    private suspend fun existingEventState(): EditorState {
        val event = checkNotNull(repository.getEvent(eventId))
        series = event
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
            calendarId = event.calendarId,
            color = event.color,
            reminders = event.reminders.sorted(),
        )
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
        if (!s.isValid) return
        viewModelScope.launch {
            val existing = series
            val edited = EventEntity(
                id = eventId,
                calendarId = s.calendarId,
                title = s.title.trim(),
                description = s.description.trim(),
                location = s.location.trim(),
                start = EventEntity.toStored(s.start, s.allDay),
                end = EventEntity.toStored(s.end, s.allDay),
                allDay = s.allDay,
                color = s.color,
                recurrence = s.recurrence,
                recurrenceUntil = s.recurrenceUntil?.toEpochDay()?.takeIf { s.recurrence != Recurrence.NONE },
                exceptions = existing?.exceptions.orEmpty(),
                reminders = s.reminders.distinct().sorted(),
            )
            val instance = s.start.toEpochSecond(ZoneOffset.UTC)
            val opened = occurrenceStart
            val id = when {
                existing == null || opened == null -> repository.saveEvent(edited)
                scope == EditScope.THIS -> repository.replaceOccurrence(
                    existing,
                    opened.toLocalDate(),
                    edited.copy(recurrence = Recurrence.NONE, recurrenceUntil = null, exceptions = emptyList()),
                )
                scope == EditScope.FOLLOWING -> repository.replaceFollowing(existing, opened.toLocalDate(), edited)
                else -> repository.saveEvent(shiftSeries(existing, opened, s, edited))
            }
            onSaved(EventLink(id, instance))
        }
    }

    /**
     * Applies an edit made on one occurrence to the whole series: the series start moves by
     * the same number of days the occurrence was moved, and takes the edited time and length.
     */
    private fun shiftSeries(series: EventEntity, opened: LocalDateTime, s: EditorState, edited: EventEntity): EventEntity {
        val shiftDays = ChronoUnit.DAYS.between(opened.toLocalDate(), s.startDate)
        val seriesDate = series.localStart().toLocalDate().plusDays(shiftDays)
        val start = LocalDateTime.of(seriesDate, s.start.toLocalTime())
        val end = start.plus(Duration.between(s.start, s.end))
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
