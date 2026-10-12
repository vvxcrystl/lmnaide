package dev.lmnaide.calendar.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.lmnaide.calendar.domain.EventIndex
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.ui.common.DayNumber
import dev.lmnaide.calendar.ui.common.EventCard
import dev.lmnaide.calendar.ui.common.Fmt
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

private sealed interface ScheduleRow {
    val key: String
    val date: LocalDate

    data class MonthHeader(val month: YearMonth) : ScheduleRow {
        override val key = "m$month"
        override val date: LocalDate = month.atDay(1)
    }

    /** A "Oct 11 – 17" label opening each week that has something in it. */
    data class WeekHeader(override val date: LocalDate, val end: LocalDate) : ScheduleRow {
        override val key = "w$date"
    }

    data class Day(override val date: LocalDate, val occurrences: List<Occurrence>) : ScheduleRow {
        override val key = "d$date"
    }
}

/** A continuous agenda list, opening at [selectedDate] and growing as it's scrolled. */
@Composable
fun ScheduleView(
    selectedDate: LocalDate,
    jumpCount: Int,
    index: EventIndex,
    weekStart: DayOfWeek,
    use24h: Boolean,
    now: LocalDateTime,
    onVisibleRange: (ClosedRange<LocalDate>) -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onToggleTask: (Occurrence) -> Unit,
) {
    // The list only rebuilds when a jump lands outside the dates it has loaded. Otherwise, as when
    // the date button takes you back to today, it scrolls there.
    val list = remember { ListAnchor(selectedDate) }
    val rebuilt = selectedDate < listStart(list.anchor) || selectedDate > listLimit(list.anchor)
    if (rebuilt) {
        list.arrivingFrom = list.shown
        list.anchor = selectedDate
    }
    key(list.anchor) {
        ScheduleList(
            list.anchor, selectedDate, jumpCount, list.arrivingFrom, index, weekStart, use24h, now,
            onVisibleRange = { range ->
                list.shown = range.start
                onVisibleRange(range)
            },
            onOpenEvent, onOpenDay, onToggleTask,
        )
    }
}

/**
 * The date the list was built around, the first date in view, and, when the list was rebuilt by a
 * jump, the date that was in view before it, so the new list can scroll in from that side.
 */
private class ListAnchor(var anchor: LocalDate) {
    var shown: LocalDate = anchor
    var arrivingFrom: LocalDate? = null
}

private fun listStart(anchor: LocalDate): LocalDate = YearMonth.from(anchor).minusMonths(1).atDay(1)

private fun listLimit(anchor: LocalDate): LocalDate = anchor.plusYears(5)

/** How many rows short of the target a long jump starts, so it scrolls the last stretch. */
private const val JUMP_RUN_UP = 6

@Composable
private fun ScheduleList(
    anchor: LocalDate,
    target: LocalDate,
    jumpCount: Int,
    arrivingFrom: LocalDate?,
    index: EventIndex,
    weekStart: DayOfWeek,
    use24h: Boolean,
    now: LocalDateTime,
    onVisibleRange: (ClosedRange<LocalDate>) -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onToggleTask: (Occurrence) -> Unit,
) {
    val today = now.toLocalDate()
    val start = remember(anchor) { listStart(anchor) }
    var loadedEnd by remember(anchor) { mutableStateOf(YearMonth.from(anchor).plusMonths(6).atEndOfMonth()) }
    val limit = remember(anchor) { listLimit(anchor) }
    // A jump past what's loaded loads up to it.
    val end = maxOf(loadedEnd, YearMonth.from(target).plusMonths(1).atEndOfMonth())
    val currentEnd by rememberUpdatedState(end)

    // Selection and week labels don't change the expanded event data.
    val byDay = remember(index, start, end) { index.byDay(start, end) }
    val rows = remember(byDay, start, end, today, anchor, target, weekStart) { buildRows(byDay, start, end, today, setOf(anchor, target), weekStart) }
    val currentRows by rememberUpdatedState(rows)
    // A list rebuilt by a jump opens a few rows short of the target, on the side it came from.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = indexOf(rows, target).let { to ->
            when {
                arrivingFrom == null -> to
                arrivingFrom > target -> to + JUMP_RUN_UP
                else -> (to - JUMP_RUN_UP).coerceAtLeast(0)
            }
        },
    )
    // Events load after the first frame; once they arrive, put the target date back at the top.
    var positioned by remember(anchor) { mutableStateOf(index !== EventIndex.Empty) }
    LaunchedEffect(rows) {
        if (!positioned && index !== EventIndex.Empty) {
            listState.scrollToItem(indexOf(rows, target))
            positioned = true
        }
    }
    // Jumps scroll to their date. A long one cuts to a few rows short and scrolls the rest, so you
    // see which way it went without scrolling through every week in between.
    val handled = remember(anchor) { HandledJump(if (arrivingFrom == null) target to jumpCount else null) }
    LaunchedEffect(target, jumpCount) {
        if (handled.jump == target to jumpCount) return@LaunchedEffect
        handled.jump = target to jumpCount
        val to = indexOf(currentRows, target)
        val from = listState.firstVisibleItemIndex
        if (from - to > JUMP_RUN_UP) listState.scrollToItem(to + JUMP_RUN_UP)
        if (to - from > JUMP_RUN_UP) listState.scrollToItem(to - JUMP_RUN_UP)
        listState.animateScrollToItem(to)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { i -> currentRows.getOrNull(i)?.date?.let { onVisibleRange(it..it) } }
    }
    LaunchedEffect(listState) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= currentRows.size - 8 }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd && loadedEnd < limit) loadedEnd = maxOf(loadedEnd, currentEnd).plusMonths(6) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
    ) {
        items(rows, key = { it.key }, contentType = { it.javaClass }) { row ->
            when (row) {
                is ScheduleRow.MonthHeader -> MonthBanner(
                    row.month,
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                )
                is ScheduleRow.WeekHeader -> WeekLabel(row.date, row.end)
                is ScheduleRow.Day -> DayRow(
                    row,
                    // How far this day has scrolled up under the top edge, read at layout time.
                    scrolledPast = { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == row.key }?.offset?.let { -it } ?: 0 },
                    today,
                    use24h,
                    now,
                    onOpenEvent,
                    onOpenDay,
                    onToggleTask,
                )
            }
        }
    }
}

