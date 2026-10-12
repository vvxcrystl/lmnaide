package dev.lmnaide.calendar.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SelectedTextInputTest {
    private val today = LocalDate.of(2026, 10, 11)

    @Test fun bareDatesBecomeEditableEvents() {
        listOf("October 23, 2026", "2026-10-23", "Oct 23").forEach { text ->
            val input = SelectedTextInput.prepare(text, today)
            assertEquals("New event $text", input)
            val result = SmartEventParser.parse(input, today) as SmartEventResult.Success
            assertEquals("New event", result.event.title)
            assertEquals(LocalDate.of(2026, 10, 23).atStartOfDay(), result.event.start)
            assertEquals(LocalDate.of(2026, 10, 24).atStartOfDay(), result.event.end)
            assertTrue(result.event.allDay)
        }
    }

    @Test fun relativeDatesTimesAndRangesUseTheExistingParser() {
        val input = SelectedTextInput.prepare("tomorrow at 3pm", today)
        val event = (SmartEventParser.parse(input, today) as SmartEventResult.Success).event
        assertEquals(today.plusDays(1).atTime(15, 0), event.start)
        assertFalse(event.allDay)
        val range = SelectedTextInput.prepare("Oct 23 to Oct 25", today)
        val trip = (SmartEventParser.parse(range, today) as SmartEventResult.Success).event
        assertEquals(LocalDate.of(2026, 10, 26).atStartOfDay(), trip.end)
    }

    @Test fun eventAndTaskSentencesKeepTheirTitles() {
        listOf("Lunch with Sam tomorrow at noon", "Pay rent by Friday").forEach { text ->
            assertEquals(text, SelectedTextInput.prepare("  $text\n", today))
        }
        assertEquals("New task by Friday", SelectedTextInput.prepare("by Friday", today))
    }

    @Test fun invalidAndAmbiguousTextIsNotGuessedOrTruncated() {
        listOf("", "a date", "February 30", "10/23", "Oct 23 or Oct 25",
            "cancel lunch tomorrow", "x".repeat(501)).forEach { text ->
            assertEquals(text, SelectedTextInput.prepare(text, today))
            assertTrue(SmartEventParser.parse(text, today) is SmartEventResult.NeedsDetails)
        }
    }
}
