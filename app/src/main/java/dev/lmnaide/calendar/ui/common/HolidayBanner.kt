package dev.lmnaide.calendar.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import dev.lmnaide.calendar.domain.HolidayArt
import kotlin.math.cos
import kotlin.math.sin

/** Flat illustration for a holiday, drawn as vectors so it scales to any banner size. */
@Composable
fun HolidayBanner(art: HolidayArt, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize().clipToBounds()) {
        when (art) {
            HolidayArt.CHRISTMAS -> christmas()
            HolidayArt.FIREWORKS -> fireworks()
            HolidayArt.HEARTS -> hearts()
            HolidayArt.EGGS -> eggs()
            HolidayArt.HALLOWEEN -> pumpkins(sky = Color(0xFF2B1B4A), ground = Color(0xFF1B1030), moon = true)
            HolidayArt.HARVEST -> pumpkins(sky = Color(0xFFF3C26B), ground = Color(0xFF8A4B20), moon = false)
            HolidayArt.MAPLE -> maple()
            HolidayArt.SUNRISE -> sunrise()
            HolidayArt.POPPY -> poppy()
            HolidayArt.SHAMROCK -> shamrock()
            HolidayArt.LANDSCAPE -> landscape()
        }
    }
}

private fun DrawScope.at(x: Float, y: Float) = Offset(size.width * x, size.height * y)

private fun DrawScope.circle(color: Color, x: Float, y: Float, radius: Float) =
    drawCircle(color, radius = size.height * radius, center = at(x, y))

private fun DrawScope.hill(color: Color, x: Float, y: Float, width: Float, height: Float) =
    drawOval(color, topLeft = at(x - width / 2, y), size = Size(size.width * width, size.height * height))

private fun DrawScope.triangle(color: Color, vararg points: Pair<Float, Float>) {
    val path = Path().apply {
        points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(size.width * x, size.height * y) else lineTo(size.width * x, size.height * y) }
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.sparkle(color: Color, x: Float, y: Float, radius: Float) {
    val c = at(x, y)
    val r = size.height * radius
    val path = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x, c.y, c.x + r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r)
    }
    drawPath(path, color)
}

private fun heartPath(cx: Float, cy: Float, s: Float) = Path().apply {
    moveTo(cx, cy + s * 0.9f)
    cubicTo(cx - s * 1.4f, cy + s * 0.1f, cx - s * 0.9f, cy - s * 0.9f, cx, cy - s * 0.3f)
    cubicTo(cx + s * 0.9f, cy - s * 0.9f, cx + s * 1.4f, cy + s * 0.1f, cx, cy + s * 0.9f)
    close()
}

private fun DrawScope.heart(color: Color, x: Float, y: Float, radius: Float) =
    drawPath(heartPath(size.width * x, size.height * y, size.height * radius), color)

private fun DrawScope.christmas() {
    drawRect(Color(0xFF0B3318))
    listOf(0.06f to 0.2f, 0.52f to 0.14f, 0.9f to 0.3f, 0.7f to 0.1f).forEach { (x, y) -> sparkle(Color(0xFFF6C85F), x, y, 0.09f) }
    circle(Color(0xFFFFF3D6), 0.56f, 0.18f, 0.09f)
    // Houses
    val roof = Color(0xFFFFFFFF)
    drawRect(Color(0xFFD9411E), at(0.1f, 0.5f), Size(size.width * 0.26f, size.height * 0.5f))
    triangle(roof, 0.07f to 0.52f, 0.23f to 0.2f, 0.39f to 0.52f)
    drawRect(Color(0xFFF05A28), at(0.1f, 0.62f), Size(size.width * 0.07f, size.height * 0.2f))
    drawRect(Color(0xFFF05A28), at(0.42f, 0.55f), Size(size.width * 0.34f, size.height * 0.45f))
    triangle(roof, 0.39f to 0.57f, 0.59f to 0.22f, 0.79f to 0.57f)
    drawRect(Color(0xFFF2CC6E), at(0.5f, 0.64f), Size(size.width * 0.18f, size.height * 0.2f))
    triangle(Color(0xFF3A1E0E), 0.57f to 0.8f, 0.59f to 0.66f, 0.61f to 0.8f)
    // Tree
    val pine = Color(0xFF2E9E5B)
    triangle(pine, 0.0f to 0.6f, 0.05f to 0.2f, 0.12f to 0.6f)
    triangle(pine, -0.01f to 0.85f, 0.05f to 0.45f, 0.14f to 0.85f)
    circle(Color(0xFFFFB6C1), 0.03f, 0.55f, 0.025f)
    circle(Color(0xFFF6C85F), 0.08f, 0.72f, 0.025f)
    // Snowman
    circle(Color.White, 0.865f, 0.82f, 0.13f)
    circle(Color.White, 0.865f, 0.6f, 0.095f)
    circle(Color(0xFF222222), 0.845f, 0.58f, 0.012f)
    circle(Color(0xFF222222), 0.885f, 0.58f, 0.012f)
    triangle(Color(0xFFF2762E), 0.865f to 0.61f, 0.865f to 0.65f, 0.93f to 0.635f)
    drawRect(Color(0xFFE53935), at(0.82f, 0.68f), Size(size.width * 0.09f, size.height * 0.05f))
    hill(Color.White, 0.5f, 0.92f, 1.3f, 0.3f)
}

