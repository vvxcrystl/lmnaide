package dev.lmnaide.calendar.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.lmnaide.calendar.data.Importance
import dev.lmnaide.calendar.ui.common.ColorDot
import dev.lmnaide.calendar.ui.common.contentColor
import kotlin.math.abs

private val Options = listOf<Importance?>(null) + Importance.entries
private val ItemWidth = 112.dp
private val EdgeFade = 40.dp

/**
 * None, Low, Medium and High in a row you swipe through. The option in the middle is the one
 * picked, and tapping a neighbor picks it and slides it to the middle.
 */
@Composable
internal fun ImportanceCarousel(selected: Importance?, onSelect: (Importance?) -> Unit) {
    val pager = rememberPagerState(initialPage = Options.indexOf(selected)) { Options.size }
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // Settling on an option picks it.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (Options[page] != currentSelected) currentOnSelect(Options[page])
        }
    }
    // A change from outside, like switching calendars, slides the new option into the middle.
    LaunchedEffect(selected) {
        val target = Options.indexOf(selected)
        if (target != pager.currentPage && !pager.isScrollInProgress) pager.animateScrollToPage(target)
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val side = ((maxWidth - ItemWidth) / 2).coerceAtLeast(0.dp)
        HorizontalPager(
            state = pager,
            pageSize = PageSize.Fixed(ItemWidth),
            contentPadding = PaddingValues(horizontal = side),
            pageSpacing = 8.dp,
            flingBehavior = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(Options.size)),
            // All four stay composed, so TalkBack can reach the ones scrolled out of view.
            beyondViewportPageCount = Options.size,
            modifier = Modifier
                .fillMaxWidth()
                // Options fade out toward the edges instead of being cut off.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (size.width <= 0f) return@drawWithContent
                    val fade = (EdgeFade.toPx() / size.width).coerceIn(0f, 0.5f)
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, fade to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) { page ->
            val option = Options[page]
            // 1 in the middle, fading to 0 one place away.
            val focus = 1f - abs(pager.getOffsetDistanceInPages(page)).coerceIn(0f, 1f)
            val idle = MaterialTheme.colorScheme.surfaceContainerHigh
            val active = option?.let { Color(it.argb) } ?: MaterialTheme.colorScheme.secondaryContainer
            val activeText = option?.let { Color(it.argb).contentColor() } ?: MaterialTheme.colorScheme.onSecondaryContainer
            // A 48dp tall target around the 40dp pill. Tapping picks the option right away, and the
            // effect above slides it to the middle; the slide itself is the feedback.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(48.dp)
                    .selectable(
                        selected = option == selected,
                        interactionSource = null,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = { currentOnSelect(option) },
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .graphicsLayer {
                            val scale = 0.88f + 0.12f * focus
                            scaleX = scale
                            scaleY = scale
                        }
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(lerp(idle, active, focus))
                        .padding(horizontal = 12.dp),
                ) {
                    if (option != null) ColorDot(Color(option.argb), size = 10.dp, modifier = Modifier.padding(end = 8.dp))
                    Text(
                        option?.label ?: "None",
                        style = MaterialTheme.typography.labelLarge,
                        color = lerp(MaterialTheme.colorScheme.onSurfaceVariant, activeText, focus),
                    )
                }
            }
        }
    }
}
