package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.EventKind
import dev.lmnaide.calendar.data.Recurrence
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SharedEventDraftTest {
    private val fields = mapOf(
        "title" to "Lunch & plans + friends 🍋",
        "description" to "Line one\nA literal %20 and #tag",
        "location" to "Café, Montréal",
        "start" to Instant.parse("2026-10-23T18:00:00Z").toEpochMilli().toString(),
        "end" to Instant.parse("2026-10-23T19:00:00Z").toEpochMilli().toString(),
        "allDay" to "false", "recurrence" to "WEEKLY", "kind" to "TASK",
        "recurrenceUntil" to LocalDate.of(2026, 11, 2).toEpochDay().toString(),
    )
    private fun query(changes: Map<String, String> = emptyMap()) = (fields + changes).entries.joinToString("&") {
        it.key + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    @Test fun importsTextExactlyAndConvertsTimedEventsToLocalTime() {
        val draft = SharedEventDraft.parse(query(), ZoneId.of("America/Regina"))!!
        assertEquals(fields["title"], draft.title)
        assertEquals(fields["description"], draft.description)
        assertEquals(fields["location"], draft.location)
        assertEquals(LocalDateTime.of(2026, 10, 23, 12, 0), draft.start)
        assertEquals(LocalDateTime.of(2026, 10, 23, 13, 0), draft.end)
        assertEquals(Recurrence.WEEKLY, draft.recurrence)
        assertEquals(LocalDate.of(2026, 11, 2), draft.recurrenceUntil)
        assertEquals(EventKind.TASK, draft.kind)
    }

    @Test fun allDayDatesFloatAndKeepTheExclusiveEnd() {
        val payload = query(mapOf("allDay" to "true", "start" to "1792713600000", "end" to "1792886400000"))
        for (zone in listOf("Pacific/Honolulu", "Pacific/Kiritimati")) {
            val draft = SharedEventDraft.parse(payload, ZoneId.of(zone))!!
            assertEquals(LocalDateTime.of(2026, 10, 23, 0, 0), draft.start)
            assertEquals(LocalDateTime.of(2026, 10, 25, 0, 0), draft.end)
        }
    }

    @Test fun rejectsMalformedAndOutOfRangeExternalInput() {
        assertNull(SharedEventDraft.parse(null))
        assertNull(SharedEventDraft.parse("title=%GG"))
        for ((key, value) in listOf(
            "start" to "NaN", "start" to Long.MIN_VALUE.toString(),
            "end" to "0", "allDay" to "yes", "kind" to "UNKNOWN",
            "recurrence" to "HOURLY", "recurrenceUntil" to Long.MAX_VALUE.toString(),
            "title" to "x".repeat(1001), "description" to "x".repeat(20001),
            "location" to "x".repeat(2001),
        )) assertNull("$key must be rejected", SharedEventDraft.parse(query(mapOf(key to value))))
    }
}
