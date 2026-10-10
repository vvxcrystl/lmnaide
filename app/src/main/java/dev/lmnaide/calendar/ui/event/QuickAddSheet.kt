package dev.lmnaide.calendar.ui.event

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import dev.lmnaide.calendar.contacts.PeopleEditor
import dev.lmnaide.calendar.contacts.hasPeople
import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.Converters
import dev.lmnaide.calendar.domain.CalendarMatcher
import dev.lmnaide.calendar.domain.CalendarReason
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Group
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
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lmnaide.calendar.data.Importance
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
    onMoreOptions: (text: String, task: Boolean, calendarId: Long?, people: String) -> Unit,
    viewModel: QuickAddViewModel,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val today = rememberNow().toLocalDate()

    var peopleJson by rememberSaveable { mutableStateOf("[]") }
    val people = remember(peopleJson) { Converters().stringToPeople(peopleJson) }
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
    // Unset until one is picked from the menu; until then the calendar's default applies.
    var importanceChoice by rememberSaveable { mutableIntStateOf(IMPORTANCE_UNSET) }
    val importance = when (importanceChoice) {
        IMPORTANCE_UNSET -> calendar?.defaultImportance
        IMPORTANCE_NONE -> null
        else -> Importance.entries[importanceChoice]
    }
    val reason = match?.reason?.takeIf { calendarChoice == null && calendar == match.calendar }
    val canSave = draft != null && calendar != null && !viewModel.isSaving

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
    }
    fun save() {
        val d = draft ?: return
        if (!canSave) return
        val c = calendar ?: return
        viewModel.save(d, isTask, c, importance, people) { link ->
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
            Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
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
                        calendar?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CalendarRow(it, reason, calendars) { picked -> calendarChoice = picked.id }
                                Spacer(Modifier.weight(1f))
                                ImportanceMenu(importance) { picked -> importanceChoice = picked?.ordinal ?: IMPORTANCE_NONE }
                            }
                        }
                    }
                    Preview.Problem -> PreviewRow(
                        Icons.Outlined.Info,
                        (result as? SmartEventResult.NeedsDetails)?.message.orEmpty(),
                        muted = true,
                    )
                }
            }

            // Only shown once the title mentions someone ("with Sam and Dario") or people were added.
            if (draft != null && hasPeople(draft.title, people)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Group, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    PeopleEditor(draft.title, people) { peopleJson = Converters().peopleToString(it) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { closeThen { onMoreOptions(input.trim(), isTask, calendar?.id, Converters().peopleToString(people)) } }) { Text("More options") }
                Spacer(Modifier.weight(1f))
                Button(onClick = ::save, enabled = canSave) { Text("Save") }
            }
        }
    }
}

private enum class Preview { Examples, Draft, Problem }

/**
 * A dropdown menu that opens above its anchor instead of below it. It isn't focusable, because a
 * focusable popup would take focus from the text field and close the keyboard.
 */
@Composable
private fun PopUpMenu(expanded: Boolean, onDismiss: () -> Unit, alignEnd: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    if (!expanded) return
    val margin = with(LocalDensity.current) { 24.dp.roundToPx() }
    val position = remember(alignEnd, margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = if (alignEnd) anchorBounds.right - popupContentSize.width else anchorBounds.left
                return IntOffset(
                    x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)),
                    (anchorBounds.top - popupContentSize.height).coerceAtLeast(0),
                )
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Surface(
            shape = MenuDefaults.shape,
            color = MenuDefaults.containerColor,
            tonalElevation = MenuDefaults.TonalElevation,
            shadowElevation = MenuDefaults.ShadowElevation,
        ) {
            Column(Modifier.width(160.dp).padding(vertical = 8.dp), content = content)
        }
    }
}

private const val IMPORTANCE_UNSET = -2
private const val IMPORTANCE_NONE = -1

/** A compact chip on the calendar row showing the importance, with a menu to change it. */
@Composable
private fun ImportanceMenu(importance: Importance?, onPick: (Importance?) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClickLabel = "Change importance") { menuOpen = true }
                .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
        ) {
            if (importance != null) ColorDot(Color(importance.argb), size = 10.dp)
            else Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            Text(importance?.label ?: "No priority", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PopUpMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
            (listOf<Importance?>(null) + Importance.entries).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option?.label ?: "None") },
                    leadingIcon = { if (option != null) ColorDot(Color(option.argb), size = 14.dp) else Icon(Icons.Outlined.Flag, contentDescription = null) },
                    trailingIcon = if (option == importance) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = { menuOpen = false; onPick(option) },
                )
            }
        }
    }
}

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
        PopUpMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, alignEnd = true) {
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
