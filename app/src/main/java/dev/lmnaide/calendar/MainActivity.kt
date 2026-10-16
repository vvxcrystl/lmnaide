package dev.lmnaide.calendar

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import dev.lmnaide.calendar.ui.theme.LocalThemeReveal
import dev.lmnaide.calendar.ui.theme.ThemeReveal
import dev.lmnaide.calendar.ui.theme.ThemeRevealOverlay
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lmnaide.calendar.ui.CalendarNavHost
import dev.lmnaide.calendar.ui.EventLink
import dev.lmnaide.calendar.ui.theme.CalendarTheme
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import dev.lmnaide.calendar.ui.event.requestClockAlarm
import dev.lmnaide.calendar.ui.event.showClockAlarmMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingEditor = MutableStateFlow<String?>(null)
    private val pendingLink = MutableStateFlow<EventLink?>(null)
    private val themeReveal = ThemeReveal()
    private var clockRequest: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        val settingsRepository = container.settings
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle()
            val dark = settings.appTheme.isDark(settings.themeMode, isSystemInDarkTheme())
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            CompositionLocalProvider(LocalThemeReveal provides themeReveal) {
                CalendarTheme(darkTheme = dark, theme = settings.appTheme) {
                    CalendarNavHost(pendingLink = pendingLink, onLinkHandled = { pendingLink.value = null }, pendingEditor = pendingEditor, onEditorHandled = { pendingEditor.value = null })
                }
            }
        }
        // The reveal draws in its own view above the app, so screen captures for it never include it.
        addContentView(
            ComposeView(this).apply { setContent { ThemeRevealOverlay(themeReveal) } },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onResume() {
        super.onResume()
        container.cloud.setForeground(true)
        // Picks up notification access granted in Settings and tidies setup notifications after Clock.
        container.appScope.launch(Dispatchers.IO) { container.reminders.reschedule() }
        requestPendingClockAlarm()
    }

    override fun onPause() {
        container.cloud.setForeground(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra("widget_edit", false)) {
            pendingEditor.value = dev.lmnaide.calendar.ui.Routes.newEvent(
                java.time.LocalDate.now(),
                task = intent.getBooleanExtra("widget_task", false),
                text = intent.getStringExtra("widget_text").orEmpty(),
                people = intent.getStringExtra("widget_people") ?: "[]",
                calendarId = intent.getLongExtra("widget_calendar", 0L).takeIf { it != 0L },
            )
        }
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, 0L)
        val link = EventLink(eventId, intent.getLongExtra(EXTRA_INSTANCE, 0L))
        if (eventId != 0L) pendingLink.value = link
        // From the setup notification for an alarm that was too far ahead for Clock. The notification
        // is gone once tapped, so the request is saved until onResume can send it, even if the
        // activity or process is recreated first. The controller also skips repeats.
        if (intent.getBooleanExtra(EXTRA_SET_CLOCK_ALARM, false)) {
            intent.removeExtra(EXTRA_SET_CLOCK_ALARM)
            if (eventId != 0L) savePendingClockAlarm(link)
        }
        // Opening a reminder counts as seeing it, which stops high-importance repeats.
        intent.getStringExtra(EXTRA_REMINDER_KEY)?.let { key ->
            container.appScope.launch { container.reminders.acknowledge(key) }
        }
    }

    private val clockPrefs get() = getSharedPreferences("clock_alarm_setup", MODE_PRIVATE)

    private fun savePendingClockAlarm(link: EventLink) {
        clockPrefs.edit(commit = true) {
            putString(KEY_PENDING_CLOCK, "${link.eventId}|${link.instanceId}|${System.currentTimeMillis()}")
        }
    }

    /** Clock must be asked while the app is in front, so this runs from onResume. */
    private fun requestPendingClockAlarm() {
        if (clockRequest?.isActive == true) return
        val saved = clockPrefs.getString(KEY_PENDING_CLOCK, null) ?: return
        val parts = saved.split('|').map { it.toLongOrNull() }
        val eventId = parts.getOrNull(0)
        val instanceId = parts.getOrNull(1)
        val savedAt = parts.getOrNull(2)
        if (eventId == null || instanceId == null || savedAt == null ||
            System.currentTimeMillis() - savedAt !in 0..PENDING_CLOCK_MAX_AGE_MS
        ) {
            clockPrefs.edit { remove(KEY_PENDING_CLOCK) }
            return
        }
        clockRequest = lifecycleScope.launch {
            val message = requestClockAlarm(this@MainActivity, EventLink(eventId, instanceId), manual = true)
            // Clear only after the request finishes, and only if no newer tap replaced it meanwhile.
            if (clockPrefs.getString(KEY_PENDING_CLOCK, null) == saved) clockPrefs.edit { remove(KEY_PENDING_CLOCK) }
            message?.let { showClockAlarmMessage(this@MainActivity, it) }
            clockRequest = null
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) requestPendingClockAlarm()
        }
    }

    companion object {
        const val EXTRA_SET_CLOCK_ALARM = "set_clock_alarm"
        private const val EXTRA_EVENT_ID = "event_id"
        private const val EXTRA_INSTANCE = "instance"
        private const val EXTRA_REMINDER_KEY = "reminder_key"
        private const val KEY_PENDING_CLOCK = "pending"
        private const val PENDING_CLOCK_MAX_AGE_MS = 60 * 60 * 1000L

        fun eventIntent(context: Context, eventId: Long, instanceId: Long, reminderKey: String? = null): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_INSTANCE, instanceId)
                .putExtra(EXTRA_REMINDER_KEY, reminderKey)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
