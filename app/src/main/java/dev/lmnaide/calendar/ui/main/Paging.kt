package dev.lmnaide.calendar.ui.main

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.lmnaide.calendar.domain.EventIndex
import dev.lmnaide.calendar.domain.Occurrence
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

internal const val PAGE_COUNT = 40_000
internal const val CENTER_PAGE = PAGE_COUNT / 2

/** Maps pager pages to consecutive periods of [days] days, page [CENTER_PAGE] starting at [anchor]. */
internal class DayPaging(private val anchor: LocalDate, val days: Int) {
    fun startOf(page: Int): LocalDate = anchor.plusDays((page - CENTER_PAGE).toLong() * days)

    fun rangeOf(page: Int): ClosedRange<LocalDate> = startOf(page).let { it..it.plusDays(days - 1L) }

    fun pageOf(date: LocalDate): Int =
        CENTER_PAGE + Math.floorDiv(ChronoUnit.DAYS.between(anchor, date), days.toLong()).toInt()
}

internal object MonthPaging {
    private val base = YearMonth.of(2000, 1)

    fun monthOf(page: Int): YearMonth = base.plusMonths((page - CENTER_PAGE).toLong())

    fun pageOf(date: LocalDate): Int = CENTER_PAGE + ChronoUnit.MONTHS.between(base, YearMonth.from(date)).toInt()

    fun rangeOf(page: Int): ClosedRange<LocalDate> = monthOf(page).let { it.atDay(1)..it.atEndOfMonth() }
}

/**
 * Keeps a pager and the selected date in sync in both directions: jumping to a date scrolls the
 * pager, and swiping to another period moves the selection into it.
 */
@Composable
internal fun PagerDateSync(
    pagerState: PagerState,
    selectedDate: LocalDate,
    today: LocalDate,
    pageOf: (LocalDate) -> Int,
    rangeOf: (Int) -> ClosedRange<LocalDate>,
    onSelectDate: (LocalDate) -> Unit,
    onVisibleRange: (ClosedRange<LocalDate>) -> Unit,
) {
    val currentSelected by rememberUpdatedState(selectedDate)
    val currentToday by rememberUpdatedState(today)
    val currentRangeOf by rememberUpdatedState(rangeOf)
    val currentOnSelect by rememberUpdatedState(onSelectDate)
    val currentOnVisible by rememberUpdatedState(onVisibleRange)

    LaunchedEffect(selectedDate) {
        val target = pageOf(selectedDate)
        val current = pagerState.currentPage
        when {
            target == current -> Unit
            abs(target - current) == 1 -> pagerState.animateScrollToPage(target)
            else -> pagerState.scrollToPage(target)
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val range = currentRangeOf(page)
            currentOnVisible(range)
            if (currentSelected !in range) {
                currentOnSelect(if (currentToday in range) currentToday else range.start)
            }
        }
    }
}

/** An occurrence placed on a horizontal lane spanning columns [startCol]..[endCol]. */
internal data class LaneItem(val occurrence: Occurrence, val startCol: Int, val endCol: Int, val lane: Int)

/** Stacks occurrences into non-overlapping lanes across [dayCount] days starting at [firstDay]. */
internal fun assignLanes(occurrences: List<Occurrence>, firstDay: LocalDate, dayCount: Int): List<LaneItem> {
    val lastDay = firstDay.plusDays(dayCount - 1L)
    val laneEnds = mutableListOf<Int>()
    return occurrences
        .filter { it.startDate <= lastDay && it.lastDate >= firstDay }
        .map { occurrence ->
            val start = ChronoUnit.DAYS.between(firstDay, maxOf(occurrence.startDate, firstDay)).toInt()
            val end = ChronoUnit.DAYS.between(firstDay, minOf(occurrence.lastDate, lastDay)).toInt()
            Triple(occurrence, start, end)
        }
        .sortedWith(
            compareBy<Triple<Occurrence, Int, Int>> { it.second }
                .thenBy { !it.first.inAllDayLane }
                .thenByDescending { it.third - it.second }
                .thenComparator { a, b -> EventIndex.OccurrenceOrder.compare(a.first, b.first) },
        )
        .map { (occurrence, start, end) ->
            var lane = laneEnds.indexOfFirst { it < start }
            if (lane == -1) {
                lane = laneEnds.size
                laneEnds += end
            } else {
                laneEnds[lane] = end
            }
            LaneItem(occurrence, start, end, lane)
        }
}
