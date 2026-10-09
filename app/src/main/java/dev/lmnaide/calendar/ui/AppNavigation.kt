package dev.lmnaide.calendar.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.lmnaide.calendar.ui.calendars.ManageCalendarsScreen
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
    const val EDIT = "edit?eventId={eventId}&instance={instance}&date={date}&minute={minute}&allDay={allDay}"

    const val NO_VALUE = Long.MIN_VALUE

    fun event(link: EventLink) = "event/${link.eventId}/${link.instanceId}"

    fun editEvent(link: EventLink) = "edit?eventId=${link.eventId}&instance=${link.instanceId}"

    /** [minute] is minutes after midnight, or -1 for the next full hour. */
    fun newEvent(date: LocalDate, minute: Int = -1, allDay: Boolean = false) =
        "edit?date=${date.toEpochDay()}&minute=$minute&allDay=$allDay"
}

@Composable
fun CalendarNavHost(pendingLink: StateFlow<EventLink?>, onLinkHandled: () -> Unit) {
    val nav = rememberNavController()
    val link by pendingLink.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        link?.let {
            nav.navigate(Routes.event(it)) { popUpTo(Routes.MAIN) }
            onLinkHandled()
        }
    }

    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                onOpenEvent = { nav.navigate(Routes.event(it)) },
                onCreateEvent = { date, minute, allDay -> nav.navigate(Routes.newEvent(date, minute, allDay)) },
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
        ) {
            EventDetailScreen(
                onBack = { nav.popBackStack() },
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
            ),
        ) {
            EventEditorScreen(
                onClose = { nav.popBackStack() },
                onSaved = { saved ->
                    nav.popBackStack()
                    // After editing from the event page, show the (possibly new) saved event there.
                    if (nav.currentDestination?.route == Routes.EVENT) {
                        nav.navigate(Routes.event(saved)) { popUpTo(Routes.MAIN) }
                    }
                },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(onBack = { nav.popBackStack() }, onOpenEvent = { nav.navigate(Routes.event(it)) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onManageCalendars = { nav.navigate(Routes.CALENDARS) })
        }
        composable(Routes.CALENDARS) {
            ManageCalendarsScreen(onBack = { nav.popBackStack() })
        }
    }
}