private fun DrawScope.burst(color: Color, x: Float, y: Float, radius: Float) {
    val c = at(x, y)
    val r = size.height * radius
    repeat(14) { i ->
        val a = Math.toRadians(i * 360.0 / 14).toFloat()
        val from = Offset(c.x + cos(a) * r * 0.35f, c.y + sin(a) * r * 0.35f)
        val to = Offset(c.x + cos(a) * r * 0.8f, c.y + sin(a) * r * 0.8f)
        drawLine(color, from, to, strokeWidth = size.height * 0.022f, cap = StrokeCap.Round)
        drawCircle(color, radius = size.height * 0.02f, center = Offset(c.x + cos(a) * r, c.y + sin(a) * r))
    }
}

private fun DrawScope.fireworks() {
    drawRect(Color(0xFF14213D))
    burst(Color(0xFFFCA311), 0.25f, 0.42f, 0.34f)
    burst(Color(0xFFE5383B), 0.62f, 0.55f, 0.4f)
    burst(Color(0xFF8ECAE6), 0.88f, 0.28f, 0.24f)
    listOf(0.06f to 0.12f, 0.45f to 0.12f, 0.78f to 0.8f, 0.12f to 0.85f, 0.97f to 0.7f).forEach { (x, y) -> sparkle(Color.White, x, y, 0.07f) }
}

private fun DrawScope.hearts() {
    drawRect(Color(0xFFF8C9D4))
    heart(Color(0xFFE8567A), 0.5f, 0.52f, 0.34f)
    heart(Color(0xFFF48FB1), 0.2f, 0.4f, 0.2f)
    heart(Color(0xFFFFFFFF), 0.8f, 0.4f, 0.2f)
    heart(Color(0xFFFFFFFF), 0.34f, 0.85f, 0.1f)
    heart(Color(0xFFE8567A), 0.07f, 0.8f, 0.1f)
    heart(Color(0xFFF48FB1), 0.68f, 0.88f, 0.09f)
    heart(Color(0xFFE8567A), 0.94f, 0.82f, 0.1f)
    heart(Color(0xFFF48FB1), 0.09f, 0.12f, 0.08f)
    heart(Color(0xFFE8567A), 0.9f, 0.1f, 0.07f)
}

private fun DrawScope.egg(base: Color, stripe: Color, x: Float, y: Float, height: Float) {
    val w = size.height * height * 0.78f
    val h = size.height * height
    val c = at(x, y)
    val shape = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)) }
    drawPath(shape, base)
    clipPath(shape) {
        drawRect(stripe, Offset(c.x - w, c.y - h * 0.05f), Size(w * 2, h * 0.16f))
        drawRect(stripe, Offset(c.x - w, c.y + h * 0.25f), Size(w * 2, h * 0.08f))
        drawCircle(Color.White.copy(alpha = 0.7f), radius = w * 0.07f, center = Offset(c.x - w * 0.1f, c.y - h * 0.25f))
        drawCircle(Color.White.copy(alpha = 0.7f), radius = w * 0.07f, center = Offset(c.x + w * 0.15f, c.y - h * 0.3f))
    }
}

