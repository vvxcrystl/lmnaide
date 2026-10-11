package dev.lmnaide.calendar.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import dev.lmnaide.calendar.ui.theme.LocalThemeReveal
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.lmnaide.calendar.data.ThemeMode
import dev.lmnaide.calendar.ui.theme.AppTheme

/** The Color scheme row (system, light, dark) followed by the theme gallery. */
@Composable
fun ThemePicker(
    themeMode: ThemeMode,
    appTheme: AppTheme,
    onThemeMode: (ThemeMode) -> Unit,
    onAppTheme: (AppTheme) -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = appTheme.isDark(themeMode, systemDark)
    val context = LocalContext.current
    val lightScheme = remember(appTheme) { appTheme.colorScheme(false, context) }
    val darkScheme = remember(appTheme) { appTheme.colorScheme(true, context) }

    Column(Modifier.padding(16.dp)) {
        Text("Color scheme", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemeMode.entries.forEach { mode ->
                SchemeCard(
                    label = mode.name.lowercase().replaceFirstChar(Char::uppercase),
                    selected = themeMode == mode,
                    onClick = { onThemeMode(mode) },
                    modifier = Modifier.weight(1f),
                ) {
                    SchemePreview(mode, lightScheme, darkScheme)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Themes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        val themes = AppTheme.entries.filter { it.isAvailable }
        themes.chunked(2).forEach { row ->
            Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { theme ->
                    ThemeCard(
                        theme = theme,
                        selected = theme == appTheme,
                        dark = dark,
                        onClick = { onAppTheme(theme) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SelectableCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val reveal = LocalThemeReveal.current
    val view = LocalView.current
    var center by remember { mutableStateOf(Offset.Zero) }
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Surface(
        onClick = {
            // The new theme spreads out from the tapped card.
            reveal?.start(view, center)
            onClick()
        },
        // The current choice ignores taps.
        enabled = !selected,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = border,
        modifier = modifier.onGloballyPositioned { center = it.boundsInWindow().center },
    ) { content() }
}

@Composable
private fun SchemeCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    preview: @Composable () -> Unit,
) {
    SelectableCard(selected, onClick, modifier) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(10.dp))) { preview() }
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A tiny schedule screen in the theme's own colors. System splits it light and dark. */
@Composable
private fun SchemePreview(mode: ThemeMode, light: ColorScheme, dark: ColorScheme) {
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.35f)) {
        when (mode) {
            ThemeMode.LIGHT -> mockSchedule(light, light, light, dark)
            ThemeMode.DARK -> mockSchedule(dark, dark, light, dark)
            ThemeMode.SYSTEM -> mockSchedule(light, dark, light, dark)
        }
    }
}

/**
 * Today's date circle beside three event cards, all in shades of the theme's accent.
 * When [left] and [right] differ, the background splits sharply down the middle while the accents blend across it.
 */
private fun DrawScope.mockSchedule(left: ColorScheme, right: ColorScheme, light: ColorScheme, dark: ColorScheme) {
    val w = size.width
    val h = size.height
    if (right === left) {
        drawRect(left.surface)
    } else {
        drawRect(left.surface, size = Size(w / 2, h))
        drawRect(right.surface, Offset(w / 2, 0f), Size(w / 2, h))
    }
    // Shades step toward the other mode's accent rather than the background, so they stay solid.
    fun shade(scheme: ColorScheme, step: Float) =
        lerp(scheme.primary, if (scheme === light) dark.primary else light.primary, step)
    fun accent(step: Float) = Brush.horizontalGradient(
        0.42f to shade(left, step),
        0.58f to shade(right, step),
        endX = w,
    )
    drawCircle(accent(0f), radius = h * 0.1f, center = Offset(w * 0.17f, h * 0.27f))
    listOf(0f, 0.4f, 0.8f).forEachIndexed { i, step ->
        drawRoundRect(accent(step), Offset(w * 0.34f, h * (0.17f + i * 0.24f)), Size(w * 0.56f, h * 0.18f), CornerRadius(h * 0.06f))
    }
}

@Composable
private fun ThemeCard(theme: AppTheme, selected: Boolean, dark: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    SelectableCard(selected, onClick, modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Orb(theme.colorScheme(dark, context))
            Spacer(Modifier.height(10.dp))
            Text(
                theme.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/** A glass-like sphere in a theme's colors: background at the edge, accent blooming from a corner. */
@Composable
private fun Orb(scheme: ColorScheme) {
    Canvas(Modifier.size(44.dp).clip(CircleShape)) {
        drawRect(scheme.background)
        drawRect(
            Brush.radialGradient(
                0f to scheme.primary.copy(alpha = 0.95f),
                0.7f to scheme.primaryContainer.copy(alpha = 0.55f),
                1f to Color.Transparent,
                center = Offset(size.width * 0.68f, size.height * 0.74f),
                radius = size.width * 0.9f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                0f to Color.White.copy(alpha = if (scheme.background.luminanceIsLight()) 0.8f else 0.28f),
                1f to Color.Transparent,
                center = Offset(size.width * 0.3f, size.height * 0.25f),
                radius = size.width * 0.55f,
            ),
        )
    }
}

private fun Color.luminanceIsLight() = (red * 0.299f + green * 0.587f + blue * 0.114f) > 0.6f
