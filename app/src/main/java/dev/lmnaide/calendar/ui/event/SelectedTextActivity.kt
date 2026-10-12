package dev.lmnaide.calendar.ui.event

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
import dev.lmnaide.calendar.domain.SelectedTextInput
import dev.lmnaide.calendar.ui.theme.CalendarTheme
import java.time.LocalDate

/** Review selected or shared text over the source app; closing or saving returns to that app. */
class SelectedTextActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.toString()
        if (text.isNullOrBlank()) {
            finish()
            return
        }
        // Never return replacement text, even when the source selection is editable.
        setResult(RESULT_CANCELED)
        val initialText = SelectedTextInput.prepare(text)
        enableEdgeToEdge()
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            val dark = settings.appTheme.isDark(settings.themeMode, isSystemInDarkTheme())
            CalendarTheme(darkTheme = dark, theme = settings.appTheme) {
                val quickAdd: QuickAddViewModel = viewModel(factory = QuickAddViewModel.Factory)
                Box(Modifier.fillMaxSize()) {
                    QuickAddSheet(
                        defaultDate = LocalDate.now(),
                        initialText = initialText,
                        onDismiss = { finish() },
                        onSaved = { _, message, _ ->
                            Toast.makeText(this@SelectedTextActivity, message, Toast.LENGTH_SHORT).show()
                            finish()
                        },
                        onMoreOptions = { input, task, calendarId, people ->
                            startActivity(Intent(this@SelectedTextActivity, MainActivity::class.java)
                                .putExtra("widget_edit", true)
                                .putExtra("widget_text", input)
                                .putExtra("widget_people", people)
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
