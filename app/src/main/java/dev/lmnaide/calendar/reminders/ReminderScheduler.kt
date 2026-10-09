package dev.lmnaide.calendar.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.getSystemService
import dev.lmnaide.calendar.MainActivity
import dev.lmnaide.calendar.R
import dev.lmnaide.calendar.data.CalendarRepository
import dev.lmnaide.calendar.data.SettingsRepository
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.domain.RecurrenceExpander
import dev.lmnaide.calendar.ui.common.Fmt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Schedules a single exact alarm for the next due reminder. When it fires, every reminder that
 * came due since the last run is posted and the following alarm is scheduled.
 */
class ReminderScheduler(
    private val context: Context,
    private val repository: CalendarRepository,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService<AlarmManager>()!!

    private data class Trigger(val occurrence: Occurrence, val minutes: Int, val at: Long)

    suspend fun reschedule() = mutex.withLock { scheduleNextLocked() }

    suspend fun fireDue() = mutex.withLock {
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(KEY_LAST_RUN, now)
        val from = maxOf(lastRun, now - TimeUnit.MINUTES.toMillis(15))
        triggersBetween(from, now).forEach(::notify)
        prefs.edit { putLong(KEY_LAST_RUN, now) }
        scheduleNextLocked()
    }

    private suspend fun scheduleNextLocked() {
        val now = System.currentTimeMillis()
        if (!prefs.contains(KEY_LAST_RUN)) prefs.edit { putLong(KEY_LAST_RUN, now) }
        // Look ahead a few weeks; if nothing is due, wake up later to look again.
        val next = triggersBetween(now, now + LOOKAHEAD).minOfOrNull { it.at } ?: (now + LOOKAHEAD)
        val pending = alarmIntent()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
    }

    /** Reminders due in the half-open interval (from, to]. */
    private suspend fun triggersBetween(from: Long, to: Long): List<Trigger> {
        val zone = ZoneId.systemDefault()
        val firstDay = Instant.ofEpochMilli(from).atZone(zone).toLocalDate().minusDays(1)
        // Reminders can be up to a week ahead of the event.
        val lastDay = Instant.ofEpochMilli(to).atZone(zone).toLocalDate().plusDays(8)
        return repository.getAllEvents()
            .filter { it.reminders.isNotEmpty() }
            .flatMap { event -> RecurrenceExpander.expand(event, 0, "", firstDay, lastDay, zone) }
            .flatMap { occurrence ->
                val anchor = if (occurrence.allDay) occurrence.start.with(ALL_DAY_TIME) else occurrence.start
                val anchorMillis = anchor.atZone(zone).toInstant().toEpochMilli()
                occurrence.event.reminders.distinct().map { minutes ->
                    Trigger(occurrence, minutes, anchorMillis - TimeUnit.MINUTES.toMillis(minutes.toLong()))
                }
            }
            .filter { it.at in (from + 1)..to }
    }

    private fun notify(trigger: Trigger) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        ensureChannel()

        val occurrence = trigger.occurrence
        val event = occurrence.event
        val use24h = settings.settings.value.use24Hour
        val today = LocalDate.now()
        val day = when (occurrence.startDate) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> Fmt.dayMedium(occurrence.startDate, today)
        }
        val text = "$day, ${Fmt.occurrenceTime(occurrence, use24h)}"
        val id = occurrence.key.hashCode()
        val open = PendingIntent.getActivity(
            context,
            id,
            MainActivity.eventIntent(context, event.id, occurrence.instanceId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title.ifBlank { "(No title)" })
            .setContentText(text)
            .setSubText(event.location.takeIf { it.isNotBlank() })
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Event reminders", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService<NotificationManager>()!!.createNotificationChannel(channel)
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL_ID = "reminders"
        const val KEY_LAST_RUN = "last_run"
        val LOOKAHEAD = TimeUnit.DAYS.toMillis(21)
        val ALL_DAY_TIME: LocalTime = LocalTime.of(9, 0)
    }
}
