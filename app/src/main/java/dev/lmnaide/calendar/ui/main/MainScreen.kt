package dev.lmnaide.calendar.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lmnaide.calendar.data.CalendarView
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.ui.EventLink
import dev.lmnaide.calendar.ui.common.Fmt
import dev.lmnaide.calendar.ui.common.rememberNow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onOpenEvent: (EventLink) -> Unit,
    onCreateEvent: (date: LocalDate, minute: Int, allDay: Boolean, task: Boolean) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onManageCalendars: () -> Unit,
    viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val calendars by viewModel.calendars.collectAsStateWithLifecycle()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val now = rememberNow()
    val today = now.toLocalDate()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var visibleRange by remember { mutableStateOf(selectedDate..selectedDate) }
    // Bumped on explicit jumps so the schedule list re-centers even if the date didn't change.
    var jumpCount by rememberSaveable { mutableIntStateOf(0) }
    val jumpTo: (LocalDate) -> Unit = { date ->
        viewModel.selectDate(date)
        jumpCount++
        pickerOpen = false
    }

    var createMenuOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = createMenuOpen) { createMenuOpen = false }

    RequestNotificationPermission()
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = pickerOpen && !drawerState.isOpen) { pickerOpen = false }

    val openEvent: (Occurrence) -> Unit = { onOpenEvent(EventLink(it.event.id, it.instanceId)) }
    // Opening a day from another view switches to the day view; back returns to where you were.
    var returnView by rememberSaveable { mutableStateOf<CalendarView?>(null) }
    BackHandler(enabled = returnView != null && !drawerState.isOpen && !pickerOpen) {
        returnView?.let(viewModel::setView)
        returnView = null
    }
    val openDay: (LocalDate) -> Unit = { date ->
        if (settings.view != CalendarView.DAY) returnView = settings.view
        viewModel.selectDate(date)
        viewModel.setView(CalendarView.DAY)
    }
    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                currentView = settings.view,
                calendars = calendars,
                showTasks = settings.showTasks,
                onToggleTasks = viewModel::toggleTasks,
                onSelectView = { view ->
                    returnView = null
                    closeDrawerThen { viewModel.setView(view) }
                },
                onToggleCalendar = viewModel::toggleCalendar,
                onManageCalendars = { closeDrawerThen(onManageCalendars) },
                onSettings = { closeDrawerThen(onSettings) },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open menu")
                        }
                    },
                    title = {
                        val arrowRotation by animateFloatAsState(if (pickerOpen) 180f else 0f, label = "arrow")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { pickerOpen = !pickerOpen }
                                .padding(start = 4.dp, end = 2.dp),
                        ) {
                            Text(rangeTitle(visibleRange, today), maxLines = 1)
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = if (pickerOpen) "Hide month picker" else "Show month picker",
                                modifier = Modifier.rotate(arrowRotation),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onSearch) {
                            Icon(Icons.Outlined.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { jumpTo(today) }) {
                            TodayIcon(today)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            floatingActionButton = {
                CreateButton(
                    expanded = createMenuOpen,
                    onExpandedChange = { createMenuOpen = it },
                    onCreate = { task -> onCreateEvent(selectedDate, -1, task, task) },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                AnimatedVisibility(pickerOpen) {
                    Column {
                        MiniMonth(
                            selectedDate = selectedDate,
                            today = today,
                            weekStart = settings.weekStart,
                            index = index,
                            onSelect = jumpTo,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (val view = settings.view) {
                        CalendarView.SCHEDULE -> ScheduleView(
                            selectedDate = selectedDate,
                            jumpCount = jumpCount,
                            index = index,
                            use24h = settings.use24Hour,
                            now = now,
                            onVisibleRange = { visibleRange = it },
                            onOpenEvent = openEvent,
                            onOpenDay = openDay,
                            onToggleTask = { viewModel.setTaskCompleted(it, !it.completed) },
                        )
                        CalendarView.MONTH -> MonthView(
                            selectedDate = selectedDate,
                            weekStart = settings.weekStart,
                            index = index,
                            now = now,
                            onSelectDate = viewModel::selectDate,
                            onVisibleRange = { visibleRange = it },
                            onOpenDay = openDay,
                        )
                        // Paging depends on the period length and week start, so start fresh when they change.
                        else -> key(view, settings.weekStart) {
                            TimeGridView(
                                days = when (view) {
                                    CalendarView.DAY -> 1
                                    CalendarView.THREE_DAY -> 3
                                    else -> 7
                                },
                                selectedDate = selectedDate,
                                weekStart = settings.weekStart,
                                index = index,
                                use24h = settings.use24Hour,
                                now = now,
                                onSelectDate = viewModel::selectDate,
                                onVisibleRange = { visibleRange = it },
                                onOpenEvent = openEvent,
                                onOpenDay = openDay,
                                onCreateAt = { onCreateEvent(it.toLocalDate(), it.hour * 60 + it.minute, false, false) },
                            )
                        }
                    }
                    if (createMenuOpen) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                                .clickable(interactionSource = null, indication = null) { createMenuOpen = false },
                        )
                    }
                }
            }
        }
    }
}

/** "October", "September – October", or with years when outside the current one. */
private fun rangeTitle(range: ClosedRange<LocalDate>, today: LocalDate): String {
    val first = YearMonth.from(range.start)
    val last = YearMonth.from(range.endInclusive)
    return when {
        first == last -> Fmt.monthYear(first, today)
        first.year == last.year && first.year == today.year -> "${Fmt.monthShort(first)} – ${Fmt.monthShort(last)}"
        else -> "${Fmt.monthYearShort(first)} – ${Fmt.monthYearShort(last)}"
    }
}

/**
 * The create button. Like Google Calendar's, it opens a small menu to choose between an event and
 * a task; the scrim behind it closes the menu.
 */
@Composable
private fun CreateButton(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, onCreate: (task: Boolean) -> Unit) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom), exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CreateOption("Task", Icons.Outlined.TaskAlt) { onExpandedChange(false); onCreate(true) }
                CreateOption("Event", Icons.Outlined.Event) { onExpandedChange(false); onCreate(false) }
            }
        }
        FloatingActionButton(
            onClick = { onExpandedChange(!expanded) },
            shape = RoundedCornerShape(16.dp),
            containerColor = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            if (expanded) Icon(Icons.Default.Close, contentDescription = "Close menu") else GooglePlus(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CreateOption(label: String, icon: ImageVector, onClick: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(label) },
        shape = CircleShape,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.height(48.dp),
    )
}

/** Google's four-color plus, as on the Calendar app's create button. */
@Composable
private fun GooglePlus(modifier: Modifier = Modifier) {
    Canvas(modifier.semantics { contentDescription = "New event" }) {
        val arm = size.width * 0.17f
        val mid = size.width / 2
        val half = arm / 2
        // Top, right, bottom and left arms, each meeting at the center.
        drawRect(Color(0xFFEA4335), Offset(mid - half, 0f), Size(arm, mid + half))
        drawRect(Color(0xFF4285F4), Offset(mid - half, mid - half), Size(mid + half, arm))
        drawRect(Color(0xFF34A853), Offset(mid - half, mid - half), Size(arm, mid + half))
        drawRect(Color(0xFFFBBC04), Offset(0f, mid - half), Size(mid + half, arm))
    }
}

/** A small calendar glyph showing today's date, like the "jump to today" button in most calendars. */
@Composable
private fun TodayIcon(today: LocalDate) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(22.dp)
            .border(1.75.dp, color, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            today.dayOfMonth.toString(),
            fontSize = 11.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
