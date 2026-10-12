package dev.lmnaide.calendar.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Material 3 motion tokens. Every animation in the app answers something the person did, so these
 * stay short: long enough to show where things went, short enough never to wait on.
 */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 400

    /** For things arriving on screen. */
    fun <T> enter(duration: Int = MEDIUM, delay: Int = 0): FiniteAnimationSpec<T> =
        tween(duration, delay, EmphasizedDecelerate)

    /** For things leaving the screen, which should get out of the way faster than they came. */
    fun <T> exit(duration: Int = SHORT): FiniteAnimationSpec<T> = tween(duration, easing = EmphasizedAccelerate)

    /** For things moving or resizing in place. */
    fun <T> move(duration: Int = MEDIUM): FiniteAnimationSpec<T> = tween(duration, easing = Emphasized)
}

private class Last<T>(var value: T? = null)

/**
 * The most recent non-null [value]. Content animating out after its value went null shows this, so
 * it doesn't go blank while it fades.
 */
@Composable
fun <T : Any> rememberLastNonNull(value: T?): T? {
    val last = remember { Last<T>() }
    if (value != null) last.value = value
    return last.value
}
