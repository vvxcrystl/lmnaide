package dev.lmnaide.calendar.ui.common

import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.domain.Occurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Fmt {
    private val locale: Locale get() = Locale.getDefault()

    private fun pattern(p: String) = DateTimeFormatter.ofPattern(p, locale)

    fun time(time: LocalTime, use24h: Boolean): String = when {
        use24h -> pattern("HH:mm").format(time)
        time.minute == 0 -> pattern("h a").format(time)
        else -> pattern("h:mm a").format(time)
    }

    fun timeRange(start: LocalTime, end: LocalTime, use24h: Boolean): String =
        "${time(start, use24h)} – ${time(end, use24h)}"

    fun hourLabel(hour: Int, use24h: Boolean): String =
        if (use24h) "%02d:00".format(hour) else pattern("h a").format(LocalTime.of(hour, 0))

    /** "Thursday, October 8", with the year when it isn't the current one. */
    fun dayLong(date: LocalDate, today: LocalDate = LocalDate.now()): String =
        pattern(if (date.year == today.year) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy").format(date)

    /** "Thu, Oct 8", with the year when it isn't the current one. */
    fun dayMedium(date: LocalDate, today: LocalDate = LocalDate.now()): String =
        pattern(if (date.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy").format(date)

    fun dayShort(date: LocalDate): String = pattern("MMM d, yyyy").format(date)

    fun monthYear(month: YearMonth, today: LocalDate = LocalDate.now()): String =
        pattern(if (month.year == today.year) "MMMM" else "MMMM yyyy").format(month)

    fun monthYearFull(month: YearMonth): String = pattern("MMMM yyyy").format(month)

    fun monthShort(month: YearMonth): String = pattern("MMM").format(month)

    fun monthYearShort(month: YearMonth): String = pattern("MMM yyyy").format(month)

    fun weekdayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale)

    fun weekdayNarrow(day: DayOfWeek): String = day.getDisplayName(TextStyle.NARROW, locale)

    /** Two lines describing when an occurrence happens, as shown on the event page. */
    fun occurrenceWhen(occurrence: Occurrence, use24h: Boolean, today: LocalDate): Pair<String, String?> {
        val first = occurrence.startDate
        val last = occurrence.lastDate
        return when {
            occurrence.allDay && first == last -> dayLong(first, today) to null
            occurrence.allDay -> "${dayMedium(first, today)} – ${dayMedium(last, today)}" to null
            first == last -> dayLong(first, today) to
                timeRange(occurrence.start.toLocalTime(), occurrence.end.toLocalTime(), use24h)
            else -> "${dayMedium(first, today)}, ${time(occurrence.start.toLocalTime(), use24h)} –" to
                "${dayMedium(last, today)}, ${time(occurrence.end.toLocalTime(), use24h)}"
        }
    }

    /** Compact time label for event blocks and notifications. */
    fun occurrenceTime(occurrence: Occurrence, use24h: Boolean): String = when {
        occurrence.allDay -> "All day"
        occurrence.startDate != occurrence.lastDate ->
            "${dayMedium(occurrence.startDate)}, ${time(occurrence.start.toLocalTime(), use24h)} – " +
                "${dayMedium(occurrence.lastDate)}, ${time(occurrence.end.toLocalTime(), use24h)}"
        else -> timeRange(occurrence.start.toLocalTime(), occurrence.end.toLocalTime(), use24h)
    }

    fun recurrence(rule: Recurrence, start: LocalDate): String = when (rule) {
        Recurrence.NONE -> "Does not repeat"
        Recurrence.DAILY -> "Every day"
        Recurrence.WEEKDAYS -> "Every weekday (Mon–Fri)"
        Recurrence.WEEKLY -> "Every week on ${start.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}"
        Recurrence.MONTHLY -> "Every month on day ${start.dayOfMonth}"
        Recurrence.YEARLY -> "Every year on ${pattern("MMMM d").format(start)}"
    }

    fun reminder(minutes: Int, allDay: Boolean, use24h: Boolean): String {
        if (allDay) {
            val at = time(LocalTime.of(9, 0), use24h)
            return when (minutes) {
                0 -> "On the day at $at"
                24 * 60 -> "The day before at $at"
                7 * 24 * 60 -> "1 week before at $at"
                else -> "${duration(minutes)} before $at"
            }
        }
        return if (minutes == 0) "At time of event" else "${duration(minutes)} before"
    }

    fun duration(minutes: Int): String = when {
        minutes % (7 * 24 * 60) == 0 -> plural(minutes / (7 * 24 * 60), "week")
        minutes % (24 * 60) == 0 -> plural(minutes / (24 * 60), "day")
        minutes % 60 == 0 -> plural(minutes / 60, "hour")
        minutes > 60 -> "${plural(minutes / 60, "hour")} ${plural(minutes % 60, "minute")}"
        else -> plural(minutes, "minute")
    }

    private fun plural(count: Int, unit: String) = if (count == 1) "1 $unit" else "$count ${unit}s"
}
