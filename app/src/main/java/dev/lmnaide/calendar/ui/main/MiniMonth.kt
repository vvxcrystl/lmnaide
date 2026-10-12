package dev.lmnaide.calendar.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.lmnaide.calendar.domain.EventIndex
import dev.lmnaide.calendar.ui.common.DayNumber
import dev.lmnaide.calendar.ui.common.Fmt
import dev.lmnaide.calendar.ui.common.Motion
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Compact month picker shown under the top bar; dots mark days with events. */
@Composable
fun MiniMonth(
    selectedDate: LocalDate,
    today: LocalDate,
    weekStart: DayOfWeek,
    index: EventIndex,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var month by remember(selectedDate) { mutableStateOf(YearMonth.from(selectedDate)) }

    Column(modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Fmt.monthYearFull(month),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            IconButton(onClick = { month = month.minusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            IconButton(onClick = { month = month.plusMonths(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }
        WeekdayHeader(weekStart)
        // The days slide in from the side of the arrow that was tapped. The old month fades out
        // before the new one fades in, so the two grids of numbers never overlap.
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(Motion.enter()) { if (forward) it / 5 else -it / 5 } + fadeIn(Motion.enter(Motion.MEDIUM - 90, delay = 90)))
                    .togetherWith(slideOutHorizontally(Motion.exit()) { if (forward) -it / 5 else it / 5 } + fadeOut(Motion.exit(90)))
            },
            label = "mini month",
        ) { shown ->
            MonthGrid(shown, selectedDate, today, weekStart, index, onSelect)
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    weekStart: DayOfWeek,
    index: EventIndex,
    onSelect: (LocalDate) -> Unit,
) {
    val firstCell = month.atDay(1).with(TemporalAdjusters.previousOrSame(weekStart))
    val lastCell = firstCell.plusWeeks(6).minusDays(1)
    val eventDays by produceState(emptySet<LocalDate>(), index, firstCell, lastCell) {
        value = withContext(Dispatchers.Default) { index.byDay(firstCell, lastCell).keys }
    }
    Column {
        for (week in 0L until 6L) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0L until 7L) {
                    val date = firstCell.plusWeeks(week).plusDays(d)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(CircleShape)
                            .clickable { onSelect(date) },
                        contentAlignment = Alignment.Center,
                    ) {
                        DayNumber(
                            date,
                            today,
                            size = 32.dp,
                            fontSize = 13,
                            dimmed = YearMonth.from(date) != month,
                            selected = date == selectedDate,
                        )
                        if (date in eventDays) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 1.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }
    }
}
