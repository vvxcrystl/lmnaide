package dev.lmnaide.calendar.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lmnaide.calendar.container
import kotlinx.coroutines.launch

/** Receives reminder alarms, and reschedules after reboots or clock changes. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (intent.action == ACTION_FIRE) container.reminders.fireDue() else container.reminders.reschedule()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.lmnaide.calendar.action.REMINDER"
    }
}
