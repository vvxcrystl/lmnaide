package dev.lmnaide.calendar.ui.event

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import dev.lmnaide.calendar.data.EventKind
import dev.lmnaide.calendar.data.Importance
import dev.lmnaide.calendar.ui.common.contentColor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.ui.EventLink
import dev.lmnaide.calendar.ui.common.ColorDot
import dev.lmnaide.calendar.ui.common.ConfirmDialog
import dev.lmnaide.calendar.ui.common.DatePickerModal
import dev.lmnaide.calendar.ui.common.EventColors
import dev.lmnaide.calendar.ui.common.Fmt
import dev.lmnaide.calendar.ui.common.OptionsDialog
import dev.lmnaide.calendar.ui.common.TimePickerModal
import java.time.LocalDate

private enum class EditorDialog {
    StartDate, StartTime, EndDate, EndTime, Repeat, RepeatEnd, RepeatUntil, Reminder, Calendar, Color, Scope, Discard
}

private val TimedReminders = listOf(0, 5, 10, 15, 30, 60, 120, 24 * 60, 7 * 24 * 60)
private val AllDayReminders = listOf(0, 24 * 60, 7 * 24 * 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorScreen(
    onClose: () -> Unit,
    onSaved: (EventLink) -> Unit,
    viewModel: EventEditorViewModel = viewModel(factory = EventEditorViewModel.Factory),
) {
    val state = viewModel.state
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf<EditorDialog?>(null) }
    val use24h = viewModel.use24Hour

    BackHandler(enabled = viewModel.isDirty) { dialog = EditorDialog.Discard }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { if (viewModel.isDirty) dialog = EditorDialog.Discard else onClose() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            if (viewModel.needsScope) dialog = EditorDialog.Scope else viewModel.save(null, onSaved)
                        },
                        enabled = state?.isValid == true && !viewModel.isSaving,
                        modifier = Modifier.padding(end = 12.dp),
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (state == null) return@Scaffold
        val today = remember { LocalDate.now() }
        val calendar = calendars.firstOrNull { it.id == state.calendarId }
        val eventColor = state.color ?: calendar?.color

        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            TitleField(
                value = state.title,
                onValueChange = { title -> viewModel.update { it.copy(title = title) } },
                autoFocus = viewModel.isNew,
            )
            KindSelector(state.kind, viewModel::setKind)
            Divider()

            EditorRow(icon = Icons.Outlined.Schedule) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("All-day", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = state.allDay, onCheckedChange = { allDay -> viewModel.update { it.copy(allDay = allDay) } })
                }
            }
            DateTimeRow(
                date = Fmt.dayMedium(state.startDate, today),
                time = Fmt.time(state.startTime, use24h).takeUnless { state.allDay },
                onDate = { dialog = EditorDialog.StartDate },
                onTime = { dialog = EditorDialog.StartTime },
            )
            if (!state.isTask) {
                DateTimeRow(
                    date = Fmt.dayMedium(state.endDate, today),
                    time = Fmt.time(state.endTime, use24h).takeUnless { state.allDay },
                    onDate = { dialog = EditorDialog.EndDate },
                    onTime = { dialog = EditorDialog.EndTime },
                    error = !state.isValid,
                )
            }
            if (!state.isValid) {
                Text(
                    "The event ends before it starts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 62.dp, bottom = 8.dp),
                )
            }
            EditorRow(icon = Icons.Outlined.Repeat, onClick = { dialog = EditorDialog.Repeat }) {
                Text(Fmt.recurrence(state.recurrence, state.startDate), style = MaterialTheme.typography.bodyLarge)
            }
            if (state.recurrence != Recurrence.NONE) {
                EditorRow(icon = null, onClick = { dialog = EditorDialog.RepeatEnd }) {
                    Text(
                        state.recurrenceUntil?.let { "Ends on ${Fmt.dayShort(it)}" } ?: "Never ends",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Divider()

            EditorRow(icon = Icons.Outlined.Flag, alignTop = true) {
                ImportanceSelector(state.importance) { importance -> viewModel.update { it.copy(importance = importance) } }
            }
            Divider()

            if (!state.isTask) {
                EditorRow(icon = Icons.Outlined.LocationOn) {
                    PlainTextField(
                        value = state.location,
                        onValueChange = { location -> viewModel.update { it.copy(location = location) } },
                        placeholder = "Add location",
                    )
                }
                Divider()
            }

            EditorRow(icon = Icons.Outlined.Notifications, alignTop = true) {
                Column {
                    state.reminders.forEach { minutes ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                Fmt.reminder(minutes, state.allDay, use24h),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { viewModel.update { it.copy(reminders = it.reminders - minutes) } },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove notification", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Text(
                        "Add notification",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dialog = EditorDialog.Reminder }
                            .padding(vertical = 6.dp),
                    )
                }
            }
            Divider()

            EditorRow(icon = Icons.Outlined.Event, onClick = { dialog = EditorDialog.Calendar }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    calendar?.let { ColorDot(Color(it.color), Modifier.padding(end = 12.dp)) }
                    Text(calendar?.name.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                }
            }
            EditorRow(icon = Icons.Outlined.Palette, onClick = { dialog = EditorDialog.Color }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    eventColor?.let { ColorDot(Color(it), Modifier.padding(end = 12.dp)) }
                    Text(
                        if (state.color == null) "Calendar color" else EventColors.labelFor(state.color),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            Divider()

            EditorRow(icon = Icons.AutoMirrored.Outlined.Notes, alignTop = true) {
                PlainTextField(
                    value = state.description,
                    onValueChange = { description -> viewModel.update { it.copy(description = description) } },
                    placeholder = "Add description",
                    singleLine = false,
                )
            }
        }

        when (dialog) {
            EditorDialog.StartDate -> DatePickerModal(state.startDate, { viewModel.setStart(date = it) }, { dialog = null })
            EditorDialog.EndDate -> DatePickerModal(state.endDate, { viewModel.setEnd(date = it) }, { dialog = null })
            EditorDialog.StartTime -> TimePickerModal(state.startTime, use24h, { viewModel.setStart(time = it) }, { dialog = null })
            EditorDialog.EndTime -> TimePickerModal(state.endTime, use24h, { viewModel.setEnd(time = it) }, { dialog = null })
            EditorDialog.Repeat -> OptionsDialog(
                title = "Repeat",
                options = Recurrence.entries,
                selected = state.recurrence,
                label = { Fmt.recurrence(it, state.startDate) },
                onSelect = { rule -> viewModel.update { it.copy(recurrence = rule) } },
                onDismiss = { dialog = null },
            )
            EditorDialog.RepeatEnd -> OptionsDialog(
                title = "Ends",
                options = listOf(false, true),
                selected = state.recurrenceUntil != null,
                label = { if (it) "On a date" else "Never" },
                onSelect = { hasEnd ->
                    if (hasEnd) {
                        // Opened after this dialog closes itself.
                        dialog = EditorDialog.RepeatUntil
                    } else {
                        viewModel.update { it.copy(recurrenceUntil = null) }
                    }
                },
                onDismiss = { if (dialog == EditorDialog.RepeatEnd) dialog = null },
            )
            EditorDialog.RepeatUntil -> DatePickerModal(
                state.recurrenceUntil ?: state.startDate.plusMonths(1),
                { until -> viewModel.update { it.copy(recurrenceUntil = maxOf(until, it.startDate)) } },
                { dialog = null },
            )
            EditorDialog.Reminder -> {
                val options = (if (state.allDay) AllDayReminders else TimedReminders) - state.reminders.toSet()
                OptionsDialog(
                    title = "Notification",
                    options = options,
                    selected = null,
                    label = { Fmt.reminder(it, state.allDay, use24h) },
                    onSelect = { minutes -> viewModel.update { it.copy(reminders = (it.reminders + minutes).sorted()) } },
                    onDismiss = { dialog = null },
                )
            }
            EditorDialog.Calendar -> OptionsDialog(
                title = "Calendar",
                options = calendars,
                selected = calendar,
                label = { it.name },
                leading = { ColorDot(Color(it.color)) },
                onSelect = { picked -> viewModel.update { it.copy(calendarId = picked.id) } },
                onDismiss = { dialog = null },
            )
            EditorDialog.Color -> OptionsDialog(
                title = "Event color",
                options = listOf<EventColors?>(null) + EventColors.entries,
                selected = EventColors.entries.firstOrNull { it.argb == state.color },
                label = { it?.label ?: "Calendar color" },
                leading = { ColorDot(it?.color ?: Color(calendar?.color ?: 0)) },
                onSelect = { picked -> viewModel.update { it.copy(color = picked?.argb) } },
                onDismiss = { dialog = null },
            )
            EditorDialog.Scope -> OptionsDialog(
                title = "Save recurring event",
                options = viewModel.scopeOptions,
                selected = null,
                label = { it.label },
                onSelect = { viewModel.save(it, onSaved) },
                onDismiss = { if (dialog == EditorDialog.Scope) dialog = null },
            )
            EditorDialog.Discard -> ConfirmDialog(
                title = "Discard changes?",
                text = "Your changes to this event won't be saved.",
                confirmLabel = "Discard",
                onConfirm = onClose,
                onDismiss = { dialog = null },
            )
            null -> Unit
        }
    }
}

@Composable
private fun KindSelector(kind: EventKind, onSelect: (EventKind) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(start = 62.dp, end = 20.dp, bottom = 12.dp),
    ) {
        listOf(EventKind.EVENT to "Event", EventKind.TASK to "Task").forEach { (option, label) ->
            FilterChip(
                selected = kind == option,
                onClick = { onSelect(option) },
                label = { Text(label) },
                shape = CircleShape,
            )
        }
    }
}

