package dev.lmnaide.calendar.ui.main

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lmnaide.calendar.domain.EventIndex
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.ui.common.DayNumber
import dev.lmnaide.calendar.ui.common.EventBar
import dev.lmnaide.calendar.ui.common.Fmt
import dev.lmnaide.calendar.ui.common.GridEventBlock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

private val GutterWidth = 52.dp
private val HourHeight = 56.dp
private val LaneHeight = 22.dp
private const val MIN_BLOCK_MINUTES = 22
private const val COLLAPSED_LANES = 2

/** Day, 3-day and week views: an hourly grid paged horizontally by [days]. */
@Composable
fun TimeGridView(
    days: Int,
    selectedDate: LocalDate,
    weekStart: DayOfWeek,
    index: EventIndex,
    use24h: Boolean,
    now: LocalDateTime,
    onSelectDate: (LocalDate) -> Unit,
    onVisibleRange: (ClosedRange<LocalDate>) -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
) {
    val today = now.toLocalDate()
    val paging = remember(days, weekStart) {
        val anchor = if (days == 7) selectedDate.with(TemporalAdjusters.previousOrSame(weekStart)) else selectedDate
        DayPaging(anchor, days)
    }
    val pagerState = rememberPagerState(initialPage = paging.pageOf(selectedDate)) { PAGE_COUNT }
    PagerDateSync(pagerState, selectedDate, today, paging::pageOf, paging::rangeOf, onSelectDate, onVisibleRange)

    // One scroll position shared by all pages, so swiping keeps the same hours in view.
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        val hour = if (today in paging.rangeOf(pagerState.currentPage)) (now.hour - 1).coerceIn(0, 15) else 7
        scroll.scrollTo(with(density) { (HourHeight * hour).roundToPx() })
    }

    var draftSlot by remember { mutableStateOf<LocalDateTime?>(null) }
    var allDayExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pagerState.settledPage) { draftSlot = null }

    HorizontalPager(state = pagerState, beyondViewportPageCount = 1, key = { it }) { page ->
        val range = paging.rangeOf(page)
        val dates = remember(range) { (0 until days).map { range.start.plusDays(it.toLong()) } }
        val occurrences = remember(index, range) { index.occurrences(range.start, range.endInclusive) }
        TimeGridPage(
            dates = dates,
            occurrences = occurrences,
            today = today,
            now = now,
            use24h = use24h,
            scroll = scroll,
            draftSlot = draftSlot,
            allDayExpanded = allDayExpanded,
            onToggleAllDay = { allDayExpanded = !allDayExpanded },
            onSlotTap = { slot -> draftSlot = slot },
            onCreateAt = { draftSlot = null; onCreateAt(it) },
            onOpenEvent = onOpenEvent,
            onOpenDay = onOpenDay,
        )
    }
}

