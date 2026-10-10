package dev.lmnaide.calendar.contacts

/** A name snapshot survives renames/deletions; lookupUri opens the current device contact. */
data class EventPerson(val name: String, val lookupUri: String)

object ContactMatcher {
    fun names(title: String): List<String> {
        val people = Regex("\\bwith\\s+(.+)$", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1) ?: return emptyList()
        return people.split(Regex("\\s*,\\s*|\\s+and\\s+|\\s*&\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim().trim('.', ',') }.filter { it.isNotEmpty() && it.lowercase() !in listOf("i", "me", "myself") }.distinct()
    }

    fun matches(name: String, contacts: List<EventPerson>): List<EventPerson> {
        val exact = contacts.filter { it.name.equals(name, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact
        return contacts.filter { person -> person.name.split(Regex("\\s+")).any { it.equals(name, ignoreCase = true) } }
    }
}
