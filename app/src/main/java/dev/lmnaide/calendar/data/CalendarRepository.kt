package dev.lmnaide.calendar.data

import androidx.room.withTransaction
import dev.lmnaide.calendar.domain.CanadianHolidays
import dev.lmnaide.calendar.ui.common.EventColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime

class CalendarRepository(private val db: AppDatabase) {
    private val calendarDao = db.calendarDao()
    private val eventDao = db.eventDao()

    val calendars: Flow<List<CalendarEntity>> = calendarDao.observeAll()
    val events: Flow<List<EventEntity>> = eventDao.observeAll()

    fun observeEvent(id: Long): Flow<EventEntity?> = eventDao.observe(id)

    suspend fun getEvent(id: Long): EventEntity? = eventDao.get(id)

    suspend fun getAllEvents(): List<EventEntity> = eventDao.getAll()

    suspend fun ensureDefaults() {
        if (calendarDao.count() == 0) {
            calendarDao.insert(CalendarEntity(name = "Personal", color = EventColors.Peacock.argb))
            calendarDao.insert(CalendarEntity(name = "Work", color = EventColors.Grape.argb))
        }
    }

    /** Adds a calendar of Canadian holidays once; deleting or hiding it afterwards sticks. */
    suspend fun seedHolidays(settings: SettingsRepository) {
        if (settings.holidaysSeeded) {
            recolorHolidays(settings)
            return
        }
        db.withTransaction {
            val calendarId = calendarDao.insert(
                CalendarEntity(name = CanadianHolidays.CALENDAR_NAME, color = CanadianHolidays.COLOR),
            )
            CanadianHolidays.all.forEach { holiday ->
                CanadianHolidays.occurrences(holiday).forEach { date ->
                    val start = EventEntity.toStored(date.atStartOfDay(), allDay = true)
                    eventDao.insert(
                        EventEntity(
                            calendarId = calendarId,
                            title = holiday.title,
                            description = holiday.description,
                            start = start,
                            end = EventEntity.toStored(date.plusDays(1).atStartOfDay(), allDay = true),
                            allDay = true,
                            recurrence = if (holiday.fixed) Recurrence.YEARLY else Recurrence.NONE,
                        ),
                    )
                }
            }
        }
        settings.holidaysSeeded = true
        settings.holidaysRecolored = true
    }

    /** The calendar first shipped in red; moves it to the teal color unless it was changed since. */
    private suspend fun recolorHolidays(settings: SettingsRepository) {
        if (settings.holidaysRecolored) return
        calendarDao.observeAll().first()
            .filter { it.name == CanadianHolidays.CALENDAR_NAME && it.color == EventColors.Tomato.argb }
            .forEach { calendarDao.update(it.copy(color = CanadianHolidays.COLOR)) }
        settings.holidaysRecolored = true
    }

    suspend fun saveEvent(event: EventEntity): Long =
        if (event.id == 0L) eventDao.insert(event) else event.id.also { eventDao.update(event) }

    suspend fun deleteEvent(event: EventEntity) = eventDao.delete(event)

    suspend fun saveCalendar(calendar: CalendarEntity) {
        if (calendar.id == 0L) calendarDao.insert(calendar) else calendarDao.update(calendar)
    }

    suspend fun deleteCalendar(calendar: CalendarEntity) = calendarDao.delete(calendar)

    /** Removes a single occurrence from a recurring series. */
    suspend fun deleteOccurrence(event: EventEntity, occurrenceDate: LocalDate) {
        eventDao.update(event.copy(exceptions = event.exceptions + occurrenceDate.toEpochDay()))
    }

    /** Ends a recurring series before [occurrenceDate], deleting it entirely if nothing remains. */
    suspend fun deleteFollowing(event: EventEntity, occurrenceDate: LocalDate) {
        if (occurrenceDate <= event.localStart().toLocalDate()) {
            eventDao.delete(event)
        } else {
            eventDao.update(event.copy(recurrenceUntil = occurrenceDate.minusDays(1).toEpochDay()))
        }
    }

    /** Replaces one occurrence of a series with a standalone [replacement] event. */
    suspend fun replaceOccurrence(series: EventEntity, occurrenceDate: LocalDate, replacement: EventEntity): Long =
        db.withTransaction {
            eventDao.update(series.copy(exceptions = series.exceptions + occurrenceDate.toEpochDay()))
            eventDao.insert(replacement.copy(id = 0))
        }

    /** Splits a series at [occurrenceDate]; [replacement] becomes the new tail of the series. */
    suspend fun replaceFollowing(series: EventEntity, occurrenceDate: LocalDate, replacement: EventEntity): Long =
        db.withTransaction {
            if (occurrenceDate <= series.localStart().toLocalDate()) {
                eventDao.update(replacement.copy(id = series.id))
                series.id
            } else {
                eventDao.update(series.copy(recurrenceUntil = occurrenceDate.minusDays(1).toEpochDay()))
                eventDao.insert(replacement.copy(id = 0, exceptions = emptyList()))
            }
        }

    /** Marks one occurrence of a task as done or not done. */
    suspend fun setCompleted(eventId: Long, occurrenceDate: LocalDate, done: Boolean) {
        val task = eventDao.get(eventId) ?: return
        val day = occurrenceDate.toEpochDay()
        val completions = if (done) (task.completions + day).distinct() else task.completions - day
        eventDao.update(task.copy(completions = completions))
    }

    suspend fun duplicate(event: EventEntity, start: LocalDateTime? = null): Long {
        val copy = if (start == null) {
            event
        } else {
            val length = event.end - event.start
            val stored = EventEntity.toStored(start, event.allDay)
            event.copy(start = stored, end = stored + length)
        }
        return eventDao.insert(copy.copy(id = 0, exceptions = emptyList(), completions = emptyList()))
    }
}
