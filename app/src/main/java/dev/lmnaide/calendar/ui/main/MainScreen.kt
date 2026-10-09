package dev.lmnaide.calendar.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import dev.lmnaide.calendar.ui.event.QuickAddSheet
import dev.lmnaide.calendar.ui.event.QuickAddViewModel
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
    onMoreOptions: (date: LocalDate, task: Boolean, text: String, calendarId: Long?) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onManageCalendars: () -> Unit,
    viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory),
    quickAddViewModel: QuickAddViewModel = viewModel(factory = QuickAddViewModel.Factory),
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

    var quickAddOpen by rememberSaveable { mutableStateOf(false) }
    val snackbars = remember { SnackbarHostState() }

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
        Box(Modifier.fillMaxSize()) {
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
                floatingActionButton = { CreateButton(onClick = { quickAddOpen = true }) },
                snackbarHost = { SnackbarHost(snackbars) },
            ) { padding ->
                // Content runs behind the navigation bar and fades out there instead of stopping above it.
                val layoutDirection = LocalLayoutDirection.current
                val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Column(
                    Modifier
                        .padding(
                            start = padding.calculateStartPadding(layoutDirection),
                            top = padding.calculateTopPadding(),
                            end = padding.calculateEndPadding(layoutDirection),
                        )
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
                            CalendarView.MONTH -> Box(Modifier.padding(bottom = navInset)) {
                                MonthView(
                                    selectedDate = selectedDate,
                                    weekStart = settings.weekStart,
                                    index = index,
                                    now = now,
                                    onSelectDate = viewModel::selectDate,
                                    onVisibleRange = { visibleRange = it },
                                    onOpenDay = openDay,
                                )
                            }
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
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(navInset + 28.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                                    ),
                                ),
                        )
                    }
                }
            }
            if (quickAddOpen) {
                // A time without a day goes on the day being viewed, unless that day is already past.
                QuickAddSheet(
                    defaultDate = maxOf(selectedDate, today),
                    onDismiss = { quickAddOpen = false },
                    onSaved = { link, message, date ->
                        quickAddOpen = false
                        jumpTo(date)
                        scope.launch {
                            val result = snackbars.showSnackbar(message, actionLabel = "View", duration = SnackbarDuration.Short)
                            if (result == SnackbarResult.ActionPerformed) onOpenEvent(link)
                        }
                    },
                    onMoreOptions = { text, task, calendarId ->
                        quickAddOpen = false
                        onMoreOptions(maxOf(selectedDate, today), task, text, calendarId)
                    },
                    viewModel = quickAddViewModel,
                )
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

/** The create button, with Google's four-color plus. It opens quick add, which can also lead to the full editor. */
@Composable
private fun CreateButton(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        GooglePlus(Modifier.size(24.dp))
    }
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
