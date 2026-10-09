package dev.lmnaide.calendar.widget

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lmnaide.calendar.MainActivity
import dev.lmnaide.calendar.container
import dev.lmnaide.calendar.data.ThemeMode
import dev.lmnaide.calendar.ui.event.QuickAddSheet
import dev.lmnaide.calendar.ui.event.QuickAddViewModel
import dev.lmnaide.calendar.ui.theme.CalendarTheme
import java.time.LocalDate

/** Transparent, separate task so quick add leaves the launcher visible underneath. */
class WidgetQuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            CalendarTheme(darkTheme = dark, dynamicColor = settings.dynamicColor) {
                val quickAdd: QuickAddViewModel = viewModel(factory = QuickAddViewModel.Factory)
                Box(Modifier.fillMaxSize()) {
                    QuickAddSheet(
                        defaultDate = LocalDate.now(),
                        onDismiss = { finish() },
                        onSaved = { _, message, _ ->
                            Toast.makeText(this@WidgetQuickAddActivity, message, Toast.LENGTH_SHORT).show()
                            finish()
                        },
                        onMoreOptions = { text, task, calendarId ->
                            startActivity(Intent(this@WidgetQuickAddActivity, MainActivity::class.java)
                                .putExtra("widget_edit", true)
                                .putExtra("widget_text", text)
                                .putExtra("widget_task", task)
                                .putExtra("widget_calendar", calendarId ?: 0L)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                            finish()
                        },
                        viewModel = quickAdd,
                    )
                }
            }
        }
    }
}
