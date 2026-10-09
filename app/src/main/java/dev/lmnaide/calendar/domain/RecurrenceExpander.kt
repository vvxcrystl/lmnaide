package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.data.Recurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object RecurrenceExpander {
    /** Returns every occurrence of [event] that overlaps the inclusive date range [from]..[to]. */
    fun expand(
        event: EventEntity,
        color: Int,
        calendarName: String,
        from: LocalDate,
        to: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Occurrence> {
        val baseStart = event.localStart(zone)
        val baseEnd = maxOf(event.localEnd(zone), baseStart)
        val base = Occurrence(event, baseStart, baseEnd, color, calendarName)

        if (event.recurrence == Recurrence.NONE) {
            return if (base.lastDate >= from && base.startDate <= to) listOf(base) else emptyList()
        }

        val length = Duration.between(baseStart, baseEnd)
        val spanDays = ChronoUnit.DAYS.between(base.startDate, base.lastDate)
        val until = event.recurrenceUntil?.let(LocalDate::ofEpochDay)
        val last = if (until != null && until < to) until else to
        val exceptions = event.exceptions.toHashSet()

        return candidateDates(event.recurrence, base.startDate, from.minusDays(spanDays), last)
            .filterNot { it.toEpochDay() in exceptions }
            .map { date ->
                val start = LocalDateTime.of(date, baseStart.toLocalTime())
                Occurrence(event, start, start.plus(length), color, calendarName)
            }
            .toList()
    }

    /** Start dates of a series beginning on [base], restricted to [from]..[to]. */
    private fun candidateDates(rule: Recurrence, base: LocalDate, from: LocalDate, to: LocalDate) = sequence {
        if (to < base) return@sequence
        val start = maxOf(from, base)
        when (rule) {
            Recurrence.NONE -> yield(base)
            Recurrence.DAILY -> yieldAll(generateSequence(start) { it.plusDays(1) }.takeWhile { it <= to })
            Recurrence.WEEKDAYS -> yieldAll(
                generateSequence(start) { it.plusDays(1) }
                    .takeWhile { it <= to }
                    .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY },
            )
            Recurrence.WEEKLY -> {
                val weeks = Math.floorDiv(ChronoUnit.DAYS.between(base, start) + 6, 7)
                yieldAll(generateSequence(base.plusWeeks(weeks)) { it.plusWeeks(1) }.takeWhile { it <= to })
            }
            Recurrence.MONTHLY -> {
                var month = YearMonth.from(start)
                while (month.atDay(1) <= to) {
                    // Months without the day (e.g. the 31st) are skipped, like most calendars do.
                    if (base.dayOfMonth <= month.lengthOfMonth()) {
                        val date = month.atDay(base.dayOfMonth)
                        if (date in start..to) yield(date)
                    }
                    month = month.plusMonths(1)
                }
            }
            Recurrence.YEARLY -> {
                var year = start.year
                while (year <= to.year) {
                    val month = YearMonth.of(year, base.month)
                    if (base.dayOfMonth <= month.lengthOfMonth()) {
                        val date = month.atDay(base.dayOfMonth)
                        if (date in start..to) yield(date)
                    }
                    year++
                }
            }
        }
    }
}
