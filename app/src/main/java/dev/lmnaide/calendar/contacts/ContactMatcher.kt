package dev.lmnaide.calendar.contacts

import java.util.Locale

/** A name snapshot survives renames/deletions; lookupUri opens the current device contact. */
data class EventPerson(val name: String, val lookupUri: String)

object ContactMatcher {
    private val withPattern = Regex("\\bwith\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val separatorPattern = Regex("\\s*,\\s*|\\s+and\\s+|\\s*&\\s*", RegexOption.IGNORE_CASE)
    private val namePattern = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*")

    fun names(title: String): List<String> {
        val people = withPattern.find(title)?.groupValues?.get(1) ?: return emptyList()
        return people.split(separatorPattern)
            .map { it.trim().trim('.', ',') }.filter { it.isNotEmpty() && it.lowercase() !in listOf("i", "me", "myself") }.distinct()
    }

    fun matches(name: String, contacts: List<EventPerson>): List<EventPerson> = Index(contacts).matches(name)

    /** Normalize a contact snapshot once, then reuse it as the event title changes. */
    class Index(contacts: List<EventPerson>) {
        private val exactNames = HashMap<List<String>, MutableList<EventPerson>>()
        private val byWord = HashMap<String, MutableList<Pair<EventPerson, List<String>>>>()

        init {
            for (contact in contacts) {
                val words = nameParts(contact.name)
                exactNames.getOrPut(words) { mutableListOf() } += contact
                val candidate = contact to words
                for (word in words.distinct()) {
                    byWord.getOrPut(word) { mutableListOf() } += candidate
                }
            }
        }

        fun matches(name: String): List<EventPerson> {
            val parts = nameParts(name)
            if (parts.isEmpty()) return emptyList()
            exactNames[parts]?.let { return it }
            // Start with the rarest requested word, keeping the original contact ordering.
            var candidates: List<Pair<EventPerson, List<String>>>? = null
            for (part in parts) {
                val containing = byWord[part] ?: return emptyList()
                if (candidates == null || containing.size < candidates.size) candidates = containing
            }
            // Match consecutive whole name parts and retain every ambiguous match.
            return candidates.orEmpty().filter { (_, words) ->
                (0..words.size - parts.size).any { start ->
                    parts.indices.all { words[start + it] == parts[it] }
                }
            }.map { it.first }
        }
    }

    private fun nameParts(name: String): List<String> =
        namePattern.findAll(name.lowercase(Locale.ROOT).replace('’', '\''))
            .map { it.value }.toList()
}
