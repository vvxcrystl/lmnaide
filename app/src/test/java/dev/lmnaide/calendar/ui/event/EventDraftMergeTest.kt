package dev.lmnaide.calendar.ui.event

import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.data.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EventDraftMergeTest {
    private val original = EventEntity(
        id = 7,
        calendarId = 2,
        title = "Planning",
        description = "old notes",
        start = 1_700_000_000_000,
        end = 1_700_003_600_000,
        reminders = listOf(15),
        completions = listOf(10),
        recurrence = Recurrence.WEEKLY,
    )

    private val opened = EditorState(
        title = original.title,
        description = original.description,
        calendarId = original.calendarId,
        reminders = original.reminders,
        startDate = LocalDate.of(2023, 11, 14),
        endDate = LocalDate.of(2023, 11, 14),
        recurrence = original.recurrence,
    )

    @Test
    fun untouchedNotesRemindersAndCompletionsKeepTheSyncedValues() {
        val latest = original.copy(
            description = "notes from another device",
            reminders = listOf(30),
            completions = listOf(10, 17),
            createdProgrammatically = true,
        )
        val draftState = opened.copy(title = "Planning moved")
        val draft = original.copy(title = "Planning moved")

        val merged = mergeEventDraft(original, latest, opened, draftState, draft)

        assertEquals("Planning moved", merged.event.title)
        assertEquals("notes from another device", merged.event.description)
        assertEquals(listOf(30), merged.event.reminders)
        assertEquals(listOf(10L, 17L), merged.event.completions)
        assertTrue(merged.event.createdProgrammatically)
        assertTrue(merged.conflicts.isEmpty())
    }

    @Test
    fun sameFieldConflictPrefersTheDraftAndNamesTheField() {
        val latest = original.copy(title = "Remote title")
        val draftState = opened.copy(title = "My title")
        val draft = original.copy(title = "My title")

        val merged = mergeEventDraft(original, latest, opened, draftState, draft)

        assertEquals("My title", merged.event.title)
        assertEquals(listOf("title"), merged.conflicts)
    }
}
