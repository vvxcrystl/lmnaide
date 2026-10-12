package dev.lmnaide.calendar.performance

import com.sun.management.ThreadMXBean
import dev.lmnaide.calendar.contacts.ContactMatcher
import dev.lmnaide.calendar.contacts.EventPerson
import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.domain.EventIndex
import dev.lmnaide.calendar.domain.RecurrenceExpander
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.time.LocalDate
import java.util.Locale

/** Opt-in host-JVM microbenchmark. No timing assertions or claims about device frame rates. */
class PerformanceBenchmarkTest {
    private var sink: Any? = null

    @Test fun compareRepresentativeSearchAndContactMatching() {
        assumeTrue("Set LMNAIDE_PERF_BENCHMARK=1 to run", System.getenv("LMNAIDE_PERF_BENCHMARK") == "1")
        val today = LocalDate.of(2026, 10, 11)
        val calendar = CalendarEntity(id = 1, name = "Personal", color = 42)
        val events = List(250) { id ->
            EventEntity(id = id.toLong(), calendarId = 1, title = "Daily $id", allDay = true,
                start = EventEntity.toStored(today.minusYears(1).atStartOfDay(), true),
                end = EventEntity.toStored(today.minusYears(1).plusDays(1).atStartOfDay(), true),
                recurrence = Recurrence.DAILY)
        }
        val index = EventIndex(events, listOf(calendar))
        val oldSearch = {
            events.map { event ->
                RecurrenceExpander.expand(event, calendar.color, calendar.name, today, today.plusYears(5)).firstOrNull()
            }
        }
        val newSearch = { events.map { index.representative(it, today) } }
        assertEquals(oldSearch(), newSearch())
        compare("250 daily series, next occurrence in five-year window", oldSearch, newSearch)

        val contacts = List(5_000) { EventPerson("Person $it Family${it % 100}", "$it") }
        val contactIndex = ContactMatcher.Index(contacts)
        val names = listOf("Person 100", "Family50", "Missing")
        val oldMatches = { names.map { legacyMatches(it, contacts) } }
        val newMatches = { names.map(contactIndex::matches) }
        assertEquals(oldMatches(), newMatches())
        compare("three names in 5,000 contacts (index already built)", oldMatches, newMatches)
    }

    private fun compare(label: String, before: () -> Any, after: () -> Any) {
        repeat(5) { sink = before(); sink = after() }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().id
        fun measure(block: () -> Any): Pair<Double, Long> {
            val bytes = bean.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            sink = block()
            val elapsed = System.nanoTime() - start
            return elapsed / 1_000_000.0 to (bean.getThreadAllocatedBytes(thread) - bytes)
        }
        val old = mutableListOf<Pair<Double, Long>>()
        val new = mutableListOf<Pair<Double, Long>>()
        repeat(11) { round ->
            if (round % 2 == 0) { old += measure(before); new += measure(after) }
            else { new += measure(after); old += measure(before) }
        }
        println(label)
        println("before: median ${old.map { it.first }.sorted()[5]} ms, ${old.map { it.second }.sorted()[5]} bytes")
        println("after: median ${new.map { it.first }.sorted()[5]} ms, ${new.map { it.second }.sorted()[5]} bytes")
    }

    // Previous implementation, including per-contact regex compilation and window allocations.
    private fun legacyMatches(name: String, contacts: List<EventPerson>): List<EventPerson> {
        fun parts(value: String) = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*")
            .findAll(value.lowercase(Locale.ROOT).replace('’', '\'')).map { it.value }.toList()
        val nameParts = parts(name)
        if (nameParts.isEmpty()) return emptyList()
        val candidates = contacts.map { it to parts(it.name) }
        val exact = candidates.filter { (_, words) -> words == nameParts }.map { it.first }
        if (exact.isNotEmpty()) return exact
        return candidates.filter { (_, words) -> words.windowed(nameParts.size).any { it == nameParts } }.map { it.first }
    }
}