private fun DrawScope.eggs() {
    drawRect(Color(0xFFCDEBD8))
    hill(Color(0xFF9AD6A0), 0.3f, 0.78f, 1.0f, 0.6f)
    hill(Color(0xFF7BC47F), 0.78f, 0.82f, 0.9f, 0.6f)
    egg(Color(0xFFF48FB1), Color(0xFFFFFFFF), 0.28f, 0.58f, 0.62f)
    egg(Color(0xFFFFD54F), Color(0xFF4FC3F7), 0.5f, 0.66f, 0.56f)
    egg(Color(0xFF9575CD), Color(0xFFFFF176), 0.72f, 0.56f, 0.64f)
    egg(Color(0xFF4DB6AC), Color(0xFFFFFFFF), 0.9f, 0.7f, 0.46f)
    sparkle(Color.White, 0.08f, 0.2f, 0.1f)
    sparkle(Color.White, 0.55f, 0.15f, 0.08f)
}

private fun DrawScope.pumpkin(body: Color, rib: Color, x: Float, y: Float, radius: Float) {
    val r = size.height * radius
    val c = at(x, y)
    drawOval(rib, Offset(c.x - r * 1.25f, c.y - r), Size(r * 2.5f, r * 2f))
    drawOval(body, Offset(c.x - r * 0.85f, c.y - r), Size(r * 1.7f, r * 2f))
    drawOval(rib, Offset(c.x - r * 0.35f, c.y - r), Size(r * 0.7f, r * 2f))
    drawOval(body, Offset(c.x - r * 0.2f, c.y - r), Size(r * 0.4f, r * 2f))
    drawRect(Color(0xFF5D7A2E), Offset(c.x - r * 0.1f, c.y - r * 1.35f), Size(r * 0.2f, r * 0.45f))
}

private fun DrawScope.pumpkins(sky: Color, ground: Color, moon: Boolean) {
    drawRect(sky)
    if (moon) {
        circle(Color(0xFFFFE9A8), 0.82f, 0.28f, 0.2f)
        circle(Color(0xFFF0D480), 0.86f, 0.22f, 0.04f)
        listOf(0.1f to 0.2f, 0.4f to 0.14f, 0.6f to 0.3f).forEach { (x, y) -> sparkle(Color(0xFFFFE9A8), x, y, 0.08f) }
    } else {
        // Falling leaves
        listOf(Triple(0.12f, 0.2f, Color(0xFFD84315)), Triple(0.45f, 0.14f, Color(0xFFEF6C00)), Triple(0.78f, 0.24f, Color(0xFFBF360C)), Triple(0.6f, 0.38f, Color(0xFFF9A825))).forEach { (x, y, color) ->
            rotate(35f, at(x, y)) { drawOval(color, at(x - 0.02f, y - 0.07f), Size(size.width * 0.04f, size.height * 0.28f)) }
        }
    }
    hill(ground, 0.5f, 0.78f, 1.4f, 0.6f)
    pumpkin(Color(0xFFF28C28), Color(0xFFD9701A), 0.3f, 0.7f, 0.22f)
    pumpkin(Color(0xFFF59A3B), Color(0xFFD9701A), 0.62f, 0.76f, 0.28f)
    pumpkin(Color(0xFFF28C28), Color(0xFFD9701A), 0.86f, 0.82f, 0.16f)
}

/** The Canada flag leaf as 11 points of a unit square, mirrored about the vertical axis. */
private val mapleHalf = listOf(
    0.50f to 0.00f, 0.585f to 0.17f, 0.67f to 0.105f, 0.645f to 0.33f, 0.80f to 0.23f, 0.775f to 0.40f,
    0.97f to 0.44f, 0.82f to 0.60f, 0.88f to 0.68f, 0.56f to 0.665f, 0.54f to 1.00f,
)

