package dev.lmnaide.calendar.domain

import java.time.LocalDate

/** Give a bare selected date a title, while keeping sentences and unreadable text editable. */
object SelectedTextInput {
    fun prepare(text: String, today: LocalDate = LocalDate.now()): String {
        val input = text.trim()
        val task = SmartEventParser.looksLikeTask(input)
        val title = if (task) "New task" else "New event"
        val candidate = "$title $input"
        val parsed = SmartEventParser.parse(candidate, today, task = task) as? SmartEventResult.Success
        // Only supply the title when the parser consumed the entire original selection as dates
        // and times. Other selections retain their original wording and validation messages.
        return if (parsed?.event?.title == title) candidate else input
    }
}
