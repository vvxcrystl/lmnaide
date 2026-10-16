package dev.lmnaide.calendar.ui.event

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.lmnaide.calendar.container
import dev.lmnaide.calendar.reminders.ClockAlarmResult
import dev.lmnaide.calendar.ui.EventLink
import kotlinx.coroutines.launch

/**
 * Asks Clock for a high-importance item's alarm and returns what to tell the user, or null when
 * there's nothing worth saying. Low and medium items come back INELIGIBLE and stay silent. Clock
 * gives no result back, so REQUESTED means the request went out, not that an alarm exists.
 *
 * Call it while the app is in front. [edited] notes that an alarm from before an edit stays in Clock.
 */
suspend fun requestClockAlarm(context: Context, link: EventLink, edited: Boolean = false, manual: Boolean = false): String? =
    clockAlarmMessage(context.container.clockAlarms.request(context, link.eventId, link.instanceId), edited, manual)

/** After saving a formerly high-importance item at a lower level. Clock doesn't follow edits. */
const val CLOCK_ALARM_DROPPED_MESSAGE = "If Clock has an alarm for this event, delete it in Clock too."

/** Uses the application context, so the toast outlives an activity that finishes right after saving. */
fun showClockAlarmMessage(context: Context, message: String) {
    Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
}

/** [manual] is a tap on the event page's Clock button, which deserves an answer even when nothing changed. */
internal fun clockAlarmMessage(result: ClockAlarmResult, edited: Boolean, manual: Boolean): String? = when (result) {
    ClockAlarmResult.REQUESTED ->
        if (edited) "Sent a new alarm to Clock. Delete any older alarm for this event in Clock."
        else "Sent an alarm 30 min before to Clock. Check Clock to confirm it."
    ClockAlarmResult.ALREADY_REQUESTED -> if (manual) "Already sent to Clock. Check Clock for the alarm." else null
    ClockAlarmResult.TOO_EARLY ->
        "Too early for a Clock alarm. You'll get a notification to set it a day ahead, plus an in-app reminder 30 min before."
    ClockAlarmResult.PAST -> if (manual) "The alarm time has passed." else null
    ClockAlarmResult.INELIGIBLE -> null
    ClockAlarmResult.UNSUPPORTED_TIME ->
        "Clock can't safely use this time because of a clock change. The in-app reminder 30 min before still works."
    ClockAlarmResult.UNAVAILABLE -> "Couldn't reach a Clock app. You'll still get an in-app reminder 30 min before."
}

/**
 * Returns a function that asks Clock for an alarm after a save. It runs in the activity's scope,
 * so it keeps going when the screen that saved closes right away.
 */
@Composable
fun rememberClockAlarmRequester(): (link: EventLink, edited: Boolean) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        val activity = context.findActivity()
        val requester: (EventLink, Boolean) -> Unit = { link, edited ->
            activity?.lifecycleScope?.launch {
                requestClockAlarm(activity, link, edited)?.let { showClockAlarmMessage(activity, it) }
            }
        }
        requester
    }
}

/** One muted line under a High importance choice. The event page shows the alarm's actual time and status. */
@Composable
fun ClockAlarmNote(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            Icons.Outlined.Alarm,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp).size(14.dp),
        )
        Text(
            "Sends Clock an alarm 30 min before once it's within a day. Further out, a notification asks you to set it. " +
                "Clock won't follow later edits, so change or delete the alarm there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
