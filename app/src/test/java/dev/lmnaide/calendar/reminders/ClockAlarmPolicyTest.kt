package dev.lmnaide.calendar.reminders

import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.data.EventKind
import dev.lmnaide.calendar.data.Importance
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.domain.RecurrenceExpander
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ClockAlarmPolicyTest {
    private val zone = ZoneId.of("America/Regina")

    private fun occurrence(
        start: String = "2026-10-16T15:00", allDay: Boolean = false,
        importance: Importance? = Importance.HIGH, task: Boolean = false,
        completed: Boolean = false, reminders: List<Int> = emptyList(),
    ): Occurrence {
        val local = LocalDateTime.parse(start)
        val end = if (allDay) local.plusDays(1) else local.plusHours(1)
        val event = EventEntity(
            id = 1, calendarId = 1, title = "Appointment", start = EventEntity.toStored(local, allDay, zone),
            end = EventEntity.toStored(end, allDay, zone), allDay = allDay, importance = importance,
            kind = if (task) EventKind.TASK else EventKind.EVENT,
            completions = if (completed) listOf(local.toLocalDate().toEpochDay()) else emptyList(), reminders = reminders,
        )
        return Occurrence(event, local, end, 0, "")
    }

    private fun at(local: String, zone: ZoneId = this.zone): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    @Test fun todayRequestsThirtyMinutesBeforeStart() {
        val plan = ClockAlarmPolicy.plan(occurrence(), at("2026-10-16T10:00"), zone)
        assertEquals(ClockAlarmStatus.READY, plan.status)
        assertEquals(at("2026-10-16T14:30"), plan.alarmAt?.toInstant())
    }

    @Test fun tomorrowIsNotSafeUntilTodaysSameTimeHasPassed() {
        val occurrence = occurrence("2026-10-17T15:00")
        assertEquals(ClockAlarmStatus.TOO_EARLY, ClockAlarmPolicy.plan(occurrence, at("2026-10-16T14:29"), zone).status)
        assertEquals(ClockAlarmStatus.READY, ClockAlarmPolicy.plan(occurrence, at("2026-10-16T14:31"), zone).status)
    }

    @Test fun neverRequestsAWeekAheadAsTodaysAlarm() {
        assertEquals(ClockAlarmStatus.TOO_EARLY,
            ClockAlarmPolicy.plan(occurrence("2026-10-23T15:00"), at("2026-10-16T10:00"), zone).status)
    }

    @Test fun midnightReminderIsOnPreviousDay() {
        val plan = ClockAlarmPolicy.plan(occurrence("2026-10-17T00:15"), at("2026-10-16T20:00"), zone)
        assertEquals(ClockAlarmStatus.READY, plan.status)
        assertEquals(at("2026-10-16T23:45"), plan.alarmAt?.toInstant())
    }

    @Test fun passedAlarmDoesNotSilentlyRollToTomorrow() {
        for (now in listOf("2026-10-16T14:30", "2026-10-16T14:45", "2026-10-17T09:00")) {
            assertEquals(ClockAlarmStatus.PAST, ClockAlarmPolicy.plan(occurrence(), at(now), zone).status)
        }
    }

    @Test fun allDayUsesEightThirtyInDeviceTimeZone() {
        val plan = ClockAlarmPolicy.plan(occurrence("2026-10-17T00:00", allDay = true), at("2026-10-16T10:00"), zone)
        assertEquals(ClockAlarmStatus.READY, plan.status)
        assertEquals(at("2026-10-17T08:30"), plan.alarmAt?.toInstant())
    }

    @Test fun onlyHighImportanceAndUnfinishedItemsAreEligible() {
        for (importance in listOf(null, Importance.LOW, Importance.MEDIUM)) {
            assertEquals(ClockAlarmStatus.INELIGIBLE,
                ClockAlarmPolicy.plan(occurrence(importance = importance), at("2026-10-16T10:00"), zone).status)
        }
        assertEquals(ClockAlarmStatus.INELIGIBLE,
            ClockAlarmPolicy.plan(occurrence(task = true, completed = true), at("2026-10-16T10:00"), zone).status)
        assertEquals(ClockAlarmStatus.READY,
            ClockAlarmPolicy.plan(occurrence(task = true), at("2026-10-16T10:00"), zone).status)
    }

    @Test fun highImportanceAddsFallbackEvenWithoutConfiguredRemindersAndDeduplicatesIt() {
        assertEquals(listOf(30), ClockAlarmPolicy.reminderMinutes(occurrence()))
        assertEquals(listOf(10, 30, 60), ClockAlarmPolicy.reminderMinutes(occurrence(reminders = listOf(10, 30, 60, 30))))
        assertEquals(emptyList<Int>(), ClockAlarmPolicy.reminderMinutes(occurrence(importance = Importance.MEDIUM)))
        assertEquals(listOf(15), ClockAlarmPolicy.reminderMinutes(occurrence(importance = Importance.LOW, reminders = listOf(15))))
    }

    @Test fun recurringAlarmUsesSelectedOccurrenceAndRespectsExceptions() {
        val base = occurrence().event.copy(recurrence = Recurrence.DAILY, exceptions = listOf(java.time.LocalDate.parse("2026-10-17").toEpochDay()))
        val day = java.time.LocalDate.parse("2026-10-18")
        val next = RecurrenceExpander.expand(base, 0, "", day.minusDays(1), day, zone).single()
        assertEquals(day, next.startDate)
        assertEquals(ClockAlarmStatus.READY, ClockAlarmPolicy.plan(next, at("2026-10-18T10:00"), zone).status)
        assertEquals(at("2026-10-18T14:30"), ClockAlarmPolicy.alarmAt(next, zone).toInstant())
    }

    @Test fun setupWakeupFallsInsideTheSafeDayAcrossDstChanges() {
        val newYork = ZoneId.of("America/New_York")
        for (start in listOf("2026-03-08T09:00", "2026-11-01T09:00", "2026-10-16T15:00")) {
            val occurrence = occurrence(start)
            val setup = ClockAlarmPolicy.setupAt(occurrence, newYork).toInstant()
            assertEquals(start, ClockAlarmStatus.READY, ClockAlarmPolicy.plan(occurrence, setup, newYork).status)
            assertEquals(start, ClockAlarmStatus.TOO_EARLY,
                ClockAlarmPolicy.plan(occurrence, setup.minusSeconds(120), newYork).status)
        }
    }

    @Test fun thirtyMinutesMeansElapsedTimeAcrossSpringClockJump() {
        val newYork = ZoneId.of("America/New_York")
        val occurrence = occurrence("2026-03-08T03:10")
        val alarm = ClockAlarmPolicy.alarmAt(occurrence, newYork)
        assertEquals("01:40", alarm.toLocalTime().toString())
        assertEquals(Duration.ofMinutes(30), Duration.between(alarm.toInstant(), occurrence.start.atZone(newYork).toInstant()))
        assertEquals(ClockAlarmStatus.READY, ClockAlarmPolicy.plan(occurrence, at("2026-03-08T01:00", newYork), newYork).status)
    }

    @Test fun ambiguousDstWallTimeIsNotSentToAClockThatMayChooseAnotherOffset() {
        val newYork = ZoneId.of("America/New_York")
        assertEquals(ClockAlarmStatus.UNSUPPORTED_TIME,
            ClockAlarmPolicy.plan(occurrence("2026-11-01T02:00"), at("2026-11-01T00:00", newYork), newYork).status)
    }

    @Test fun minutePrecisionNeverSetsAnAlarmAfterItsReminderTime() {
        val occurrence = occurrence("2026-10-16T15:00:45")
        val plan = ClockAlarmPolicy.plan(occurrence, at("2026-10-16T10:00"), zone)
        assertEquals(at("2026-10-16T14:30"), plan.alarmAt?.toInstant())
        assertEquals(ClockAlarmStatus.PAST, ClockAlarmPolicy.plan(occurrence, at("2026-10-16T14:30:01"), zone).status)
    }
}
