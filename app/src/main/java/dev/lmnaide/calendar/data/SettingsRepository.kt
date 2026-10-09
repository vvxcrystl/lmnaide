package dev.lmnaide.calendar.data

import android.content.Context
import android.text.format.DateFormat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class CalendarView { SCHEDULE, DAY, THREE_DAY, WEEK, MONTH }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    /** Null follows the locale. */
    val weekStartOverride: DayOfWeek? = null,
    val use24Hour: Boolean = false,
    val defaultDurationMinutes: Int = 60,
    /** Null means no default reminder. */
    val defaultReminderMinutes: Int? = 30,
    val defaultCalendarId: Long? = null,
    val view: CalendarView = CalendarView.MONTH,
    val showTasks: Boolean = true,
) {
    val weekStart: DayOfWeek
        get() = weekStartOverride ?: WeekFields.of(Locale.getDefault()).firstDayOfWeek
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val systemUses24Hour = DateFormat.is24HourFormat(context)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit {
            putString(KEY_THEME, next.themeMode.name)
            putBoolean(KEY_DYNAMIC, next.dynamicColor)
            putString(KEY_WEEK_START, next.weekStartOverride?.name)
            putBoolean(KEY_24H, next.use24Hour)
            putInt(KEY_DURATION, next.defaultDurationMinutes)
            putInt(KEY_REMINDER, next.defaultReminderMinutes ?: NONE)
            putLong(KEY_CALENDAR, next.defaultCalendarId ?: NONE.toLong())
            putString(KEY_VIEW, next.view.name)
            putBoolean(KEY_TASKS, next.showTasks)
        }
    }

    private fun read() = Settings(
        themeMode = enumOrNull<ThemeMode>(prefs.getString(KEY_THEME, null)) ?: ThemeMode.SYSTEM,
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
        weekStartOverride = enumOrNull<DayOfWeek>(prefs.getString(KEY_WEEK_START, null)),
        use24Hour = prefs.getBoolean(KEY_24H, systemUses24Hour),
        defaultDurationMinutes = prefs.getInt(KEY_DURATION, 60),
        defaultReminderMinutes = prefs.getInt(KEY_REMINDER, 30).takeIf { it != NONE },
        defaultCalendarId = prefs.getLong(KEY_CALENDAR, NONE.toLong()).takeIf { it != NONE.toLong() },
        view = enumOrNull<CalendarView>(prefs.getString(KEY_VIEW, null)) ?: CalendarView.MONTH,
        showTasks = prefs.getBoolean(KEY_TASKS, true),
    )

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        enumValues<T>().firstOrNull { it.name == name }

    private companion object {
        const val NONE = -1
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_WEEK_START = "week_start"
        const val KEY_24H = "use_24h"
        const val KEY_DURATION = "default_duration"
        const val KEY_REMINDER = "default_reminder"
        const val KEY_CALENDAR = "default_calendar"
        const val KEY_VIEW = "view"
        const val KEY_TASKS = "show_tasks"
    }
}
