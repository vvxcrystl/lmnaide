package dev.lmnaide.calendar.reminders

import dev.lmnaide.calendar.data.Importance
import dev.lmnaide.calendar.domain.Occurrence
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

enum class ClockAlarmStatus { READY, TOO_EARLY, PAST, INELIGIBLE, UNSUPPORTED_TIME }

data class ClockAlarmPlan(val status: ClockAlarmStatus, val alarmAt: ZonedDateTime? = null)

/** Clock's public intent accepts a time of day, not a date. Never request the wrong day. */
object ClockAlarmPolicy {
    const val MINUTES_BEFORE = 30
    val ALL_DAY_TIME: LocalTime = LocalTime.of(9, 0)

    fun reminderMinutes(occurrence: Occurrence): List<Int> =
        (occurrence.event.reminders + if (occurrence.event.importance == Importance.HIGH) listOf(MINUTES_BEFORE) else emptyList())
            .distinct()

    fun alarmAt(occurrence: Occurrence, zone: ZoneId): ZonedDateTime =
        (if (occurrence.allDay) occurrence.start.with(ALL_DAY_TIME) else occurrence.start)
            .atZone(zone).minusMinutes(MINUTES_BEFORE.toLong()).truncatedTo(ChronoUnit.MINUTES)

    /** One minute after yesterday's same wall time, so the next such time is the intended day. */
    fun setupAt(occurrence: Occurrence, zone: ZoneId): ZonedDateTime =
        alarmAt(occurrence, zone).minusDays(1).plusMinutes(1)

    fun plan(occurrence: Occurrence, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): ClockAlarmPlan {
        if (occurrence.event.importance != Importance.HIGH || occurrence.completed) {
            return ClockAlarmPlan(ClockAlarmStatus.INELIGIBLE)
        }
        val at = alarmAt(occurrence, zone)
        if (!at.toInstant().isAfter(now)) return ClockAlarmPlan(ClockAlarmStatus.PAST, at)
        val today = now.atZone(zone).toLocalDate()
        var next = today.atTime(at.toLocalTime()).atZone(zone)
        if (!next.toInstant().isAfter(now)) next = today.plusDays(1).atTime(at.toLocalTime()).atZone(zone)
        // Reject ambiguous wall times too: Clock apps can choose a different overlap offset.
        val unambiguous = zone.rules.getValidOffsets(at.toLocalDateTime()).size == 1
        if (!unambiguous) return ClockAlarmPlan(ClockAlarmStatus.UNSUPPORTED_TIME, at)
        val ready = next.toInstant() == at.toInstant()
        return ClockAlarmPlan(if (ready) ClockAlarmStatus.READY else ClockAlarmStatus.TOO_EARLY, at)
    }
}
