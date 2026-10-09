package dev.lmnaide.calendar.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lmnaide.calendar.BuildConfig
import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.CalendarRepository
import dev.lmnaide.calendar.data.Settings
import dev.lmnaide.calendar.data.SettingsRepository
import dev.lmnaide.calendar.data.ThemeMode
import dev.lmnaide.calendar.ui.common.Fmt
import dev.lmnaide.calendar.ui.common.OptionsDialog
import dev.lmnaide.calendar.ui.common.appViewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

class SettingsViewModel(
    repository: CalendarRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings = settingsRepository.settings
    val calendars: StateFlow<List<CalendarEntity>> = repository.calendars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(transform: (Settings) -> Settings) = settingsRepository.update(transform)

    companion object {
        val Factory = appViewModelFactory { container, _ -> SettingsViewModel(container.repository, container.settings) }
    }
}

private enum class SettingsDialog { WeekStart, Duration, Reminder, DefaultCalendar }

private val Durations = listOf(15, 30, 45, 60, 90, 120)
private val Reminders = listOf<Int?>(null, 0, 5, 10, 15, 30, 60, 24 * 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onManageCalendars: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }
    val defaultCalendar = calendars.firstOrNull { it.id == settings.defaultCalendarId } ?: calendars.firstOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Section("Appearance") {
                Column(Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.update { it.copy(themeMode = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                            ) {
                                Text(mode.name.lowercase().replaceFirstChar(Char::uppercase))
                            }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    RowDivider()
                    SwitchRow(
                        title = "Dynamic color",
                        subtitle = "Use colors from your wallpaper",
                        checked = settings.dynamicColor,
                        onCheckedChange = { on -> viewModel.update { it.copy(dynamicColor = on) } },
                    )
                }
            }

            Section("Calendar") {
                ClickRow(
                    title = "Start of the week",
                    value = settings.weekStartOverride?.fullName()
                        ?: "Locale default (${WeekFields.of(LocalConfiguration.current.locales[0]).firstDayOfWeek.fullName()})",
                    onClick = { dialog = SettingsDialog.WeekStart },
                )
                RowDivider()
                SwitchRow(
                    title = "24-hour time",
                    subtitle = Fmt.time(LocalTime.of(15, 30), settings.use24Hour),
                    checked = settings.use24Hour,
                    onCheckedChange = { on -> viewModel.update { it.copy(use24Hour = on) } },
                )
                RowDivider()
                ClickRow(title = "Manage calendars", value = "${calendars.size} calendars", onClick = onManageCalendars, chevron = true)
            }

            Section("New events") {
                ClickRow(
                    title = "Default calendar",
                    value = defaultCalendar?.name.orEmpty(),
                    onClick = { dialog = SettingsDialog.DefaultCalendar },
                )
                RowDivider()
                ClickRow(
                    title = "Default duration",
                    value = Fmt.duration(settings.defaultDurationMinutes),
                    onClick = { dialog = SettingsDialog.Duration },
                )
                RowDivider()
                ClickRow(
                    title = "Default notification",
                    value = reminderLabel(settings.defaultReminderMinutes),
                    onClick = { dialog = SettingsDialog.Reminder },
                )
            }

            Section("About") {
                ClickRow(title = "Version", value = BuildConfig.VERSION_NAME, onClick = null)
            }
        }
    }

    when (dialog) {
        SettingsDialog.WeekStart -> OptionsDialog(
            title = "Start of the week",
            options = listOf(null, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY),
            selected = settings.weekStartOverride,
            label = { it?.fullName() ?: "Locale default" },
            onSelect = { day -> viewModel.update { it.copy(weekStartOverride = day) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Duration -> OptionsDialog(
            title = "Default duration",
            options = Durations,
            selected = settings.defaultDurationMinutes,
            label = { Fmt.duration(it) },
            onSelect = { minutes -> viewModel.update { it.copy(defaultDurationMinutes = minutes) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.Reminder -> OptionsDialog(
            title = "Default notification",
            options = Reminders,
            selected = settings.defaultReminderMinutes,
            label = ::reminderLabel,
            onSelect = { minutes -> viewModel.update { it.copy(defaultReminderMinutes = minutes) } },
            onDismiss = { dialog = null },
        )
        SettingsDialog.DefaultCalendar -> OptionsDialog(
            title = "Default calendar",
            options = calendars,
            selected = defaultCalendar,
            label = { it.name },
            onSelect = { calendar -> viewModel.update { it.copy(defaultCalendarId = calendar.id) } },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private fun DayOfWeek.fullName() = getDisplayName(TextStyle.FULL, Locale.getDefault())

private fun reminderLabel(minutes: Int?) = when (minutes) {
    null -> "None"
    0 -> "At time of event"
    else -> "${Fmt.duration(minutes)} before"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ClickRow(title: String, value: String, onClick: (() -> Unit)?, chevron: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