/** None, then Low, Medium and High, each marked with its color. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportanceSelector(importance: Importance?, onSelect: (Importance?) -> Unit) {
    Column {
        Text("Importance", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (listOf<Importance?>(null) + Importance.entries).forEach { option ->
                FilterChip(
                    selected = importance == option,
                    onClick = { onSelect(option) },
                    label = { Text(option?.label ?: "None") },
                    leadingIcon = option?.let { { ColorDot(Color(it.argb), size = 10.dp) } },
                    shape = CircleShape,
                    border = null,
                    colors = if (option != null) {
                        FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            selectedContainerColor = Color(option.argb),
                            selectedLabelColor = Color(option.argb).contentColor(),
                        )
                    } else {
                        FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    },
                )
            }
        }
        val hint = when (importance) {
            Importance.LOW -> "Reminders arrive silently"
            Importance.MEDIUM -> "Standard reminders with sound"
            Importance.HIGH -> "Urgent alerts at start that repeat until you respond"
            null -> null
        }
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TitleField(value: String, onValueChange: (String) -> Unit, autoFocus: Boolean) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) focus.requestFocus() }
    PlainTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = "Add title",
        textStyle = MaterialTheme.typography.headlineSmall,
        modifier = Modifier
            .padding(start = 62.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
            .focusRequester(focus),
    )
}

@Composable
private fun PlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

@Composable
private fun EditorRow(
    icon: ImageVector?,
    onClick: (() -> Unit)? = null,
    alignTop: Boolean = false,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = if (alignTop) Alignment.Top else Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(Modifier.width(42.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun DateTimeRow(
    date: String,
    time: String?,
    onDate: () -> Unit,
    onTime: () -> Unit,
    error: Boolean = false,
) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(
            date,
            style = MaterialTheme.typography.bodyLarge,
            color = color,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onDate)
                .padding(start = 62.dp, top = 12.dp, bottom = 12.dp),
        )
        if (time != null) {
            Text(
                time,
                style = MaterialTheme.typography.bodyLarge,
                color = color,
                modifier = Modifier
                    .clickable(onClick = onTime)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
}