/** The last jump scrolled to, so the one a list opens with isn't run again. */
private class HandledJump(var jump: Pair<LocalDate, Int>?)

/** The row for [date], or the month banner and week label sitting directly above it. */
private fun indexOf(rows: List<ScheduleRow>, date: LocalDate): Int {
    var i = rows.indexOfFirst { it is ScheduleRow.Day && it.date >= date }.coerceAtLeast(0)
    val month = YearMonth.from(rows.getOrNull(i)?.date ?: return 0)
    while (i > 0) {
        val above = rows[i - 1]
        if (above !is ScheduleRow.WeekHeader && !(above is ScheduleRow.MonthHeader && above.month == month)) break
        i--
    }
    return i
}

private fun buildRows(
    byDay: Map<LocalDate, List<Occurrence>>,
    start: LocalDate,
    end: LocalDate,
    today: LocalDate,
    alwaysShown: Set<LocalDate>,
    weekStart: DayOfWeek,
): List<ScheduleRow> {
    val rows = mutableListOf<ScheduleRow>()
    var month = YearMonth.from(start).minusMonths(1)
    var labelledWeek: LocalDate? = null
    var day = start
    while (day <= end) {
        if (YearMonth.from(day) != month) {
            month = YearMonth.from(day)
            rows += ScheduleRow.MonthHeader(month)
            // A new month banner restarts the week label, like Google Calendar does.
            labelledWeek = null
        }
        val occurrences = byDay[day].orEmpty()
        if (occurrences.isNotEmpty() || day == today || day in alwaysShown) {
            val week = day.with(TemporalAdjusters.previousOrSame(weekStart))
            if (week != labelledWeek) {
                labelledWeek = week
                rows += ScheduleRow.WeekHeader(maxOf(week, month.atDay(1)), minOf(week.plusDays(6), month.atEndOfMonth()))
            }
            rows += ScheduleRow.Day(day, occurrences)
        }
        day = day.plusDays(1)
    }
    return rows
}

@Composable
private fun WeekLabel(start: LocalDate, end: LocalDate) {
    Text(
        Fmt.weekRange(start, end),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 72.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** The current-time marker Google draws between today's past and upcoming events. */
@Composable
private fun NowLine() {
    val color = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(color))
        Box(Modifier.size(12.dp).background(color, CircleShape))
    }
}

@Composable
private fun DayRow(
    row: ScheduleRow.Day,
    scrolledPast: () -> Int,
    today: LocalDate,
    use24h: Boolean,
    now: LocalDateTime,
    onOpenEvent: (Occurrence) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onToggleTask: (Occurrence) -> Unit,
) {
    val isToday = row.date == today
    var rowHeight by remember { mutableIntStateOf(0) }
    var labelHeight by remember { mutableIntStateOf(0) }
    Row(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { rowHeight = it.height }
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
    ) {
        Column(
            Modifier
                // Keep the date pinned to the top while its events scroll by, until the next day pushes it off.
                .offset { IntOffset(0, scrolledPast().coerceIn(0, (rowHeight - labelHeight - 16.dp.roundToPx()).coerceAtLeast(0))) }
                .onSizeChanged { labelHeight = it.height }
                .width(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onOpenDay(row.date) }
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                Fmt.weekdayShort(row.date.dayOfWeek),
                style = MaterialTheme.typography.labelSmall,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DayNumber(row.date, today, size = 34.dp, fontSize = 18)
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 8.dp, top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (row.occurrences.isEmpty()) {
                Text(
                    if (isToday) "Nothing planned today" else "Nothing planned",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, start = 4.dp),
                )
            }
            // Untimed items and anything already started sit above the line; the rest below it.
            val nowAt = if (!isToday) -1 else row.occurrences.indexOfFirst { !it.allDay && it.start > now }
                .let { if (it < 0) row.occurrences.size else it }
            row.occurrences.forEachIndexed { i, occurrence ->
                if (i == nowAt) NowLine()
                val time = when {
                    occurrence.isTask && occurrence.allDay -> "Task"
                    occurrence.isTask -> "Task, due ${Fmt.time(occurrence.start.toLocalTime(), use24h)}"
                    occurrence.allDay -> "All day"
                    occurrence.startDate == occurrence.lastDate -> Fmt.occurrenceTime(occurrence, use24h)
                    row.date == occurrence.startDate -> "From ${Fmt.time(occurrence.start.toLocalTime(), use24h)}"
                    row.date == occurrence.lastDate -> "Until ${Fmt.time(occurrence.end.toLocalTime(), use24h)}"
                    else -> "All day"
                }
                // Keyed so a card's animated state stays with its event when others are added or removed.
                key(occurrence.key) {
                    EventCard(
                        occurrence,
                        time,
                        occurrence.event.location,
                        now,
                        onClick = { onOpenEvent(occurrence) },
                        onToggleTask = { onToggleTask(occurrence) },
                    )
                }
            }
            if (nowAt == row.occurrences.size) NowLine()
        }
    }
}
