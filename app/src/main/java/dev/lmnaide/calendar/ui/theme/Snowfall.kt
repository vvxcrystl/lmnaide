package dev.lmnaide.calendar.ui.theme

import android.animation.ValueAnimator
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.sin
import kotlin.random.Random

private class Flake(random: Random) {
    val x = random.nextFloat()
    val y = random.nextFloat()
    val radius = 1.5f + random.nextFloat() * 2.5f
    /** Screen heights per second; bigger flakes are nearer, so they fall faster. */
    val speed = 0.03f + radius * 0.012f
    val phase = random.nextFloat() * 6.28f
    val alpha = 0.35f + random.nextFloat() * 0.45f
}

/**
 * Light snow drifting over the app for the Christmas theme. It never takes touches, and holds still
 * when the system has animations turned off.
 */
@Composable
fun Snowfall(modifier: Modifier) {
    val flakes = remember { Random(25).let { random -> List(36) { Flake(random) } } }
    val color = if (LocalDarkTheme.current) Color.White else Color(0xFFB8C6D4)
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        if (!ValueAnimator.areAnimatorsEnabled()) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) withFrameNanos { seconds = (it - start) / 1_000_000_000f }
    }
    Canvas(modifier) {
        val scale = density
        flakes.forEach { flake ->
            val r = flake.radius * scale
            // Wraps with a margin so flakes slide in from above instead of popping in.
            val y = ((flake.y + seconds * flake.speed) % 1f) * (size.height + 4 * r) - 2 * r
            val x = flake.x * size.width + sin(seconds * 0.8f + flake.phase) * 12f * scale
            drawCircle(color.copy(alpha = flake.alpha), r, Offset(x, y))
        }
    }
}
