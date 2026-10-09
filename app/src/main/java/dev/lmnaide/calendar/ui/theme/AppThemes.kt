package dev.lmnaide.calendar.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import dev.lmnaide.calendar.data.ThemeMode

/** Named color themes, each with a light and a dark scheme. */
enum class AppTheme(val label: String) {
    MATERIAL_YOU("Material You"),
    LEMONADE("Lemonade"),
    JET_BLACK("Jet Black"),
    BLUSH("Blush"),
    AMETHYST("Amethyst"),
    FOREST("Forest"),
    OCEAN("Ocean"),
    EMBER("Ember"),
    IRIS("Iris");

    /** Material You uses baseline colors before Android 12. */
    val isAvailable: Boolean get() = true

    fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean =
        this == JET_BLACK || if (mode == ThemeMode.SYSTEM) systemDark else mode == ThemeMode.DARK

    fun colorScheme(dark: Boolean, context: Context): ColorScheme {
        val useDark = dark
        return when (this) {
            JET_BLACK -> JetBlackColors
            MATERIAL_YOU -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (useDark) DarkColors else LightColors
            }
            LEMONADE -> if (useDark) {
                palette(true, 0xFFEBDD68, 0xFF171609)
            } else {
                palette(false, 0xFF716000, 0xFFFFFCED).copy(
                    primaryContainer = Color(0xFFF8EA8A),
                    onPrimaryContainer = Color(0xFF221F00),
                )
            }
            BLUSH -> if (useDark) palette(true, 0xFFFF6FA8, 0xFF190B13) else palette(false, 0xFFC9266D, 0xFFFFF6F9)
            AMETHYST -> if (useDark) palette(true, 0xFFB78CFF, 0xFF120B1D) else palette(false, 0xFF8A3FE0, 0xFFF9F5FF)
            FOREST -> if (useDark) palette(true, 0xFF6FD39B, 0xFF0F1511) else palette(false, 0xFF2E7D52, 0xFFF7F8F1)
            OCEAN -> if (useDark) palette(true, 0xFF63B3F0, 0xFF0C1519) else palette(false, 0xFF1F6FB8, 0xFFF5F9FC)
            EMBER -> if (useDark) palette(true, 0xFFFFA06B, 0xFF17100D) else palette(false, 0xFFB5532A, 0xFFFFF8F4)
            IRIS -> if (useDark) palette(true, 0xFFB9A1FF, 0xFF120F1A) else palette(false, 0xFF7B5BD6, 0xFFFAF7FF)
        }
    }
}

/** Pure-black surfaces in every appearance mode, with neutral, high-contrast controls. */
private val JetBlackColors = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    primaryContainer = Color.Black, onPrimaryContainer = Color.White,
    inversePrimary = Color.White,
    secondary = Color.White, onSecondary = Color.Black,
    secondaryContainer = Color.Black, onSecondaryContainer = Color.White,
    tertiary = Color.White, onTertiary = Color.Black,
    tertiaryContainer = Color.Black, onTertiaryContainer = Color.White,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    surfaceVariant = Color.Black, onSurfaceVariant = Color(0xFFCCCCCC),
    surfaceTint = Color.Black,
    inverseSurface = Color.Black, inverseOnSurface = Color.White,
    error = Color(0xFFFFB4AB), onError = Color.Black,
    errorContainer = Color.Black, onErrorContainer = Color(0xFFFFB4AB),
    outline = Color(0xFF808080), outlineVariant = Color(0xFF333333),
    scrim = Color.Black,
    surfaceBright = Color.Black, surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black, surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
)

private fun Color.shiftHue(degrees: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[0] = (hsl[0] + degrees) % 360f
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Builds a full Material 3 scheme from one accent and one background, tinting the surfaces toward the accent. */
private fun palette(dark: Boolean, primary: Long, background: Long): ColorScheme {
    val p = Color(primary.toInt())
    val bg = Color(background.toInt())
    val ink = if (dark) Color(0xFFE6E4EA) else Color(0xFF1B1B1F)
    fun tint(amount: Float) = lerp(bg, p, amount)
    val tertiary = p.shiftHue(if (dark) 55f else 70f)
    val onPrimary = if (dark) lerp(p, Color.Black, 0.78f) else Color.White
    val primaryContainer = if (dark) tint(0.4f) else lerp(Color.White, p, 0.2f)
    val onPrimaryContainer = if (dark) lerp(p, Color.White, 0.8f) else lerp(p, Color.Black, 0.7f)
    val secondaryContainer = if (dark) tint(0.24f) else tint(0.16f)
    val tertiaryContainer = if (dark) lerp(bg, tertiary, 0.34f) else lerp(Color.White, tertiary, 0.22f)
    val onTertiaryContainer = if (dark) lerp(tertiary, Color.White, 0.8f) else lerp(tertiary, Color.Black, 0.7f)
    val errorColors = if (dark) {
        listOf(Color(0xFFF2B8B5), Color(0xFF601410), Color(0xFF8C1D18), Color(0xFFF9DEDC))
    } else {
        listOf(Color(0xFFB3261E), Color.White, Color(0xFFF9DEDC), Color(0xFF410E0B))
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = p, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            inversePrimary = lerp(p, Color.Black, 0.3f),
            secondary = lerp(p, ink, 0.25f), onSecondary = onPrimary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = ink,
            tertiary = tertiary, onTertiary = lerp(tertiary, Color.Black, 0.78f),
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = errorColors[0], onError = errorColors[1], errorContainer = errorColors[2], onErrorContainer = errorColors[3],
            background = bg, onBackground = ink, surface = bg, onSurface = ink,
            surfaceVariant = tint(0.14f), onSurfaceVariant = lerp(ink, bg, 0.25f),
            surfaceTint = p, inverseSurface = ink, inverseOnSurface = bg,
            outline = lerp(ink, bg, 0.5f), outlineVariant = lerp(ink, bg, 0.75f),
            surfaceBright = tint(0.15f), surfaceDim = bg,
            surfaceContainerLowest = lerp(bg, Color.Black, 0.3f), surfaceContainerLow = tint(0.03f),
            surfaceContainer = tint(0.06f), surfaceContainerHigh = tint(0.09f), surfaceContainerHighest = tint(0.12f),
        )
    } else {
        lightColorScheme(
            primary = p, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            inversePrimary = lerp(p, Color.White, 0.5f),
            secondary = lerp(p, ink, 0.25f), onSecondary = onPrimary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = ink,
            tertiary = tertiary, onTertiary = Color.White,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = errorColors[0], onError = errorColors[1], errorContainer = errorColors[2], onErrorContainer = errorColors[3],
            background = bg, onBackground = ink, surface = bg, onSurface = ink,
            surfaceVariant = tint(0.12f), onSurfaceVariant = lerp(ink, bg, 0.25f),
            surfaceTint = p, inverseSurface = ink, inverseOnSurface = bg,
            outline = lerp(ink, bg, 0.5f), outlineVariant = lerp(ink, bg, 0.78f),
            surfaceBright = lerp(bg, Color.White, 0.6f), surfaceDim = tint(0.1f),
            surfaceContainerLowest = lerp(bg, Color.White, 0.6f), surfaceContainerLow = tint(0.03f),
            surfaceContainer = tint(0.055f), surfaceContainerHigh = tint(0.08f), surfaceContainerHighest = tint(0.11f),
        )
    }
    return scheme
}
