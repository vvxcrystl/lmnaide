package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.Recurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Local, deterministic English event entry. Dates are resolved against the device's local day. */
data class SmartEventDraft(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val recurrence: Recurrence,
)

sealed interface SmartEventResult {
    data class Success(val event: SmartEventDraft) : SmartEventResult
    data class NeedsDetails(val message: String) : SmartEventResult
}

object SmartEventParser {
    private val weekdays = DayOfWeek.entries.associateBy { it.name.lowercase(Locale.US) }
    private val months = Month.entries.associateBy { it.name.lowercase(Locale.US) }
    private val dayNames = weekdays.keys.joinToString("|")
    private val monthNames = months.keys.joinToString("|")
    private const val CLOCK = "(?:noon|midnight|\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)"
    private fun regex(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** True when the text reads like a to-do with a deadline, such as "Pay rent by Friday". */
    fun looksLikeTask(input: String): Boolean =
        regex("\\bby\\s+(?:\\d|noon|midnight|today|tomorrow|next\\b|$dayNames|$monthNames)").containsMatchIn(input)

    /**
     * [defaultDate] is used when the text names a time but no date, such as the day open in the
     * calendar; relative words like "tomorrow" are always resolved against [today].
     */
    fun parse(
        input: String,
        today: LocalDate = LocalDate.now(),
        defaultDurationMinutes: Int = 60,
        task: Boolean = false,
        defaultDate: LocalDate = today,
    ): SmartEventResult {
        if (input.isBlank()) return problem("Describe an event, including a date or time.")
        if (input.length > 500) return problem("Keep the event description under 500 characters.")
        if (regex("^(?:please\\s+)?(?:delete|cancel|remove|move|reschedule)\\b").containsMatchIn(input.trim()))
            return problem("Create new events or tasks here. Open an existing item to change it.")
        if (!task && regex("\\bby\\s+(?:\\d|noon|midnight)").containsMatchIn(input))
            return problem("Use “at” for an event start time, or create a Smart Task with a due time.")
        var text = input.trim().replace(Regex("\\s+"), " ")
            .replace(regex("^(?:please\\s+)?(?:add|create|schedule)\\s+(?:an?\\s+)?"), "")
        fun remove(match: MatchResult) { text = text.removeRange(match.range).trim() }
        val allDayMatch = regex("\\ball[- ]day\\b").find(text)
        if (allDayMatch != null) remove(allDayMatch)
        var date: LocalDate? = null
        var recurrence = Recurrence.NONE
        val repeat = regex("\\bevery\\s+(weekday|day|week|month|year|$dayNames)s?\\b|\\b(daily|weekly|monthly|yearly)\\b").find(text)
        if (repeat != null) {
            val rule = repeat.groupValues[1].ifEmpty { repeat.groupValues[2] }.lowercase(Locale.US)
            recurrence = when (rule) {
                "day", "daily" -> Recurrence.DAILY
                "weekday" -> Recurrence.WEEKDAYS
                "month", "monthly" -> Recurrence.MONTHLY
                "year", "yearly" -> Recurrence.YEARLY
                else -> Recurrence.WEEKLY
            }
            weekdays[rule]?.let { date = today.with(TemporalAdjusters.nextOrSame(it)) }
            if (recurrence == Recurrence.WEEKDAYS) {
                date = today
                while (date!!.dayOfWeek in listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) date = date.plusDays(1)
            }
            remove(repeat)
        }
        val datePrefix = if (task) "(?:on|by)" else "on"
        val datePattern = regex("\\b(?:$datePrefix\\s+)?(the day after tomorrow|day after tomorrow|tomorrow|today|in\\s+\\d+\\s+(?:days?|weeks?)|\\d{4}-\\d{2}-\\d{2}|(?:next\\s+)?(?:$dayNames)|(?:$monthNames)\\s+\\d{1,2}(?:st|nd|rd|th)?(?:,?\\s+\\d{4})?)\\b")
        val dateMatch = datePattern.find(text)
        if (dateMatch != null) {
            val raw = dateMatch.groupValues[1].lowercase(Locale.US)
            val parsed = try {
                when {
                    raw.endsWith("day after tomorrow") -> today.plusDays(2)
                    raw == "tomorrow" -> today.plusDays(1)
                    raw == "today" -> today
                    raw.startsWith("in ") -> {
                        val amount = raw.split(" ")[1].toLong()
                        if (amount > 36500) return problem("That date is too far away. Use a specific date.")
                        if (raw.contains("week")) today.plusWeeks(amount) else today.plusDays(amount)
                    }
                    regex("\\d{4}-\\d{2}-\\d{2}").matches(raw) -> LocalDate.parse(raw)
                    weekdays.containsKey(raw.removePrefix("next ")) -> today.with(TemporalAdjusters.next(weekdays.getValue(raw.removePrefix("next "))))
                    else -> {
                        val parts = raw.replace(",", "").replace(regex("(\\d)(st|nd|rd|th)\\b"), "$1").split(" ")
                        val month = months.getValue(parts[0])
                        val year = parts.getOrNull(2)?.toInt() ?: today.year
                        // MonthDay validates leap-day input before choosing the next valid year.
                        val day = parts[1].toInt()
                        if (parts.size > 2) LocalDate.of(year, month, day) else {
                            val md = java.time.MonthDay.of(month, day)
                            var candidateYear = year
                            while (!md.isValidYear(candidateYear) || md.atYear(candidateYear).isBefore(today)) candidateYear++
                            md.atYear(candidateYear)
                        }
                    }
                }
            } catch (_: RuntimeException) { return problem("Check the date. Try “October 20” or “2026-10-20”.") }
            if (date != null && date != parsed) return problem("Use one start date for this event.")
            date = parsed
            remove(dateMatch)
            if (datePattern.containsMatchIn(text)) return problem("Use one start date for this event.")
        }
        if (recurrence != Recurrence.NONE && date == null) date = today
        val durationMatch = regex("\\bfor\\s+(\\d+)\\s*(minutes?|mins?|hours?|hrs?)\\b").find(text)
        var duration = defaultDurationMinutes.toLong().coerceIn(1, 1440)
        if (durationMatch != null && task) return problem("Tasks have a due time. Try “by 9pm today”.")
        if (durationMatch != null) {
            val amount = durationMatch.groupValues[1].toLongOrNull() ?: return problem("Check the duration.")
            duration = amount * if (durationMatch.groupValues[2].lowercase(Locale.US).startsWith("h")) 60 else 1
            if (amount !in 1..10080 || duration !in 1..10080) return problem("Use a duration between 1 minute and 7 days.")
            remove(durationMatch)
        }
        val range = regex("(?<![\\w:])(?:from\\s+|at\\s+)?($CLOCK)\\s*(?:to|[-–])\\s*($CLOCK)(?![\\w:])").find(text)
        var startTime: LocalTime? = null
        var endTime: LocalTime? = null
        if (range != null && task) return problem("Use one due time for a task, for example “by 9pm today”.")
        if (range != null) {
            if (durationMatch != null) return problem("Use either an end time or a duration.")
            val first = range.groupValues[1].trim()
            val last = range.groupValues[2].trim()
            val suffix = regex("(am|pm)$").find(last)?.value?.lowercase(Locale.US)
            startTime = clock(first, suffix) ?: bareHour(first)?.let(::likelyTime)
                ?: return problem("Check the start time, for example “3 to 5pm”.")
            // A bare end hour is the next one after the start: 9 to 5 means 9am to 5pm.
            endTime = clock(last) ?: bareHour(last)?.let { nextAfter(it, startTime) }
                ?: return problem("Check the end time, or use 24-hour time like “17:00”.")
            // With no am or pm anywhere, prefer the reading that ends the same day: 6 to 2 is a day shift.
            val firstHour = bareHour(first)
            if (suffix == null && firstHour in 1..12 && bareHour(last) in 1..12 && endTime < startTime) {
                val flipped = startTime.plusHours(12)
                val flippedEnd = nextAfter(bareHour(last)!!, flipped)
                if (flippedEnd > flipped) { startTime = flipped; endTime = flippedEnd }
            }
            // Shared suffix: 11 to 1pm means 11am to 1pm, rather than an overnight range.
            if (suffix != null && !regex("am|pm|noon|midnight").containsMatchIn(first) && first.substringBefore(':').trim().toIntOrNull() in 1..12 && endTime <= startTime) {
                startTime = if (startTime.hour >= 12) startTime.minusHours(12) else startTime.plusHours(12)
            }
            if (endTime == startTime) return problem("The start and end times are the same. Add an end time or duration.")
            remove(range)
        } else {
            val timePrefix = if (task) "(?:at|by)" else "at"
            val single = regex("\\b$timePrefix\\s+($CLOCK)(?![\\w:])|(?<![\\w:])((?:\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)|\\d{1,2}:\\d{2}|noon|midnight))(?![\\w:])").find(text)
            if (single != null) {
                val raw = single.groupValues[1].ifEmpty { single.groupValues[2] }
                startTime = clock(raw) ?: bareHour(raw)?.let(::likelyTime)
                    ?: return problem("Check the time, or use 24-hour time like “15:00”.")
                remove(single)
            }
        }
        if (regex("\\b(?:today|tomorrow|every|daily|weekly|monthly|yearly|next|in\\s+\\d+|for\\s+\\d+|at\\s+\\d|from\\s+\\d|am|pm|noon|midnight)\\b|\\d+:\\d+|\\d+\\s*(?:am|pm)\\b|\\b\\d{1,2}[/-]\\d{1,2}\\b").containsMatchIn(text)) {
            return problem(if (task) "I couldn't read all the due-date details. Try “Call doctor by 9pm today”."
                else "I couldn't read all the date or time details. Try “Work tomorrow from 3 to 5pm”.")
        }
        if (allDayMatch != null && startTime != null) return problem("Use either all-day or a start time.")
        val title = text.replace(Regex("\\s+"), " ").trim(' ', ',', '.', '-', '–')
        if (title.isBlank()) return problem("Add an event title, for example “Work tomorrow at 3pm”.")
        if (date == null && startTime == null) return problem("Add a date or time, for example “tomorrow” or “at 3pm”.")
        if (durationMatch != null && startTime == null) return problem("Add a start time for the duration.")
        val start = (date ?: defaultDate).atTime(startTime ?: LocalTime.MIDNIGHT)
        val end = when {
            startTime == null -> start.plusDays(1)
            task -> start
            endTime == null -> start.plusMinutes(duration)
            else -> start.toLocalDate().atTime(endTime).let { if (it < start) it.plusDays(1) else it }
        }
        return SmartEventResult.Success(SmartEventDraft(title, start, end, startTime == null, recurrence))
    }

    private fun clock(raw: String, inheritedSuffix: String? = null): LocalTime? {
        val text = raw.lowercase(Locale.US).replace(" ", "")
        if (text == "noon") return LocalTime.NOON
        if (text == "midnight") return LocalTime.MIDNIGHT
        val match = Regex("(\\d{1,2})(?::(\\d{2}))?(am|pm)?").matchEntire(text) ?: return null
        var hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        val suffix = match.groupValues[3].ifEmpty { if (hour in 1..12) inheritedSuffix.orEmpty() else "" }
        if (minute !in 0..59) return null
        if (suffix.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (suffix == "pm") 12 else 0
        } else if (match.groupValues[2].isEmpty() || hour !in 0..23) return null
        return LocalTime.of(hour, minute)
    }

    private fun bareHour(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 0..23 }

    /**
     * Guesses am or pm for a bare hour the way people usually mean it: 7 to 11 are mornings,
     * 12 is noon, and 1 to 6 are afternoons. Hours past 12 are already 24-hour times.
     */
    private fun likelyTime(hour: Int): LocalTime = when (hour) {
        in 1..6 -> LocalTime.of(hour + 12, 0)
        else -> LocalTime.of(hour, 0)
    }

    /** The first time after [start] with this hour on the clock face, wrapping past midnight if needed. */
    private fun nextAfter(hour: Int, start: LocalTime): LocalTime {
        if (hour !in 1..12) return LocalTime.of(hour, 0)
        val am = LocalTime.of(hour % 12, 0)
        val pm = am.plusHours(12)
        return listOf(am, pm).firstOrNull { it > start } ?: am
    }

    private fun problem(message: String) = SmartEventResult.NeedsDetails(message)
}
