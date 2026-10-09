package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.Recurrence
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SmartTaskParserTest {
    private val today = LocalDate.of(2026, 10, 8)
    private fun task(text: String): SmartEventDraft {
        val result = SmartEventParser.parse(text, today, task = true)
        assertTrue("$text: $result", result is SmartEventResult.Success)
        return (result as SmartEventResult.Success).event
    }
    @Test fun appointmentExampleHasDueTimeAndNoDuration() {
        val task = task("Schedule doctors appointment by 9pm today")
        assertEquals("doctors appointment", task.title)
        assertEquals(LocalDateTime.of(2026,10,8,21,0), task.start)
        assertEquals(task.start, task.end)
        assertFalse(task.allDay)
        assertEquals(Recurrence.NONE, task.recurrence)
    }
    @Test fun dateOnlyTaskUsesOneFloatingDay() {
        val task = task("Buy groceries tomorrow")
        assertEquals("Buy groceries", task.title)
        assertTrue(task.allDay)
        assertEquals(today.plusDays(1).atStartOfDay(), task.start)
        assertEquals(task.start.plusDays(1), task.end)
        assertEquals("Call doctor", task("Call doctor by Friday").title)
        assertEquals(today.plusDays(1), task("Call doctor by Friday").start.toLocalDate())
    }
    @Test fun repeatsUseExistingRules() {
        val task = task("Take vitamins every day at 8am")
        assertEquals(Recurrence.DAILY, task.recurrence)
        assertEquals(task.start, task.end)
        assertEquals("Take vitamins", task.title)
        assertEquals(8, task.start.hour)
    }
    @Test fun militaryAndRelativeDueTimes() {
        val task = task("Submit report by 21:30 tomorrow")
        assertEquals("Submit report", task.title)
        assertEquals(LocalDateTime.of(2026,10,9,21,30), task.start)
        assertEquals(task.start, task.end)
        assertEquals(12, task("Call doctor today by noon").start.hour)
        assertEquals(LocalDate.of(2026,10,20), task("Submit report by 2026-10-20").start.toLocalDate())
    }
    @Test fun ambiguousTimesAndEventDurationsRequireDetails() {
        listOf("Call doctor", "Call doctor tomorrow from 3 to 5pm",
            "Call doctor tomorrow at 3pm for 30 minutes", "Call doctor tomorrow at 25pm",
            "Call doctor tomorrow at 3pm and by 4pm",
        ).forEach { assertTrue(it, SmartEventParser.parse(it, today, task = true) is SmartEventResult.NeedsDetails) }
        assertTrue(SmartEventParser.parse("Call doctor by 9pm today", today) is SmartEventResult.NeedsDetails)
    }
    @Test fun deadlinesReadAsTasks() {
        listOf("Pay rent by Friday", "Call doctor by 9pm today", "Submit report by October 20", "Renew passport by next Monday")
            .forEach { assertTrue(it, SmartEventParser.looksLikeTask(it)) }
        listOf("Lunch with Sam tomorrow at noon", "Drop by the office at 3pm", "Stand by me tomorrow")
            .forEach { assertFalse(it, SmartEventParser.looksLikeTask(it)) }
    }
    @Test fun timeWithoutDateUsesTheDefaultDate() {
        val open = LocalDate.of(2026, 10, 15)
        val result = SmartEventParser.parse("Call doctor by 5pm", today, task = true, defaultDate = open) as SmartEventResult.Success
        assertEquals(LocalDateTime.of(2026,10,15,17,0), result.event.start)
        val relative = SmartEventParser.parse("Call doctor by 5pm tomorrow", today, task = true, defaultDate = open) as SmartEventResult.Success
        assertEquals(today.plusDays(1), relative.event.start.toLocalDate())
    }
    @Test fun bareDueHoursGuessAmOrPm() {
        assertEquals(LocalDateTime.of(2026,10,8,9,0), task("Call doctor by 9 today").start)
        assertEquals(LocalDateTime.of(2026,10,9,17,0), task("Pay rent by 5 tomorrow").start)
    }
}
