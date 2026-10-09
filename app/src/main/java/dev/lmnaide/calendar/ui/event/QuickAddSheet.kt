package dev.lmnaide.calendar.ui.event

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.domain.CalendarMatcher
import dev.lmnaide.calendar.domain.CalendarReason
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.domain.SmartEventDraft
import dev.lmnaide.calendar.domain.SmartEventParser
import dev.lmnaide.calendar.domain.SmartEventResult
import dev.lmnaide.calendar.ui.EventLink
import dev.lmnaide.calendar.ui.common.ColorDot
import dev.lmnaide.calendar.ui.common.Fmt
import dev.lmnaide.calendar.ui.common.rememberNow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

private val EventExamples = listOf("Lunch with Sam tomorrow at noon", "Gym every Monday at 6pm", "Work from 9 to 5 on Friday")
private val TaskExamples = listOf("Pay rent by Friday", "Call the dentist by 5pm", "Take vitamins every day at 8am")

/**
 * Google-style quick add: one sentence in, an event or task out. Whether it's a task is guessed from
 * the wording ("by Friday") until a chip is tapped. The preview updates as you type, and Save or
 * the keyboard's Done key adds it straight to the calendar; More options opens the full editor.
 *
 * [defaultDate] is used when the text names a time but no day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSaved: (link: EventLink, message: String, date: LocalDate) -> Unit,
    onMoreOptions: (text: String, task: Boolean, calendarId: Long?) -> Unit,
    viewModel: QuickAddViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val today = rememberNow().toLocalDate()

    var input by rememberSaveable { mutableStateOf("") }
    // Null until a chip is tapped; until then the wording decides.
    var taskChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val isTask = taskChoice ?: SmartEventParser.looksLikeTask(input)
    val result = remember(input, today, isTask, defaultDate) {
        SmartEventParser.parse(input, today, settings.defaultDurationMinutes, task = isTask, defaultDate = defaultDate)
    }
    val draft = (result as? SmartEventResult.Success)?.event
    // Null until one is picked from the menu; until then keywords, past titles and names decide.
    var calendarChoice by rememberSaveable { mutableStateOf<Long?>(null) }
    val match = remember(draft?.title, input, calendars, history) {
        CalendarMatcher.match(draft?.title ?: input, calendars, history)
    }
    val calendar = calendars.firstOrNull { it.id == calendarChoice }
        ?: match?.calendar
        ?: calendars.firstOrNull { it.id == settings.defaultCalendarId }
        ?: calendars.firstOrNull()
    val reason = match?.reason?.takeIf { calendarChoice == null && calendar == match.calendar }
    val canSave = draft != null && calendar != null && !viewModel.isSaving

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }
    fun save() {
        val d = draft ?: return
        if (!canSave) return
        val c = calendar ?: return
        viewModel.save(d, isTask, c) { link ->
            val message = "${if (isTask) "Task" else "Event"} added · ${whenLabel(d, isTask, today, settings.use24Hour)}"
            closeThen { onSaved(link, message, d.start.toLocalDate()) }
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The keyboard only opens once the sheet's window is showing.
        snapshotFlow { sheetState.isVisible }.first { it }
        focus.requestFocus()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KindChip("Event", Icons.Outlined.Event, selected = !isTask) { taskChoice = false }
                KindChip("Task", Icons.Outlined.TaskAlt, selected = isTask) { taskChoice = true }
            }

            BasicTextField(
                value = input,
                onValueChange = { input = it.take(500) },
                textStyle = MaterialTheme.typography.headlineSmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                if (isTask) "What needs doing, and by when?" else "What, and when?",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )

            // Keyed on the kind of content so the rows don't flicker while typing within one state.
            val preview = when {
                input.isBlank() -> Preview.Examples
                draft != null -> Preview.Draft
                else -> Preview.Problem
            }
            AnimatedContent(
                targetState = preview,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "preview",
                modifier = Modifier.heightIn(min = 56.dp),
            ) { shown ->
                when (shown) {
                    // Scrolls edge to edge, past the sheet's side padding.
                    Preview.Examples -> Row(
                        Modifier.layout { measurable, constraints ->
                            val bleed = 24.dp.roundToPx()
                            val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + 2 * bleed))
                            layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
                        }.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (if (isTask) TaskExamples else EventExamples).forEach { example ->
                            SuggestionChip(onClick = { input = example }, label = { Text(example) })
                        }
                    }
                    Preview.Draft -> if (draft != null) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PreviewRow(if (isTask) Icons.Outlined.TaskAlt else Icons.Outlined.Event, draft.title, emphasized = true)
                        PreviewRow(Icons.Outlined.Schedule, whenLabel(draft, isTask, today, settings.use24Hour))
                        if (draft.recurrence != Recurrence.NONE) {
                            PreviewRow(Icons.Outlined.Repeat, Fmt.recurrence(draft.recurrence, draft.start.toLocalDate()))
                        }
                        calendar?.let { CalendarRow(it, reason, calendars) { picked -> calendarChoice = picked.id } }
                    }
                    Preview.Problem -> PreviewRow(
                        Icons.Outlined.Info,
                        (result as? SmartEventResult.NeedsDetails)?.message.orEmpty(),
                        muted = true,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { closeThen { onMoreOptions(input.trim(), isTask, calendar?.id) } }) { Text("More options") }
                Spacer(Modifier.weight(1f))
                Button(onClick = ::save, enabled = canSave) { Text("Save") }
            }
        }
    }
}

private enum class Preview { Examples, Draft, Problem }

@Composable
private fun KindChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        shape = CircleShape,
    )
}

/** The calendar the item will go into, why it was picked, and a menu to pick another. */
@Composable
private fun CalendarRow(calendar: CalendarEntity, reason: CalendarReason?, calendars: List<CalendarEntity>, onPick: (CalendarEntity) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Change calendar") { menuOpen = true }
                .padding(vertical = 4.dp)
                .padding(end = 8.dp),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { ColorDot(Color(calendar.color), size = 14.dp) }
            Column {
                Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                reason?.let {
                    Text(
                        when (it) {
                            is CalendarReason.Keyword -> "Matched “${it.keyword}”"
                            CalendarReason.SameTitle -> "Same as last time"
                            CalendarReason.Name -> "Named in the title"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            calendars.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name) },
                    leadingIcon = { ColorDot(Color(option.color), size = 14.dp) },
                    trailingIcon = if (option.id == calendar.id) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = { menuOpen = false; onPick(option) },
                )
            }
        }
    }
}

