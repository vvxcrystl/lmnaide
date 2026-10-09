package dev.lmnaide.calendar.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lmnaide.calendar.data.Importance
import dev.lmnaide.calendar.domain.Occurrence
import java.time.LocalDate
import java.time.LocalDateTime

/** Done tasks and finished events are drawn faded; open tasks stay prominent even when overdue. */
private fun Occurrence.isPast(now: LocalDateTime): Boolean = when {
    isTask -> completed
    allDay -> lastDate < now.toLocalDate()
    else -> end <= now
}

private fun Occurrence.titleText() = event.title.ifBlank { "(No title)" }

private fun Occurrence.titleDecoration() = if (completed) TextDecoration.LineThrough else null

/** The check circle shown in front of task titles. */
@Composable
fun TaskCheck(completed: Boolean, tint: Color, size: Dp) {
    Icon(
        if (completed) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
        contentDescription = if (completed) "Completed task" else "Task",
        tint = tint,
        modifier = Modifier.size(size),
    )
}

/** A solid colored dot marking importance. */
@Composable
fun ImportanceDot(importance: Importance, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    ColorDot(
        Color(importance.argb),
        modifier.semantics { contentDescription = "${importance.label} importance" },
        size,
    )
}

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
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (occurrence.isTask) {
                TaskCheck(occurrence.completed, content, size = (titleSize + 1).dp)
            }
            Text(
                occurrence.titleText(),
                fontSize = titleSize.sp,
                lineHeight = lineHeight.sp,
                fontWeight = FontWeight.Medium,
                color = content,
                textDecoration = occurrence.titleDecoration(),
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            occurrence.event.importance?.let { ImportanceDot(it, Modifier.padding(top = 3.dp)) }
        }
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            occurrence.event.importance?.let { ImportanceDot(it, size = 7.dp) }
            if (occurrence.isTask) TaskCheck(occurrence.completed, content, size = fontSize.dp)
            Text(
                occurrence.titleText(),
                fontSize = fontSize.sp,
                lineHeight = (fontSize + 2).sp,
                fontWeight = FontWeight.Medium,
                color = content,
                textDecoration = occurrence.titleDecoration(),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
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
    onToggleTask: (() -> Unit)? = null,
) {
    val (background, content) = chipColors(occurrence, now)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = if (occurrence.isTask) 4.dp else 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
    ) {
        if (occurrence.isTask) {
            IconButton(onClick = { onToggleTask?.invoke() }, enabled = onToggleTask != null, modifier = Modifier.size(40.dp)) {
                TaskCheck(occurrence.completed, content, size = 22.dp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                occurrence.titleText(),
                style = MaterialTheme.typography.titleSmall,
                color = content,
                textDecoration = occurrence.titleDecoration(),
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
        occurrence.event.importance?.let { ImportanceDot(it, Modifier.padding(start = 8.dp), size = 12.dp) }
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
