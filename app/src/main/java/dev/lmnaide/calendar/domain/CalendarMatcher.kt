package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.EventEntity

/** Why quick add picked a calendar, so the preview can say so. */
sealed interface CalendarReason {
    data class Keyword(val keyword: String) : CalendarReason
    data object SameTitle : CalendarReason
    data object Name : CalendarReason
}

data class CalendarMatch(val calendar: CalendarEntity, val reason: CalendarReason)

/**
 * Picks a calendar for a quick add title. In order: a calendar keyword in the title ("produce"),
 * the calendar the same title went into last time, then a calendar named in the title ("Work").
 * Returns null when nothing matches, so the default calendar is used.
 */
object CalendarMatcher {
    fun match(title: String, calendars: List<CalendarEntity>, history: List<EventEntity>): CalendarMatch? {
        if (title.isBlank()) return null
        calendars
            .flatMap { calendar -> calendar.keywordList.map { calendar to it } }
            .filter { (_, keyword) -> containsWord(title, keyword) }
            // The most specific keyword wins: "night shift" over "shift".
            .maxByOrNull { (_, keyword) -> keyword.length }
            ?.let { (calendar, keyword) -> return CalendarMatch(calendar, CalendarReason.Keyword(keyword)) }
        history
            .filter { it.title.equals(title.trim(), ignoreCase = true) }
            .sortedByDescending { it.id }
            .firstNotNullOfOrNull { event -> calendars.firstOrNull { it.id == event.calendarId } }
            ?.let { return CalendarMatch(it, CalendarReason.SameTitle) }
        return calendars
            .filter { containsWord(title, it.name) }
            .maxByOrNull { it.name.length }
            ?.let { CalendarMatch(it, CalendarReason.Name) }
    }

    /** Whole words only, ignoring case and allowing a plural: "shift" matches "Shifts" but not "shifty". */
    private fun containsWord(text: String, word: String): Boolean {
        if (word.isBlank()) return false
        val pattern = "(?<![\\p{L}\\p{N}])${Regex.escape(word.trim())}(?:e?s)?(?![\\p{L}\\p{N}])"
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}
