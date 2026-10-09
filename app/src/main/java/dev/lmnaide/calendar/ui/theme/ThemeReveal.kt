package dev.lmnaide.calendar.ui.theme

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import kotlin.math.hypot

/**
 * Pictures of the app from just before each theme change. [ThemeRevealOverlay] draws them over
 * the new theme, each wiped away by a soft-edged circle spreading from where the user tapped.
 * Rapid changes each get their own circle, and all of them keep spreading together.
 */
class ThemeReveal {
    internal class Layer(val id: Int, val snapshot: ImageBitmap, val origin: Offset) {
        val progress = Animatable(0f)
    }

    internal val layers = mutableStateListOf<Layer>()
    private var nextId = 0

    /** Call right before changing the theme. [view] is the app's own view; [origin] is in window coordinates. */
    fun start(view: View, origin: Offset) {
        val bitmap = runCatching { view.drawToBitmap() }.getOrNull() ?: return
        layers += Layer(nextId++, bitmap.asImageBitmap(), origin)
        // Each layer is a full-screen bitmap, so cap how many can stack.
        while (layers.size > MAX_LAYERS) layers.removeAt(0)
    }

    private companion object {
        const val MAX_LAYERS = 6
    }
}

val LocalThemeReveal = staticCompositionLocalOf<ThemeReveal?> { null }

private val Splash = CubicBezierEasing(0.15f, 0f, 0.1f, 1f)

@Composable
fun ThemeRevealOverlay(reveal: ThemeReveal) {
    val layers = reveal.layers
    layers.forEach { layer ->
        key(layer.id) {
            LaunchedEffect(Unit) {
                layer.progress.animateTo(1f, tween(550, easing = Splash))
                // Once this circle fills the screen, it also hides every older picture.
                val index = layers.indexOf(layer)
                if (index >= 0) repeat(index + 1) { layers.removeAt(0) }
            }
        }
    }
    if (layers.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val feather = 72.dp.toPx()
        fun hole(layer: ThemeReveal.Layer): Brush {
            val origin = layer.origin
            val farthest = maxOf(
                hypot(origin.x, origin.y),
                hypot(size.width - origin.x, origin.y),
                hypot(origin.x, size.height - origin.y),
                hypot(size.width - origin.x, size.height - origin.y),
            )
            val radius = (farthest + feather) * layer.progress.value
            val outer = radius + feather
            // Solid inside the circle, then a blurred rim fading out over [feather].
            return Brush.radialGradient(0f to Color.Black, radius / outer to Color.Black, 1f to Color.Transparent, center = origin, radius = outer)
        }
        val holes = layers.map(::hole)
        // Picture i shows the theme before change i, so it is cut by its own circle and every later one.
        // Older pictures go on top: they are visible only outside more circles.
        for (i in layers.indices.reversed()) {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                drawImage(layers[i].snapshot, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                for (j in i until layers.size) drawRect(holes[j], blendMode = BlendMode.DstOut)
                canvas.restore()
            }
        }
    }
}
