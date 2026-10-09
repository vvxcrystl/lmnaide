package dev.lmnaide.calendar.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lmnaide.calendar.ui.common.Fmt
import java.time.Month
import java.time.YearMonth
import kotlin.random.Random

private val Ink = Color(0xFF1F1F1F)

/** A month heading drawn as a small seasonal landscape, in the spirit of Google Calendar's month art. */
@Composable
fun MonthBanner(month: YearMonth, modifier: Modifier = Modifier) {
    val scene = remember(month) { sceneFor(month) }
    Box(
        modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(24.dp))
            .drawBehind { scene(this) },
    ) {
        Text(
            Fmt.monthYearFull(month),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            color = Ink,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp),
        )
    }
}

private fun sceneFor(month: YearMonth): DrawScope.() -> Unit {
    // Seeded per month so the art is stable but each month looks a little different.
    val seed = month.year * 12 + month.monthValue
    return when (month.month) {
        Month.DECEMBER, Month.JANUARY, Month.FEBRUARY -> { { winter(Random(seed)) } }
        Month.MARCH, Month.APRIL, Month.MAY -> { { spring(Random(seed)) } }
        Month.JUNE, Month.JULY, Month.AUGUST -> { { summer(Random(seed)) } }
        else -> { { autumn(Random(seed)) } }
    }
}

private fun DrawScope.hill(x: Float, y: Float, radius: Float, color: Color) =
    drawCircle(color, radius, Offset(size.width * x, size.height * y))

private fun DrawScope.winter(random: Random) {
    val w = size.width
    val h = size.height
    drawRect(Color(0xFFD7E7F7))
    hill(0.3f + random.nextFloat() * 0.1f, 1.5f, h * 0.85f, Color(0xFFEAF2FB))
    hill(0.82f, 1.55f, h * 0.9f, Color.White)
    // Pines on the near hill.
    listOf(0.7f to 0.42f, 0.78f to 0.3f, 0.88f to 0.38f).forEach { (x, top) ->
        val cx = w * (x + (random.nextFloat() - 0.5f) * 0.03f)
        val base = h * 0.82f
        val tree = Path().apply {
            moveTo(cx, h * top)
            lineTo(cx + h * 0.13f, base)
            lineTo(cx - h * 0.13f, base)
            close()
        }
        drawPath(tree, Color(0xFF2F6B57))
    }
    repeat(22) {
        drawCircle(
            Color.White.copy(alpha = 0.6f + random.nextFloat() * 0.4f),
            radius = 1.5.dp.toPx() + random.nextFloat() * 2.dp.toPx(),
            center = Offset(random.nextFloat() * w, random.nextFloat() * h * 0.9f),
        )
    }
}

private fun DrawScope.spring(random: Random) {
    val w = size.width
    val h = size.height
    drawRect(Color(0xFFE3F4E8))
    drawCircle(Color(0xFFFFE08A), h * 0.17f, Offset(w * (0.8f + random.nextFloat() * 0.08f), h * 0.3f))
    hill(0.25f, 1.55f, h * 0.9f, Color(0xFFA9DDB2))
    hill(0.78f, 1.65f, h * 0.95f, Color(0xFF7CC795))
    val petals = listOf(Color(0xFFF49CBB), Color(0xFFFFFFFF), Color(0xFFFFCF5C))
    repeat(14) {
        val center = Offset(w * (0.45f + random.nextFloat() * 0.55f), h * (0.78f + random.nextFloat() * 0.18f))
        val r = 2.5.dp.toPx() + random.nextFloat() * 1.5.dp.toPx()
        drawCircle(petals[it % petals.size], r, center)
        drawCircle(Color(0xFFF2A541), r * 0.4f, center)
    }
}

private fun DrawScope.summer(random: Random) {
    val w = size.width
    val h = size.height
    drawRect(Color(0xFFFFEFC9))
    val sun = Offset(w * (0.78f + random.nextFloat() * 0.1f), h * 0.36f)
    drawCircle(Color(0xFFFFDDA0), h * 0.3f, sun)
    drawCircle(Color(0xFFFFB74D), h * 0.2f, sun)
    drawRect(Color(0xFF4FB3E8), topLeft = Offset(0f, h * 0.68f), size = Size(w, h * 0.32f))
    repeat(6) {
        val x = w * (0.35f + random.nextFloat() * 0.6f)
        val y = h * (0.76f + random.nextFloat() * 0.18f)
        val wave = Path().apply {
            moveTo(x, y)
            quadraticTo(x + 6.dp.toPx(), y - 4.dp.toPx(), x + 12.dp.toPx(), y)
            quadraticTo(x + 18.dp.toPx(), y + 4.dp.toPx(), x + 24.dp.toPx(), y)
        }
        drawPath(wave, Color.White.copy(alpha = 0.8f), style = Stroke(width = 2.dp.toPx()))
    }
    hill(0.12f, 1.3f, h * 0.6f, Color(0xFFF3D193))
}

private fun DrawScope.autumn(random: Random) {
    val w = size.width
    val h = size.height
    drawRect(Color(0xFFFDE6D2))
    drawCircle(Color(0xFFF9C87A), h * 0.15f, Offset(w * (0.62f + random.nextFloat() * 0.08f), h * 0.32f))
    hill(0.3f, 1.6f, h * 0.95f, Color(0xFFEDB083))
    hill(0.88f, 1.5f, h * 0.8f, Color(0xFFCF7B4E))
    // A single tree on the right hill.
    val trunkX = w * 0.86f
    drawRect(Color(0xFF6D4030), Offset(trunkX - 2.dp.toPx(), h * 0.42f), Size(4.dp.toPx(), h * 0.3f))
    drawCircle(Color(0xFFD9603B), h * 0.2f, Offset(trunkX, h * 0.36f))
    drawCircle(Color(0xFFE98A4C), h * 0.12f, Offset(trunkX - h * 0.12f, h * 0.46f))
    val leaves = listOf(Color(0xFFD9603B), Color(0xFFF2A541), Color(0xFFB5462B))
    repeat(12) {
        // Kept to the right of the month name.
        val center = Offset(w * (0.55f + random.nextFloat() * 0.25f), h * (0.12f + random.nextFloat() * 0.6f))
        rotate(random.nextFloat() * 180f, center) {
            drawOval(
                leaves[it % leaves.size],
                topLeft = Offset(center.x - 4.dp.toPx(), center.y - 2.dp.toPx()),
                size = Size(8.dp.toPx(), 4.dp.toPx()),
            )
        }
    }
}