private fun DrawScope.mapleLeaf(color: Color, cx: Float, cy: Float, extent: Float) {
    val s = size.height * extent
    val left = cx - s / 2
    val top = cy - s / 2
    val path = Path().apply {
        mapleHalf.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(left + x * s, top + y * s) else lineTo(left + x * s, top + y * s) }
        mapleHalf.asReversed().drop(1).forEach { (x, y) -> lineTo(left + (1 - x) * s, top + y * s) }
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.maple() {
    val red = Color(0xFFD52B1E)
    drawRect(red)
    drawRect(Color.White, at(0.3f, 0f), Size(size.width * 0.4f, size.height))
    mapleLeaf(red, size.width * 0.5f, size.height * 0.5f, 0.78f)
    mapleLeaf(Color.White.copy(alpha = 0.9f), size.width * 0.12f, size.height * 0.28f, 0.22f)
    mapleLeaf(Color.White.copy(alpha = 0.9f), size.width * 0.9f, size.height * 0.7f, 0.28f)
}

private fun DrawScope.sunrise() {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFF08A3C), Color(0xFFFBD38D)), startY = 0f, endY = size.height))
    val c = at(0.5f, 0.68f)
    repeat(13) { i ->
        val a = Math.toRadians(180.0 + i * 180.0 / 12).toFloat()
        val r1 = size.height * 0.5f
        val r2 = size.height * 0.75f
        drawLine(Color.White.copy(alpha = 0.55f), Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), Offset(c.x + cos(a) * r2, c.y + sin(a) * r2), strokeWidth = size.height * 0.03f, cap = StrokeCap.Round)
    }
    circle(Color(0xFFFFF3B0), 0.5f, 0.68f, 0.38f)
    hill(Color(0xFF2F6F6A), 0.25f, 0.72f, 0.9f, 0.7f)
    hill(Color(0xFF479A8F), 0.78f, 0.78f, 0.95f, 0.7f)
}

private fun DrawScope.poppy() {
    drawRect(Color(0xFF1F2026))
    drawLine(Color(0xFF4C8C4A), at(0.5f, 0.55f), at(0.5f, 1.05f), strokeWidth = size.height * 0.05f, cap = StrokeCap.Round)
    val centre = at(0.5f, 0.42f)
    val r = size.height * 0.2f
    listOf(0f, 72f, 144f, 216f, 288f).forEach { deg ->
        val a = Math.toRadians(deg - 90.0).toFloat()
        drawCircle(Color(0xFFD32F2F), r * 0.95f, Offset(centre.x + cos(a) * r * 0.75f, centre.y + sin(a) * r * 0.75f))
    }
    drawCircle(Color(0xFFB71C1C), r * 0.62f, centre)
    drawCircle(Color(0xFF111111), r * 0.34f, centre)
    listOf(0.08f to 0.7f, 0.92f to 0.3f).forEach { (x, y) ->
        drawCircle(Color(0xFFD32F2F), size.height * 0.07f, at(x, y))
        drawCircle(Color(0xFF111111), size.height * 0.025f, at(x, y))
    }
}

private fun DrawScope.shamrock() {
    drawRect(Color(0xFF1F7A3D))
    fun clover(x: Float, y: Float, radius: Float, color: Color) {
        val c = at(x, y)
        val s = size.height * radius
        listOf(0f, 120f, 240f).forEach { deg ->
            rotate(deg, c) { drawPath(heartPath(c.x, c.y - s * 0.75f, s * 0.6f), color) }
        }
        drawLine(color, c, Offset(c.x + s * 0.25f, c.y + s * 1.4f), strokeWidth = s * 0.14f, cap = StrokeCap.Round)
    }
    clover(0.5f, 0.45f, 0.45f, Color(0xFF8EE08F))
    clover(0.18f, 0.55f, 0.28f, Color(0xFF55B85A))
    clover(0.84f, 0.5f, 0.3f, Color(0xFF55B85A))
    listOf(0.06f to 0.15f, 0.36f to 0.12f, 0.68f to 0.2f, 0.95f to 0.14f).forEach { (x, y) -> sparkle(Color(0xFFFFE082), x, y, 0.08f) }
}

private fun DrawScope.landscape() {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF9ED4F0), Color(0xFFDDF1FA))))
    circle(Color(0xFFFFD54F), 0.8f, 0.3f, 0.17f)
    circle(Color.White, 0.2f, 0.28f, 0.1f)
    circle(Color.White, 0.27f, 0.22f, 0.13f)
    circle(Color.White, 0.35f, 0.3f, 0.1f)
    hill(Color(0xFF8BC98A), 0.3f, 0.6f, 1.1f, 0.9f)
    hill(Color(0xFF5BA56B), 0.82f, 0.7f, 1.0f, 0.9f)
}
