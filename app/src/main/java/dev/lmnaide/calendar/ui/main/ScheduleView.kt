package dev.lmnaide.calendar.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.lmnaide.calendar.domain.EventIndex
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.ui.common.DayNumber
import dev.lmnaide.calendar.ui.common.EventCard
import dev.lmnaide.calendar.ui.common.Fmt
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

private sealed interface ScheduleRow {
    val key: String
    val date: LocalDate

    data class MonthHeader(val month: YearMonth) : ScheduleRow {
        override val key = "m$month"
        override val date: LocalDate = month.atDay(1)
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
    use24h: Boolean,
    now: LocalDateTime,
    onVisibleRange: (ClosedRange<LocalDate>) -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onToggleTask: (Occurrence) -> Unit,
) {
    // Jumping to another date rebuilds the list around it.
    key(selectedDate, jumpCount) {
        ScheduleList(selectedDate, index, use24h, now, onVisibleRange, onOpenEvent, onOpenDay, onToggleTask)
    }
}

@Composable
private fun ScheduleList(
    anchor: LocalDate,
    index: EventIndex,
    use24h: Boolean,
    now: LocalDateTime,
    onVisibleRange: (ClosedRange<LocalDate>) -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onToggleTask: (Occurrence) -> Unit,
) {
    val today = now.toLocalDate()
    val start = remember(anchor) { YearMonth.from(anchor).minusMonths(1).atDay(1) }
    var end by remember(anchor) { mutableStateOf(YearMonth.from(anchor).plusMonths(6).atEndOfMonth()) }
    val limit = remember(anchor) { anchor.plusYears(5) }

    val rows = remember(index, start, end, today, anchor) { buildRows(index, start, end, today, anchor) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = indexOf(rows, anchor))
    // Events load after the first frame; once they arrive, put the anchor date back at the top.
    var positioned by remember(anchor) { mutableStateOf(false) }
    LaunchedEffect(rows) {
        if (!positioned && index !== EventIndex.Empty) {
            listState.scrollToItem(indexOf(rows, anchor))
            positioned = true
        }
    }
    val currentRows by rememberUpdatedState(rows)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { i -> currentRows.getOrNull(i)?.date?.let { onVisibleRange(it..it) } }
    }
    LaunchedEffect(listState) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= currentRows.size - 8 }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd && end < limit) end = end.plusMonths(6) }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is ScheduleRow.MonthHeader -> MonthBanner(
                    row.month,
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                )
                is ScheduleRow.Day -> DayRow(row, today, use24h, now, onOpenEvent, onOpenDay, onToggleTask)
            }
        }
    }
}

/** The row for [date], or its month banner when the date opens the month. */
private fun indexOf(rows: List<ScheduleRow>, date: LocalDate): Int {
    val day = rows.indexOfFirst { it is ScheduleRow.Day && it.date >= date }.coerceAtLeast(0)
    val header = rows.getOrNull(day - 1)
    return if (header is ScheduleRow.MonthHeader && header.month == YearMonth.from(rows[day].date)) day - 1 else day
}

private fun buildRows(
    index: EventIndex,
    start: LocalDate,
    end: LocalDate,
    today: LocalDate,
    anchor: LocalDate,
): List<ScheduleRow> {
    val byDay = index.byDay(start, end)
    val rows = mutableListOf<ScheduleRow>()
    var month = YearMonth.from(start).minusMonths(1)
    var day = start
    while (day <= end) {
        if (YearMonth.from(day) != month) {
            month = YearMonth.from(day)
            rows += ScheduleRow.MonthHeader(month)
        }
        val occurrences = byDay[day].orEmpty()
        if (occurrences.isNotEmpty() || day == today || day == anchor) rows += ScheduleRow.Day(day, occurrences)
        day = day.plusDays(1)
    }
    return rows
}

@Composable
private fun DayRow(
    row: ScheduleRow.Day,
    today: LocalDate,
    use24h: Boolean,
    now: LocalDateTime,
    onOpenEvent: (Occurrence) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onToggleTask: (Occurrence) -> Unit,
) {
    val isToday = row.date == today
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(
            Modifier
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (row.occurrences.isEmpty()) {
                Text(
                    if (isToday) "Nothing planned today" else "Nothing planned",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, start = 4.dp),
                )
            }
            row.occurrences.forEach { occurrence ->
                val time = when {
                    occurrence.isTask && occurrence.allDay -> "Task"
                    occurrence.isTask -> "Task, due ${Fmt.time(occurrence.start.toLocalTime(), use24h)}"
                    occurrence.allDay -> "All day"
                    occurrence.startDate == occurrence.lastDate -> Fmt.occurrenceTime(occurrence, use24h)
                    row.date == occurrence.startDate -> "From ${Fmt.time(occurrence.start.toLocalTime(), use24h)}"
                    row.date == occurrence.lastDate -> "Until ${Fmt.time(occurrence.end.toLocalTime(), use24h)}"
                    else -> "All day"
                }
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
    }
}
