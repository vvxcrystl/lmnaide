package dev.lmnaide.calendar.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lmnaide.calendar.domain.Occurrence
import java.time.LocalDate
import java.time.LocalDateTime

private fun Occurrence.isPast(now: LocalDateTime): Boolean =
    if (allDay) lastDate < now.toLocalDate() else end <= now

/** Background and text colors for an event chip. */
@Composable
private fun chipColors(occurrence: Occurrence, now: LocalDateTime): Pair<Color, Color> {
    val surface = MaterialTheme.colorScheme.surface
    val base = Color(occurrence.color)
    val past = occurrence.isPast(now)
    val background = base.faded(surface, past)
    val content = when {
        !past -> background.contentColor()
        // Faded chips are pastel in light mode and muted in dark mode; keep their text quiet but legible.
        background.luminance() > 0.3f -> Color(0xFF1F1F1F).copy(alpha = 0.75f)
        else -> Color.White.copy(alpha = 0.75f)
    }
    return background to content
}

/** An event placed in the hourly grid. */
@Composable
fun GridEventBlock(
    occurrence: Occurrence,
    timeText: String,
    height: Dp,
    now: LocalDateTime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val (background, content) = chipColors(occurrence, now)
    val titleSize = if (compact) 11 else 13
    val lineHeight = titleSize + 3
    Column(
        modifier
            .clip(RoundedCornerShape(if (compact) 6.dp else 8.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = 3.dp),
    ) {
        val showTime = height >= if (compact) 56.dp else 40.dp
        val titleLines = ((height.value - 6 - if (showTime) lineHeight else 0) / lineHeight).toInt().coerceIn(1, 4)
        Text(
            occurrence.event.title.ifBlank { "(No title)" },
            fontSize = titleSize.sp,
            lineHeight = lineHeight.sp,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = titleLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (showTime) {
            Text(
                timeText,
                fontSize = (titleSize - 1).sp,
                lineHeight = lineHeight.sp,
                color = content.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A one-line chip for all-day lanes and the month grid. */
@Composable
fun EventBar(
    occurrence: Occurrence,
    now: LocalDateTime,
    modifier: Modifier = Modifier,
    fontSize: Int = 12,
    onClick: (() -> Unit)? = null,
) {
    val (background, content) = chipColors(occurrence, now)
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            occurrence.event.title.ifBlank { "(No title)" },
            fontSize = fontSize.sp,
            lineHeight = (fontSize + 2).sp,
            fontWeight = FontWeight.Medium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** A full-width event card used in the schedule and search lists. */
@Composable
fun EventCard(
    occurrence: Occurrence,
    time: String,
    detail: String?,
    now: LocalDateTime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (background, content) = chipColors(occurrence, now)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            occurrence.event.title.ifBlank { "(No title)" },
            style = MaterialTheme.typography.titleSmall,
            color = content,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(time, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.85f), maxLines = 1)
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Day-of-month number, circled when it's today. */
@Composable
fun DayNumber(
    date: LocalDate,
    today: LocalDate,
    size: Dp,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    selected: Boolean = false,
    fontSize: Int = 14,
) {
    val colors = MaterialTheme.colorScheme
    val isToday = date == today
    val background = when {
        isToday -> colors.primary
        selected -> colors.primaryContainer
        else -> Color.Transparent
    }
    val content = when {
        isToday -> colors.onPrimary
        selected -> colors.onPrimaryContainer
        dimmed -> colors.onSurfaceVariant.copy(alpha = 0.55f)
        else -> colors.onSurface
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            date.dayOfMonth.toString(),
            fontSize = fontSize.sp,
            fontWeight = if (isToday || selected) FontWeight.Medium else FontWeight.Normal,
            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
            color = content,
        )
    }
}

/** Small colored dot used for calendars and event colors. */
@Composable
fun ColorDot(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}
