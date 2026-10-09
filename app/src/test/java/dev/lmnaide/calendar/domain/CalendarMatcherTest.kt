package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.EventEntity
import org.junit.Assert.*
import org.junit.Test

class CalendarMatcherTest {
    private val personal = CalendarEntity(id = 1, name = "Personal", color = 0)
    private val work = CalendarEntity(id = 2, name = "Work", color = 0, keywords = "produce, shift")
    private val gym = CalendarEntity(id = 3, name = "Fitness", color = 0, keywords = "gym, night shift")
    private val calendars = listOf(personal, work, gym)
    private fun event(id: Long, title: String, calendar: CalendarEntity) =
        EventEntity(id = id, calendarId = calendar.id, title = title, start = 0, end = 0)

    @Test fun keywordsMatchWholeWordsAndPlurals() {
        assertEquals(CalendarMatch(work, CalendarReason.Keyword("produce")), CalendarMatcher.match("Produce", calendars, emptyList()))
        assertEquals(work, CalendarMatcher.match("Two shifts", calendars, emptyList())?.calendar)
        assertNull(CalendarMatcher.match("Shifty plans", calendars, emptyList()))
    }

    @Test fun longestKeywordWins() {
        assertEquals(gym, CalendarMatcher.match("Night shift cover", calendars, emptyList())?.calendar)
    }

    @Test fun sameTitleReusesTheLatestCalendar() {
        val history = listOf(event(1, "Dentist", work), event(5, "dentist", personal))
        assertEquals(CalendarMatch(personal, CalendarReason.SameTitle), CalendarMatcher.match("Dentist", calendars, history))
    }

    @Test fun calendarNameIsTheFallback() {
        assertEquals(CalendarMatch(work, CalendarReason.Name), CalendarMatcher.match("Work", calendars, emptyList()))
        assertNull(CalendarMatcher.match("Homework", calendars, emptyList()))
        assertNull(CalendarMatcher.match("Lunch", calendars, emptyList()))
    }

    @Test fun keywordsBeatHistory() {
        val history = listOf(event(1, "Produce", personal))
        assertEquals(work, CalendarMatcher.match("Produce", calendars, history)?.calendar)
    }
}
