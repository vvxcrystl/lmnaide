package dev.lmnaide.calendar.ui.main

import dev.lmnaide.calendar.domain.Occurrence
import java.time.LocalDate

private const val MIN_BLOCK_MINUTES = 22

internal class Placed(val occurrence: Occurrence, val startMin: Int, val endMin: Int) {
    var column = 0
    var columns = 1
    var span = 1
}

/** Positions overlapping events side by side, like most calendar apps do. */
internal fun layoutDay(occurrences: List<Occurrence>, date: LocalDate): List<Placed> {
    val items = occurrences.map { o ->
        val start = if (o.startDate < date) 0 else o.start.hour * 60 + o.start.minute
        val rawEnd = if (o.end.toLocalDate() > date) 24 * 60 else o.end.hour * 60 + o.end.minute
        val end = maxOf(rawEnd, start + MIN_BLOCK_MINUTES).coerceAtMost(24 * 60)
        Placed(o, minOf(start, end - MIN_BLOCK_MINUTES), end)
    }.sortedWith(compareBy<Placed> { it.startMin }.thenByDescending { it.endMin })

    val result = mutableListOf<Placed>()
    val cluster = mutableListOf<Placed>()
    val columnEnds = mutableListOf<Int>()
    var clusterEnd = -1

    fun flush() {
        val byColumn = cluster.groupBy { it.column }
        cluster.forEach { item ->
            item.columns = columnEnds.size
            // Let an event widen into free columns to its right.
            var span = 1
            while (item.column + span < columnEnds.size &&
                !byColumn.getValue(item.column + span).overlaps(item.startMin, item.endMin)
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

/** Each column is ordered and non-overlapping, so only the first event ending after start matters. */
private fun List<Placed>.overlaps(start: Int, end: Int): Boolean {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle].endMin <= start) low = middle + 1 else high = middle
    }
    return low < size && this[low].startMin < end
}
