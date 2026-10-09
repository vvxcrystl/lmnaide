package dev.lmnaide.calendar.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Google Calendar's event color palette. */
enum class EventColors(val label: String, val argb: Int) {
    Tomato("Tomato", 0xFFD50000.toInt()),
    Flamingo("Flamingo", 0xFFE67C73.toInt()),
    Tangerine("Tangerine", 0xFFF4511E.toInt()),
    Banana("Banana", 0xFFF6BF26.toInt()),
    Sage("Sage", 0xFF33B679.toInt()),
    Basil("Basil", 0xFF0B8043.toInt()),
    Peacock("Peacock", 0xFF039BE5.toInt()),
    Blueberry("Blueberry", 0xFF3F51B5.toInt()),
    Lavender("Lavender", 0xFF7986CB.toInt()),
    Grape("Grape", 0xFF8E24AA.toInt()),
    Graphite("Graphite", 0xFF616161.toInt());

    val color: Color get() = Color(argb)

    companion object {
        fun labelFor(argb: Int): String = entries.firstOrNull { it.argb == argb }?.label ?: "Custom"
    }
}

/** Past events fade toward the background, like Google Calendar's. */
fun Color.faded(surface: Color, past: Boolean): Color = if (past) lerp(this, surface, 0.55f) else this

fun Color.contentColor(): Color = if (luminance() > 0.55f) Color(0xFF1F1F1F) else Color.White
