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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val pendingEditor = MutableStateFlow<String?>(null)
    private val pendingLink = MutableStateFlow<EventLink?>(null)
    private val themeReveal = ThemeReveal()

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra("widget_edit", false)) {
            pendingEditor.value = dev.lmnaide.calendar.ui.Routes.newEvent(
                java.time.LocalDate.now(),
                task = intent.getBooleanExtra("widget_task", false),
                text = intent.getStringExtra("widget_text").orEmpty(),
                calendarId = intent.getLongExtra("widget_calendar", 0L).takeIf { it != 0L },
            )
        }
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, 0L)
        if (eventId != 0L) pendingLink.value = EventLink(eventId, intent.getLongExtra(EXTRA_INSTANCE, 0L))
        // Opening a reminder counts as seeing it, which stops high-importance repeats.
        intent.getStringExtra(EXTRA_REMINDER_KEY)?.let { key ->
            container.appScope.launch { container.reminders.acknowledge(key) }
        }
    }

    companion object {
        private const val EXTRA_EVENT_ID = "event_id"
        private const val EXTRA_INSTANCE = "instance"
        private const val EXTRA_REMINDER_KEY = "reminder_key"

        fun eventIntent(context: Context, eventId: Long, instanceId: Long, reminderKey: String? = null): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_EVENT_ID, eventId)
                .putExtra(EXTRA_INSTANCE, instanceId)
                .putExtra(EXTRA_REMINDER_KEY, reminderKey)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
