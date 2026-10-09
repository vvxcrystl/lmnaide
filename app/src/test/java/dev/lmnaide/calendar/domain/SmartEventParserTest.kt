package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.Recurrence
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SmartEventParserTest {
    private val today = LocalDate.of(2026, 10, 8)
    private fun event(text: String, duration: Int = 60): SmartEventDraft {
        val result = SmartEventParser.parse(text, today, duration)
        assertTrue("$text: $result", result is SmartEventResult.Success)
        return (result as SmartEventResult.Success).event
    }
    private fun check(text: String, title: String, start: String, end: String) {
        val event = event(text)
        assertEquals(title, event.title)
        assertEquals(LocalDateTime.parse(start), event.start)
        assertEquals(LocalDateTime.parse(end), event.end)
    }

    @Test fun originalExample() {
        check("Work from 3 to 5pm tomorrow", "Work", "2026-10-09T15:00", "2026-10-09T17:00")
    }
    @Test fun timeFormats() {
        check("WORK TOMORROW FROM 3 TO 5PM", "WORK", "2026-10-09T15:00", "2026-10-09T17:00")
        check("Dentist tomorrow at 10am", "Dentist", "2026-10-09T10:00", "2026-10-09T11:00")
        check("Lunch today at noon", "Lunch", "2026-10-08T12:00", "2026-10-08T13:00")
        check("Call Alex on 2026-10-12 at 14:30", "Call Alex", "2026-10-12T14:30", "2026-10-12T15:30")
        check("Coffee tomorrow at 9:30am", "Coffee", "2026-10-09T09:30", "2026-10-09T10:30")
        check("Work tomorrow 3-5pm", "Work", "2026-10-09T15:00", "2026-10-09T17:00")
    }
    @Test fun sharedMeridiemAndOvernight() {
        check("Lunch tomorrow from 11 to 1pm", "Lunch", "2026-10-09T11:00", "2026-10-09T13:00")
        check("Lunch tomorrow from 11:30 to 1pm", "Lunch", "2026-10-09T11:30", "2026-10-09T13:00")
        check("Shift tomorrow from 11 to 1am", "Shift", "2026-10-09T23:00", "2026-10-10T01:00")
        check("Night shift tomorrow from 10pm to 2am", "Night shift", "2026-10-09T22:00", "2026-10-10T02:00")
        check("Shift tomorrow from 14:00 to 3pm", "Shift", "2026-10-09T14:00", "2026-10-09T15:00")
    }
    @Test fun datesAndDurations() {
        check("Haircut next Friday at 4pm", "Haircut", "2026-10-09T16:00", "2026-10-09T17:00")
        check("Soccer on Monday at 6pm", "Soccer", "2026-10-12T18:00", "2026-10-12T19:00")
        check("Inspection in 3 days at 11am", "Inspection", "2026-10-11T11:00", "2026-10-11T12:00")
        check("Visit mom the day after tomorrow at noon", "Visit mom", "2026-10-10T12:00", "2026-10-10T13:00")
        check("Meeting on October 20 at 2pm", "Meeting", "2026-10-20T14:00", "2026-10-20T15:00")
        check("Walk tomorrow at 4pm for 30 minutes", "Walk", "2026-10-09T16:00", "2026-10-09T16:30")
        check("Workshop tomorrow at 10am for 2 hours", "Workshop", "2026-10-09T10:00", "2026-10-09T12:00")
        check("Maintenance tomorrow at midnight for 90 minutes", "Maintenance", "2026-10-09T00:00", "2026-10-09T01:30")
        assertEquals(45, java.time.Duration.between(event("Work at 3pm", 45).start, event("Work at 3pm", 45).end).toMinutes())
    }
    @Test fun allDayAndYearRollover() {
        val draft = event("Birthday party tomorrow")
        assertTrue(draft.allDay)
        assertEquals("Birthday", event("Birthday tomorrow all day").title)
        assertEquals(today.plusDays(1).atStartOfDay(), draft.start)
        assertEquals(today.plusDays(2).atStartOfDay(), draft.end)
        assertEquals(LocalDate.of(2027, 1, 2), event("Holiday January 2").start.toLocalDate())
        assertEquals(LocalDate.of(2028, 2, 29), event("Leap day February 29").start.toLocalDate())
    }
    @Test fun recurrence() {
        assertEquals(Recurrence.WEEKDAYS, event("Standup every weekday at 9am").recurrence)
        val guitar = event("Guitar every Tuesday at 7pm")
        assertEquals(Recurrence.WEEKLY, guitar.recurrence)
        assertEquals(LocalDate.of(2026, 10, 13), guitar.start.toLocalDate())
        assertEquals(Recurrence.DAILY, event("Stretch every day at 6am").recurrence)
        assertEquals(today, event("Reading every Thursday from 8pm to 9pm").start.toLocalDate())
        val weekend = SmartEventParser.parse("Standup every weekday at 9am", LocalDate.of(2026,10,10)) as SmartEventResult.Success
        assertEquals(LocalDate.of(2026,10,12), weekend.event.start.toLocalDate())
    }
    @Test fun politeRequest() {
        assertEquals("team meeting", event("Please add a team meeting tomorrow from 2pm to 3pm").title)
    }
    @Test fun needsClarificationRatherThanInventingDetails() {
        listOf("", "Work", "Dinner sometime next week",
            "Meeting tomorrow at 3pm and at 4pm", "Delete my meeting tomorrow",
            "Work tomorrow at 3pm all-day", "Work tomorrow at 25pm", "Work tomorrow at 10:99",
            "Work February 30 at 3pm", "Work 2026-02-29 at 3pm", "Work tomorrow today at 3pm",
            "Work tomorrow at 3pm for 0 minutes", "Work tomorrow for 2 hours",
            "Work tomorrow from 3pm to 5pm for 2 hours", "Work tomorrow from 3pm to 3pm",
            "tomorrow at 3pm", "Work 10/20 at 3pm", "Work every Monday tomorrow at 3pm",
        ).forEach { assertTrue("Should clarify: $it", SmartEventParser.parse(it, today) is SmartEventResult.NeedsDetails) }
    }
    @Test fun bareHoursGuessAmOrPm() {
        check("Work from 9 to 5 on Friday", "Work", "2026-10-09T09:00", "2026-10-09T17:00")
        check("Work tomorrow from 3 to 5", "Work", "2026-10-09T15:00", "2026-10-09T17:00")
        check("Brunch tomorrow from 10 to 2", "Brunch", "2026-10-09T10:00", "2026-10-09T14:00")
        check("Dinner tomorrow from 6 to 9", "Dinner", "2026-10-09T18:00", "2026-10-09T21:00")
        check("Shift tomorrow from 9am to 5", "Shift", "2026-10-09T09:00", "2026-10-09T17:00")
        check("Party tomorrow from 10pm to 2", "Party", "2026-10-09T22:00", "2026-10-10T02:00")
        check("Produce shift tomorrow 6 to 2", "Produce shift", "2026-10-09T06:00", "2026-10-09T14:00")
        check("Shift tomorrow from 9 to 17", "Shift", "2026-10-09T09:00", "2026-10-09T17:00")
        check("Meet Jordan tomorrow at 3", "Meet Jordan", "2026-10-09T15:00", "2026-10-09T16:00")
        check("Breakfast tomorrow at 8", "Breakfast", "2026-10-09T08:00", "2026-10-09T09:00")
        check("Lunch tomorrow at 12", "Lunch", "2026-10-09T12:00", "2026-10-09T13:00")
    }
}
