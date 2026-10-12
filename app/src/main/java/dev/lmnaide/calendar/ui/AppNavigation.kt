package dev.lmnaide.calendar.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.lmnaide.calendar.ui.calendars.ManageCalendarsScreen
import dev.lmnaide.calendar.ui.common.Motion
import dev.lmnaide.calendar.ui.event.EventDetailScreen
import dev.lmnaide.calendar.ui.event.EventEditorScreen
import dev.lmnaide.calendar.ui.main.MainScreen
import dev.lmnaide.calendar.ui.search.SearchScreen
import dev.lmnaide.calendar.ui.settings.SettingsScreen
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** Points at one instance of an event; [instanceId] is ignored for non-recurring events. */
data class EventLink(val eventId: Long, val instanceId: Long)

object Routes {
    const val MAIN = "main"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val CALENDARS = "calendars"
    const val EVENT = "event/{eventId}/{instance}"
    const val EDIT = "edit?eventId={eventId}&instance={instance}&date={date}&minute={minute}&allDay={allDay}&task={task}&text={text}&calendar={calendar}&people={people}"

    const val NO_VALUE = Long.MIN_VALUE

    fun event(link: EventLink) = "event/${link.eventId}/${link.instanceId}"

    fun editEvent(link: EventLink) = "edit?eventId=${link.eventId}&instance=${link.instanceId}"

    /**
     * [minute] is minutes after midnight, or -1 for the next full hour. [text] is a quick add
     * sentence to fill the editor from, and [calendarId] the calendar it picked.
     */
    fun newEvent(date: LocalDate, minute: Int = -1, allDay: Boolean = false, task: Boolean = false, text: String = "", calendarId: Long? = null, people: String = "[]") =
        "edit?date=${date.toEpochDay()}&minute=$minute&allDay=$allDay&task=$task&text=${Uri.encode(text)}&calendar=${calendarId ?: NO_VALUE}&people=${Uri.encode(people)}"
}

/**
 * Leaves [entry]'s screen, but only while it is the resumed one. A second tap on a close button
 * during the exit animation would otherwise pop the main screen too and leave a blank app.
 */
private fun NavController.popFrom(entry: NavBackStackEntry): Boolean =
    entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && popBackStack()

/** Event details and the editor open like full-screen dialogs, rising over the screen they came from. */
private fun NavBackStackEntry.rises() = destination.route == Routes.EVENT || destination.route == Routes.EDIT

private fun riseIn() = slideInVertically(Motion.enter(Motion.LONG)) { it / 8 } + fadeIn(Motion.enter(Motion.MEDIUM))

private fun sinkOut() = slideOutVertically(Motion.exit(Motion.MEDIUM)) { it / 8 } + fadeOut(Motion.exit(Motion.MEDIUM))

/**
 * Other screens slide in from the side they lead to, a tenth of the way. The old screen fades out
 * before the new one fades in, so their text never overlaps.
 */
private fun slideIn(forward: Boolean) =
    slideInHorizontally(Motion.enter(Motion.LONG)) { if (forward) it / 10 else -it / 10 } + fadeIn(Motion.enter(Motion.MEDIUM - 90, delay = 90))

private fun slideOut(forward: Boolean) =
    slideOutHorizontally(Motion.exit(Motion.MEDIUM)) { if (forward) -it / 10 else it / 10 } + fadeOut(Motion.exit(90))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (targetState.rises()) riseIn() else slideIn(forward = true)

// The screen underneath stays put while a dialog-style screen rises over it.
private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    if (targetState.rises()) ExitTransition.KeepUntilTransitionsFinished else slideOut(forward = true)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    if (initialState.rises()) EnterTransition.None else slideIn(forward = false)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (initialState.rises()) sinkOut() else slideOut(forward = false)

@Composable
fun CalendarNavHost(pendingLink: StateFlow<EventLink?>, onLinkHandled: () -> Unit, pendingEditor: StateFlow<String?>, onEditorHandled: () -> Unit) {
    val nav = rememberNavController()
    val editor by pendingEditor.collectAsStateWithLifecycle()
    LaunchedEffect(editor) {
        editor?.let {
            nav.navigate(it) { popUpTo(Routes.MAIN) }
            onEditorHandled()
        }
    }
    val link by pendingLink.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        link?.let {
            nav.navigate(Routes.event(it)) { popUpTo(Routes.MAIN) }
            onLinkHandled()
        }
    }

    NavHost(
        navController = nav,
        startDestination = Routes.MAIN,
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
    ) {
        composable(Routes.MAIN) {
            MainScreen(
                onOpenEvent = { nav.navigate(Routes.event(it)) },
                onCreateEvent = { date, minute, allDay, task -> nav.navigate(Routes.newEvent(date, minute, allDay, task)) },
                onMoreOptions = { date, task, text, calendarId, people -> nav.navigate(Routes.newEvent(date, task = task, text = text, calendarId = calendarId, people = people)) },
                onSearch = { nav.navigate(Routes.SEARCH) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onManageCalendars = { nav.navigate(Routes.CALENDARS) },
            )
        }
        composable(
            Routes.EVENT,
            arguments = listOf(
                navArgument("eventId") { type = NavType.LongType },
                navArgument("instance") { type = NavType.LongType },
            ),
        ) { entry ->
            EventDetailScreen(
                onBack = { nav.popFrom(entry) },
                onEdit = { nav.navigate(Routes.editEvent(it)) },
                onOpenEvent = { nav.navigate(Routes.event(it)) { popUpTo(Routes.MAIN) } },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(
                navArgument("eventId") { type = NavType.LongType; defaultValue = 0L },
                navArgument("instance") { type = NavType.LongType; defaultValue = Routes.NO_VALUE },
                navArgument("date") { type = NavType.LongType; defaultValue = Routes.NO_VALUE },
                navArgument("minute") { type = NavType.IntType; defaultValue = -1 },
                navArgument("allDay") { type = NavType.BoolType; defaultValue = false },
                navArgument("task") { type = NavType.BoolType; defaultValue = false },
                navArgument("people") { type = NavType.StringType; defaultValue = "[]" },
                navArgument("text") { type = NavType.StringType; defaultValue = "" },
                navArgument("calendar") { type = NavType.LongType; defaultValue = Routes.NO_VALUE },
            ),
        ) { entry ->
            EventEditorScreen(
                onClose = { nav.popFrom(entry) },
                onSaved = { saved ->
                    if (!nav.popFrom(entry)) return@EventEditorScreen
                    // After editing from the event page, show the (possibly new) saved event there.
                    if (nav.currentDestination?.route == Routes.EVENT) {
                        nav.navigate(Routes.event(saved)) { popUpTo(Routes.MAIN) }
                    }
                },
            )
        }
        composable(Routes.SEARCH) { entry ->
            SearchScreen(onBack = { nav.popFrom(entry) }, onOpenEvent = { nav.navigate(Routes.event(it)) })
        }
        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(onBack = { nav.popFrom(entry) }, onManageCalendars = { nav.navigate(Routes.CALENDARS) })
        }
        composable(Routes.CALENDARS) { entry ->
            ManageCalendarsScreen(onBack = { nav.popFrom(entry) })
        }
    }
}
