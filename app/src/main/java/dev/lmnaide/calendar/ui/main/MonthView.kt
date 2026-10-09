package dev.lmnaide.calendar.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lmnaide.calendar.domain.EventIndex
import dev.lmnaide.calendar.ui.common.DayNumber
import dev.lmnaide.calendar.ui.common.EventBar
import dev.lmnaide.calendar.ui.common.Fmt
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

private val DateHeaderHeight = 26.dp
private val ChipHeight = 17.dp
private val ChipSpacing = 1.dp

@Composable
fun MonthView(
    selectedDate: LocalDate,
    weekStart: DayOfWeek,
    index: EventIndex,
    now: LocalDateTime,
    onSelectDate: (LocalDate) -> Unit,
    onVisibleRange: (ClosedRange<LocalDate>) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    val today = now.toLocalDate()
    val pagerState = rememberPagerState(initialPage = MonthPaging.pageOf(selectedDate)) { PAGE_COUNT }
    PagerDateSync(pagerState, selectedDate, today, MonthPaging::pageOf, MonthPaging::rangeOf, onSelectDate, onVisibleRange)

    Column(Modifier.fillMaxSize()) {
        WeekdayHeader(weekStart)
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { it },
            modifier = Modifier.weight(1f),
        ) { page ->
            MonthPage(MonthPaging.monthOf(page), weekStart, index, today, now, onOpenDay)
        }
    }
}

@Composable
internal fun WeekdayHeader(weekStart: DayOfWeek, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        for (i in 0L until 7L) {
            Text(
                Fmt.weekdayShort(weekStart.plus(i)).take(1),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MonthPage(
    month: YearMonth,
    weekStart: DayOfWeek,
    index: EventIndex,
    today: LocalDate,
    now: LocalDateTime,
    onOpenDay: (LocalDate) -> Unit,
) {
    val firstCell = month.atDay(1).with(TemporalAdjusters.previousOrSame(weekStart))
    val weeks = ((ChronoUnit.DAYS.between(firstCell, month.atEndOfMonth()) / 7) + 1).toInt()
    val lanesByWeek = remember(index, month, weekStart) {
        val all = index.occurrences(firstCell, firstCell.plusWeeks(weeks.toLong()).minusDays(1))
        List(weeks) { week -> assignLanes(all, firstCell.plusWeeks(week.toLong()), 7) }
    }
    Column(Modifier.fillMaxSize()) {
        for (week in 0 until weeks) {
            WeekRow(
                firstDay = firstCell.plusWeeks(week.toLong()),
                month = month,
                items = lanesByWeek[week],
                today = today,
                now = now,
                onOpenDay = onOpenDay,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WeekRow(
    firstDay: LocalDate,
    month: YearMonth,
    items: List<LaneItem>,
    today: LocalDate,
    now: LocalDateTime,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(lineColor, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                for (d in 1 until 7) {
                    val x = size.width * d / 7
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            },
    ) {
        val columnWidth = maxWidth / 7
        val maxLanes = ((maxHeight - DateHeaderHeight) / (ChipHeight + ChipSpacing)).toInt().coerceAtLeast(1)
        val laneCount = (items.maxOfOrNull { it.lane } ?: -1) + 1
        // When everything doesn't fit, the last visible lane becomes a "+N" summary.
        val shownLanes = if (laneCount <= maxLanes) laneCount else maxLanes - 1

        Row(Modifier.fillMaxSize()) {
            for (d in 0L until 7L) {
                val date = firstDay.plusDays(d)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onOpenDay(date) },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    DayNumber(
                        date,
                        today,
                        size = 22.dp,
                        fontSize = 12,
                        dimmed = YearMonth.from(date) != month,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        items.filter { it.lane < shownLanes }.forEach { item ->
            EventBar(
                occurrence = item.occurrence,
                now = now,
                fontSize = 10,
                modifier = Modifier
                    .offset(x = columnWidth * item.startCol, y = DateHeaderHeight + (ChipHeight + ChipSpacing) * item.lane)
                    .width(columnWidth * (item.endCol - item.startCol + 1))
                    .height(ChipHeight)
                    .padding(horizontal = 1.5.dp),
            )
        }

        if (shownLanes < laneCount) {
            for (col in 0 until 7) {
                val hidden = items.count { it.lane >= shownLanes && col in it.startCol..it.endCol }
                if (hidden > 0) {
                    Text(
                        "+$hidden",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .offset(x = columnWidth * col, y = DateHeaderHeight + (ChipHeight + ChipSpacing) * shownLanes)
                            .width(columnWidth)
                            .height(ChipHeight)
                            .padding(start = 5.dp),
                    )
                }
            }
        }
    }
}
