package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.data.EventKind
import dev.lmnaide.calendar.data.Recurrence
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RecurrenceExpanderTest {
    private val base = LocalDate.of(2024, 1, 31)
    private val zone = ZoneId.of("America/New_York")

    private fun event(rule: Recurrence, allDay: Boolean = false, days: Long = 0): EventEntity {
        val start = base.atTime(if (allDay) 0 else 9, 0)
        val end = if (allDay) start.plusDays(days + 1) else start.plusDays(days).plusHours(2)
        return EventEntity(
            id = 1, calendarId = 1, title = "Recurring event", allDay = allDay,
            start = EventEntity.toStored(start, allDay, zone),
            end = EventEntity.toStored(end, allDay, zone), recurrence = rule,
        )
    }

    @Test fun firstAgreesWithExpansionAcrossRulesRangesAndExceptions() {
        for (rule in Recurrence.entries) for (allDay in listOf(false, true)) for (days in listOf(0L, 3L)) {
            val original = event(rule, allDay, days)
            for (until in listOf(null, base.plusMonths(3).toEpochDay())) {
                val event = original.copy(
                    recurrenceUntil = until,
                    exceptions = listOf(base.toEpochDay(), base.plusDays(1).toEpochDay(), base.plusMonths(2).toEpochDay()),
                )
                for (offset in listOf(-7L, 0L, 2L, 39L, 366L)) {
                    val from = base.plusDays(offset)
                    val to = from.plusYears(5)
                    assertEquals("$rule/$allDay/$days/$until/$offset",
                        RecurrenceExpander.expand(event, 42, "Personal", from, to, zone).firstOrNull(),
                        RecurrenceExpander.first(event, 42, "Personal", from, to, zone))
                }
            }
        }
    }

    @Test fun monthlySkipsMissingDatesAndYearlySkipsNonLeapYears() {
        val monthly = RecurrenceExpander.first(event(Recurrence.MONTHLY), 0, "", base.plusDays(1), base.plusMonths(3), zone)
        assertEquals(LocalDate.of(2024, 3, 31), monthly?.startDate)
        val leap = event(Recurrence.YEARLY, allDay = true).copy(
            start = EventEntity.toStored(LocalDate.of(2024, 2, 29).atStartOfDay(), true),
            end = EventEntity.toStored(LocalDate.of(2024, 3, 1).atStartOfDay(), true),
        )
        assertEquals(LocalDate.of(2028, 2, 29),
            RecurrenceExpander.first(leap, 0, "", LocalDate.of(2025, 1, 1), LocalDate.of(2030, 1, 1), zone)?.startDate)
    }

    @Test fun ongoingMultidayOccurrenceAndInclusiveUntilArePreserved() {
        val event = event(Recurrence.WEEKLY, allDay = true, days = 3).copy(recurrenceUntil = base.toEpochDay())
        val occurrence = RecurrenceExpander.first(event, 0, "", base.plusDays(2), base.plusYears(1), zone)
        assertEquals(base, occurrence?.startDate)
        assertEquals(base.plusDays(3), occurrence?.lastDate)
        assertNull(RecurrenceExpander.first(event, 0, "", base.plusDays(4), base.plusYears(1), zone))
    }

    @Test fun firstDoesNotConstructTheRestOfTheSeries() {
        var completionChecks = 0
        val completions = object : AbstractList<Long>() {
            override val size = 0
            override fun get(index: Int): Long = throw IndexOutOfBoundsException()
            override fun contains(element: Long): Boolean { completionChecks++; return false }
        }
        val task = event(Recurrence.DAILY).copy(kind = EventKind.TASK, completions = completions)
        assertNotNull(RecurrenceExpander.first(task, 0, "", base.plusDays(1), base.plusYears(5), zone))
        // The base establishes the span, then only the selected occurrence is constructed.
        assertEquals(2, completionChecks)
    }

    @Test fun representativeRetainsPastFallbackColorsAndCalendarName() {
        val event = event(Recurrence.DAILY, allDay = true).copy(recurrenceUntil = base.plusDays(3).toEpochDay())
        val calendar = CalendarEntity(id = 1, name = "Personal", color = 42)
        val occurrence = EventIndex(listOf(event), listOf(calendar)).representative(event, base.plusYears(1))
        assertEquals(base, occurrence?.startDate)
        assertEquals(42, occurrence?.color)
        assertEquals("Personal", occurrence?.calendarName)
    }
}
