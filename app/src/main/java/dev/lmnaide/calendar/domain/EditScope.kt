package dev.lmnaide.calendar.domain

/** Which part of a recurring series an edit or delete applies to. */
enum class EditScope(val label: String) {
    THIS("This event"),
    FOLLOWING("This and following events"),
    ALL("All events"),
}
