package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.EventEntity
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/** A single instance of an event on the calendar, in local time. */
data class Occurrence(
    val event: EventEntity,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val color: Int,
    val calendarName: String,
) {
    val startDate: LocalDate = start.toLocalDate()

    /** Last day the occurrence covers; an end at midnight belongs to the previous day. */
    val lastDate: LocalDate =
        if (end > start && end.toLocalTime() == LocalTime.MIDNIGHT) end.toLocalDate().minusDays(1)
        else maxOf(end.toLocalDate(), startDate)

    val allDay: Boolean get() = event.allDay

    val isTask: Boolean get() = event.isTask

    val completed: Boolean = event.isTask && startDate.toEpochDay() in event.completions

    /** Shown in the all-day lane instead of the hourly grid. */
    val inAllDayLane: Boolean = event.allDay || Duration.between(start, end) >= Duration.ofHours(24)

    /** Identifies this instance within its series, independent of time zone. */
    val instanceId: Long = start.toEpochSecond(ZoneOffset.UTC)

    val key: String = "${event.id}@$instanceId"

    fun occursOn(date: LocalDate): Boolean = date in startDate..lastDate

    companion object {
        fun instanceStart(instanceId: Long): LocalDateTime =
            LocalDateTime.ofEpochSecond(instanceId, 0, ZoneOffset.UTC)
    }
}
