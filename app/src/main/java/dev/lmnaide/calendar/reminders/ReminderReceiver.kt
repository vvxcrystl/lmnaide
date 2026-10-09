package dev.lmnaide.calendar.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.lmnaide.calendar.container
import dev.lmnaide.calendar.domain.Occurrence
import kotlinx.coroutines.launch

/** Receives reminder alarms and notification actions, and reschedules after reboots or clock changes. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = context.container.reminders
        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, 0L)
        val instanceId = intent.getLongExtra(EXTRA_INSTANCE, 0L)
        val pending = goAsync()
        context.container.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> reminders.fireDue()
                    ACTION_ACKNOWLEDGE -> reminders.acknowledge(key)
                    ACTION_SNOOZE -> reminders.snooze(key, eventId, instanceId)
                    ACTION_COMPLETE ->
                        reminders.complete(key, eventId, Occurrence.instanceStart(instanceId).toLocalDate())
                    else -> reminders.reschedule()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.lmnaide.calendar.action.REMINDER"
        const val ACTION_ACKNOWLEDGE = "dev.lmnaide.calendar.action.ACKNOWLEDGE"
        const val ACTION_SNOOZE = "dev.lmnaide.calendar.action.SNOOZE"
        const val ACTION_COMPLETE = "dev.lmnaide.calendar.action.COMPLETE"
        const val EXTRA_KEY = "key"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_INSTANCE = "instance"
    }
}