@Composable
private fun PreviewRow(icon: ImageVector, text: String, muted: Boolean = false, emphasized: Boolean = false) {
    val color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, contentDescription = null, tint = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, color = color)
    }
}

/** "Tomorrow · 3 – 5 PM", "Due Fri, Oct 9 by 9 PM", or "Today · All day". */
private fun whenLabel(draft: SmartEventDraft, task: Boolean, today: LocalDate, use24h: Boolean): String {
    fun day(date: LocalDate) = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> Fmt.dayMedium(date, today)
    }
    val startDay = day(draft.start.toLocalDate())
    val start = Fmt.time(draft.start.toLocalTime(), use24h)
    return when {
        task && draft.allDay -> "Due ${startDay.lowercaseRelative()}"
        task -> "Due ${startDay.lowercaseRelative()} by $start"
        draft.allDay -> "$startDay · All day"
        draft.end.toLocalDate() != draft.start.toLocalDate() ->
            "$startDay, $start – ${day(draft.end.toLocalDate())}, ${Fmt.time(draft.end.toLocalTime(), use24h)}"
        else -> "$startDay · ${Fmt.timeRange(draft.start.toLocalTime(), draft.end.toLocalTime(), use24h)}"
    }
}

private fun String.lowercaseRelative() = if (this == "Today" || this == "Tomorrow") lowercase() else this
