package dev.lmnaide.calendar.data

import java.time.LocalDate

/** Limits accepted by the Cloud sync API, enforced before local edits are saved. */
object SyncLimits {
    const val TITLE = 1_000
    const val DESCRIPTION = 20_000
    const val LOCATION = 2_000
    const val CALENDAR_NAME = 200
    const val KEYWORDS = 2_000
    const val REMINDERS = 100
    const val REMINDER_MINUTES = 525_600

    val MIN_DATE: LocalDate = LocalDate.of(1900, 1, 1)
    val MAX_DATE: LocalDate = LocalDate.of(2200, 12, 31)

    fun validDate(date: LocalDate): Boolean = date in MIN_DATE..MAX_DATE

    fun validCalendar(name: String, keywords: String): Boolean =
        name.isNotBlank() && name.length in 1..CALENDAR_NAME && keywords.length <= KEYWORDS

    fun validReminders(reminders: List<Int>): Boolean =
        reminders.size <= REMINDERS && reminders.all { it in 0..REMINDER_MINUTES }
}
