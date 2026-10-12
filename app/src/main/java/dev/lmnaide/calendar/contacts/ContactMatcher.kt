package dev.lmnaide.calendar.contacts

import java.util.Locale

/** A name snapshot survives renames/deletions; lookupUri opens the current device contact. */
data class EventPerson(val name: String, val lookupUri: String)

object ContactMatcher {
    fun names(title: String): List<String> {
        val people = Regex("\\bwith\\s+(.+)$", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1) ?: return emptyList()
        return people.split(Regex("\\s*,\\s*|\\s+and\\s+|\\s*&\\s*", RegexOption.IGNORE_CASE))
            .map { it.trim().trim('.', ',') }.filter { it.isNotEmpty() && it.lowercase() !in listOf("i", "me", "myself") }.distinct()
    }

    fun matches(name: String, contacts: List<EventPerson>): List<EventPerson> {
        val parts = nameParts(name)
        if (parts.isEmpty()) return emptyList()
        val candidates = contacts.map { it to nameParts(it.name) }
        val exact = candidates.filter { (_, words) -> words == parts }.map { it.first }
        if (exact.isNotEmpty()) return exact
        // Match complete name parts, including first/last names and shorter multi-part names.
        // Return every candidate so shared names require a choice instead of guessing.
        return candidates.filter { (_, words) ->
            words.windowed(parts.size).any { it == parts }
        }.map { it.first }
    }

    private fun nameParts(name: String): List<String> =
        Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*")
            .findAll(name.lowercase(Locale.ROOT).replace('’', '\''))
            .map { it.value }.toList()
}
