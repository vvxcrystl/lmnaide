package dev.lmnaide.calendar.ui.main

import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.domain.Occurrence
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

private const val MIN_BLOCK_MINUTES = 22

class TimeGridLayoutTest {
    private val day = LocalDate.of(2026, 10, 11)

    private fun occurrence(id: Int, start: Int, end: Int): Occurrence {
        val event = EventEntity(id = id.toLong(), calendarId = 1, title = "$id", start = 0, end = 0)
        return Occurrence(event, day.atStartOfDay().plusMinutes(start.toLong()),
            day.atStartOfDay().plusMinutes(end.toLong()), 0, "")
    }

    private fun assertSameLayout(events: List<Occurrence>) {
        val expected = legacyLayoutDay(events, day).map {
            listOf(it.occurrence.key, it.startMin, it.endMin, it.column, it.columns, it.span)
        }
        val actual = layoutDay(events, day).map {
            listOf(it.occurrence.key, it.startMin, it.endMin, it.column, it.columns, it.span)
        }
        assertEquals(expected, actual)
    }

    @Test fun preservesColumnsWidthsMinimumHeightAndMidnightClipping() {
        assertSameLayout(emptyList())
        assertSameLayout(listOf(occurrence(1, -60, 60), occurrence(2, 30, 90),
            occurrence(3, 60, 90), occurrence(4, 90, 90), occurrence(5, 1439, 1500)))
        assertSameLayout(List(100) { occurrence(it, 60, 120) })
        // A long event links many short events into one cluster with free space to widen into.
        assertSameLayout(listOf(occurrence(0, 0, 1440)) + List(100) {
            occurrence(it + 1, (it * 13) % 1300, (it * 13) % 1300 + 30)
        })
    }

    @Test fun matchesPreviousLayoutForRandomizedDenseDays() {
        val random = Random(731)
        repeat(150) {
            val events = List(random.nextInt(1, 200)) { id ->
                val start = random.nextInt(-120, 1440)
                occurrence(id, start, start + random.nextInt(0, 400))
            }.shuffled(random)
            assertSameLayout(events)
        }
    }
}

// Frozen reference implementation: guards the exact placement and widening behavior.
private class LegacyPlaced(val occurrence: Occurrence, val startMin: Int, val endMin: Int) {
    var column = 0
    var columns = 1
    var span = 1
}

/** Positions overlapping events side by side, like most calendar apps do. */
private fun legacyLayoutDay(occurrences: List<Occurrence>, date: LocalDate): List<LegacyPlaced> {
    val items = occurrences.map { o ->
        val start = if (o.startDate < date) 0 else o.start.hour * 60 + o.start.minute
        val rawEnd = if (o.end.toLocalDate() > date) 24 * 60 else o.end.hour * 60 + o.end.minute
        val end = maxOf(rawEnd, start + MIN_BLOCK_MINUTES).coerceAtMost(24 * 60)
        LegacyPlaced(o, minOf(start, end - MIN_BLOCK_MINUTES), end)
    }.sortedWith(compareBy<LegacyPlaced> { it.startMin }.thenByDescending { it.endMin })

    val result = mutableListOf<LegacyPlaced>()
    val cluster = mutableListOf<LegacyPlaced>()
    val columnEnds = mutableListOf<Int>()
    var clusterEnd = -1

    fun flush() {
        cluster.forEach { item ->
            item.columns = columnEnds.size
            // Let an event widen into free columns to its right.
            var span = 1
            while (item.column + span < columnEnds.size &&
                cluster.none { it.column == item.column + span && it.startMin < item.endMin && it.endMin > item.startMin }
            ) span++
            item.span = span
        }
        result += cluster
        cluster.clear()
        columnEnds.clear()
        clusterEnd = -1
    }

    for (item in items) {
        if (cluster.isNotEmpty() && item.startMin >= clusterEnd) flush()
        val column = columnEnds.indexOfFirst { it <= item.startMin }
        if (column == -1) {
            item.column = columnEnds.size
            columnEnds += item.endMin
        } else {
            item.column = column
            columnEnds[column] = item.endMin
        }
        cluster += item
        clusterEnd = maxOf(clusterEnd, item.endMin)
    }
    flush()
    return result
}