@Composable
private fun TimeGridPage(
    dates: List<LocalDate>,
    occurrences: List<Occurrence>,
    today: LocalDate,
    now: LocalDateTime,
    use24h: Boolean,
    scroll: ScrollState,
    draftSlot: LocalDateTime?,
    allDayExpanded: Boolean,
    onToggleAllDay: () -> Unit,
    onSlotTap: (LocalDateTime) -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    val laneItems = remember(occurrences, dates) {
        assignLanes(occurrences.filter { it.inAllDayLane }, dates.first(), dates.size)
    }
    val timed = remember(occurrences) { occurrences.filterNot { it.inAllDayLane } }
    val lineColor = MaterialTheme.colorScheme.outlineVariant

    Column(Modifier.fillMaxSize()) {
        DayHeaderRow(dates, today, onOpenDay)
        AllDayLanes(dates, laneItems, now, allDayExpanded, onToggleAllDay, onOpenEvent)
        HorizontalDivider(color = lineColor)
        Row(
            Modifier
                .weight(1f)
                .verticalScroll(scroll),
        ) {
            HourGutter(use24h)
            Row(
                Modifier
                    .weight(1f)
                    .height(HourHeight * 24)
                    .drawBehind {
                        val hour = HourHeight.toPx()
                        for (h in 1 until 24) {
                            drawLine(lineColor, Offset(0f, h * hour), Offset(size.width, h * hour), 1.dp.toPx())
                        }
                        for (d in 0 until dates.size) {
                            val x = size.width * d / dates.size
                            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        }
                    },
            ) {
                dates.forEach { date ->
                    DayColumn(
                        date = date,
                        occurrences = timed.filter { it.occursOn(date) },
                        isToday = date == today,
                        now = now,
                        use24h = use24h,
                        draftSlot = draftSlot?.takeIf { it.toLocalDate() == date },
                        onSlotTap = onSlotTap,
                        onCreateAt = onCreateAt,
                        onOpenEvent = onOpenEvent,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayHeaderRow(dates: List<LocalDate>, today: LocalDate, onOpenDay: (LocalDate) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp),
    ) {
        Spacer(Modifier.width(GutterWidth))
        dates.forEach { date ->
            val isToday = date == today
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = dates.size > 1) { onOpenDay(date) }
                    .padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    Fmt.weekdayShort(date.dayOfWeek),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DayNumber(date, today, size = 32.dp, fontSize = 16)
            }
        }
    }
}

@Composable
private fun AllDayLanes(
    dates: List<LocalDate>,
    items: List<LaneItem>,
    now: LocalDateTime,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
) {
    if (items.isEmpty()) return
    val laneCount = items.maxOf { it.lane } + 1
    val collapsible = laneCount > COLLAPSED_LANES + 1
    val collapsed = collapsible && !expanded
    val visibleLanes = if (collapsed) COLLAPSED_LANES + 1 else laneCount

    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.width(GutterWidth), contentAlignment = Alignment.TopCenter) {
            if (collapsible) {
                IconButton(onClick = onToggle, modifier = Modifier.size(LaneHeight * 2)) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse all-day events" else "Expand all-day events",
                    )
                }
            }
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .height(LaneHeight * visibleLanes + 4.dp),
        ) {
            val columnWidth = maxWidth / dates.size
            items.filter { !collapsed || it.lane < COLLAPSED_LANES }.forEach { item ->
                EventBar(
                    occurrence = item.occurrence,
                    now = now,
                    onClick = { onOpenEvent(item.occurrence) },
                    modifier = Modifier
                        .offset(x = columnWidth * item.startCol, y = LaneHeight * item.lane)
                        .width(columnWidth * (item.endCol - item.startCol + 1))
                        .height(LaneHeight)
                        .padding(1.dp),
                )
            }
            if (collapsed) {
                dates.indices.forEach { col ->
                    val hidden = items.count { it.lane >= COLLAPSED_LANES && col in it.startCol..it.endCol }
                    if (hidden > 0) {
                        Text(
                            "+$hidden",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .offset(x = columnWidth * col, y = LaneHeight * COLLAPSED_LANES)
                                .width(columnWidth)
                                .height(LaneHeight)
                                .clickable(onClick = onToggle)
                                .padding(start = 6.dp, top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HourGutter(use24h: Boolean) {
    Box(
        Modifier
            .width(GutterWidth)
            .height(HourHeight * 24),
    ) {
        for (hour in 1 until 24) {
            Text(
                Fmt.hourLabel(hour, use24h),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .offset(y = HourHeight * hour - 8.dp)
                    .width(GutterWidth)
                    .padding(end = 8.dp),
            )
        }
    }
}

@Composable
private fun DayColumn(
    date: LocalDate,
    occurrences: List<Occurrence>,
    isToday: Boolean,
    now: LocalDateTime,
    use24h: Boolean,
    draftSlot: LocalDateTime?,
    onSlotTap: (LocalDateTime) -> Unit,
    onCreateAt: (LocalDateTime) -> Unit,
    onOpenEvent: (Occurrence) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier.pointerInput(date) {
            detectTapGestures { offset ->
                val hour = (offset.y / HourHeight.toPx()).toInt().coerceIn(0, 23)
                onSlotTap(date.atTime(hour, 0))
            }
        },
    ) {
        val placed = remember(occurrences, date) { layoutDay(occurrences, date) }
        placed.forEach { p ->
            val columnWidth = maxWidth / p.columns
            val height = minutesToDp(p.endMin - p.startMin)
            val compact = columnWidth < 64.dp
            val start = p.occurrence.start.toLocalTime()
            GridEventBlock(
                occurrence = p.occurrence,
                timeText = if (compact) Fmt.time(start, use24h) else Fmt.timeRange(start, p.occurrence.end.toLocalTime(), use24h),
                height = height,
                now = now,
                onClick = { onOpenEvent(p.occurrence) },
                compact = compact,
                modifier = Modifier
                    .offset(x = columnWidth * p.column, y = minutesToDp(p.startMin))
                    .width(columnWidth * p.span)
                    .height(height)
                    .padding(start = 1.dp, end = 2.dp, bottom = 1.dp),
            )
        }

        if (draftSlot != null) {
            val narrow = maxWidth < 96.dp
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = if (narrow) Arrangement.Center else Arrangement.Start,
                modifier = Modifier
                    .offset(y = minutesToDp(draftSlot.hour * 60))
                    .fillMaxWidth()
                    .height(HourHeight)
                    .padding(horizontal = 2.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    .clickable { onCreateAt(draftSlot) }
                    .padding(4.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "New event", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                if (!narrow) {
                    Text(
                        "New event",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 2.dp, top = 1.dp),
                    )
                }
            }
        }

        if (isToday) {
            val y = minutesToDp(now.hour * 60 + now.minute)
            val color = MaterialTheme.colorScheme.primary
            Box(
                Modifier
                    .offset(y = y - 1.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(color),
            )
            Box(
                Modifier
                    .offset(x = (-5).dp, y = y - 5.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

private fun minutesToDp(minutes: Int): Dp = HourHeight * (minutes / 60f)

private class Placed(val occurrence: Occurrence, val startMin: Int, val endMin: Int) {
    var column = 0
    var columns = 1
    var span = 1
}

/** Positions overlapping events side by side, like most calendar apps do. */
private fun layoutDay(occurrences: List<Occurrence>, date: LocalDate): List<Placed> {
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
