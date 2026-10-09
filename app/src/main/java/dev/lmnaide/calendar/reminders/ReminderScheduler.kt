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
import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.data.Importance
import dev.lmnaide.calendar.data.SettingsRepository
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.domain.RecurrenceExpander
import dev.lmnaide.calendar.ui.common.Fmt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Schedules a single exact alarm for the next due reminder. When it fires, every reminder that
 * came due since the last run is posted and the following alarm is scheduled.
 *
 * Importance changes how reminders behave:
 * - Low: posted silently, without a heads-up.
 * - Medium (and no importance): a normal alert with a snooze action.
 * - High: an urgent alert with strong vibration, an extra alert when the item starts, and repeats
 *   every [NAG_INTERVAL_MINUTES] minutes until acknowledged.
 */
class ReminderScheduler(
    private val context: Context,
    private val repository: CalendarRepository,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val alarmManager = context.getSystemService<AlarmManager>()!!
    private val notifications = NotificationManagerCompat.from(context)

    private enum class Reason { REMINDER, STARTING, REPEAT, SNOOZE }

    private data class Trigger(val occurrence: Occurrence, val at: Long, val reason: Reason, val minutes: Int = 0)

    suspend fun reschedule() = mutex.withLock { scheduleNextLocked() }

    suspend fun fireDue() = mutex.withLock {
        val now = System.currentTimeMillis()
        val lastRun = prefs.getLong(KEY_LAST_RUN, now)
        val from = maxOf(lastRun, now - TimeUnit.MINUTES.toMillis(15))
        // One notification per occurrence: the latest trigger wins.
        triggersBetween(from, now)
            .groupBy { it.occurrence.key }
            .values
            .map { group -> group.maxBy { it.at } }
            .forEach(::notify)
        prefs.edit {
            putLong(KEY_LAST_RUN, now)
            putStringSet(KEY_SNOOZES, snoozes().filter { it.at > now - TimeUnit.DAYS.toMillis(1) }.map { it.encode() }.toSet())
            putStringSet(KEY_ACKNOWLEDGED, acknowledged().filter { keyInstant(it) > now - TimeUnit.DAYS.toMillis(2) }.toSet())
        }
        scheduleNextLocked()
    }

    /** Stops repeat alerts for an occurrence and clears its notification. */
    suspend fun acknowledge(key: String) = mutex.withLock {
        prefs.edit { putStringSet(KEY_ACKNOWLEDGED, acknowledged() + key) }
        notifications.cancel(key.hashCode())
        scheduleNextLocked()
    }

    suspend fun snooze(key: String, eventId: Long, instanceId: Long, minutes: Int = SNOOZE_MINUTES) = mutex.withLock {
        val at = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())
        prefs.edit {
            putStringSet(KEY_SNOOZES, snoozes().map { it.encode() }.toSet() + Snooze(eventId, instanceId, at).encode())
            putStringSet(KEY_ACKNOWLEDGED, acknowledged() + key)
        }
        notifications.cancel(key.hashCode())
        scheduleNextLocked()
    }

    suspend fun complete(key: String, eventId: Long, date: LocalDate) {
        repository.setCompleted(eventId, date, done = true)
        acknowledge(key)
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
        val events = repository.getAllEvents()
        val acknowledged = acknowledged()

        val scheduled = events
            .filter { it.reminders.isNotEmpty() || it.importance == Importance.HIGH }
            .flatMap { event -> RecurrenceExpander.expand(event, 0, "", firstDay, lastDay, zone) }
            .filterNot { it.completed }
            .flatMap { occurrence ->
                val anchor = anchorMillis(occurrence, zone)
                val reminders = occurrence.event.reminders.distinct().map { minutes ->
                    Trigger(occurrence, anchor - TimeUnit.MINUTES.toMillis(minutes.toLong()), Reason.REMINDER, minutes)
                }
                val urgent = if (occurrence.event.importance == Importance.HIGH && occurrence.key !in acknowledged) {
                    listOf(Trigger(occurrence, anchor, Reason.STARTING)) +
                        (1..NAG_COUNT).map { n ->
                            Trigger(occurrence, anchor + TimeUnit.MINUTES.toMillis(n * NAG_INTERVAL_MINUTES), Reason.REPEAT)
                        }
                } else {
                    emptyList()
                }
                reminders + urgent
            }

        val eventsById = events.associateBy { it.id }
        val snoozed = snoozes().mapNotNull { snooze ->
            val event = eventsById[snooze.eventId] ?: return@mapNotNull null
            occurrenceOf(event, snooze.instanceId, zone)?.let { Trigger(it, snooze.at, Reason.SNOOZE) }
        }
        return (scheduled + snoozed).filter { it.at in (from + 1)..to }
    }

    private fun anchorMillis(occurrence: Occurrence, zone: ZoneId): Long {
        val anchor = if (occurrence.allDay) occurrence.start.with(ALL_DAY_TIME) else occurrence.start
        return anchor.atZone(zone).toInstant().toEpochMilli()
    }

    private fun occurrenceOf(event: EventEntity, instanceId: Long, zone: ZoneId): Occurrence? {
        val start = Occurrence.instanceStart(instanceId)
        val length = Duration.between(event.localStart(zone), event.localEnd(zone))
        return Occurrence(event, start, start.plus(length), 0, "").takeUnless { it.completed }
    }

    private fun notify(trigger: Trigger) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        ensureChannels()

        val occurrence = trigger.occurrence
        val event = occurrence.event
        val importance = event.importance
        val use24h = settings.settings.value.use24Hour
        val today = LocalDate.now()
        val day = when (occurrence.startDate) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> Fmt.dayMedium(occurrence.startDate, today)
        }
        val time = if (event.isTask && !event.allDay) "due ${Fmt.time(occurrence.start.toLocalTime(), use24h)}"
        else Fmt.occurrenceTime(occurrence, use24h)
        val title = event.title.ifBlank { "(No title)" }
        val text = when (trigger.reason) {
            Reason.STARTING -> if (event.isTask) "Due now" else "Starting now"
            Reason.REPEAT -> when {
                !event.isTask -> "Started at ${Fmt.time(occurrence.start.toLocalTime(), use24h)}"
                event.allDay -> "Still not done"
                else -> "Still not done, was due at ${Fmt.time(occurrence.start.toLocalTime(), use24h)}"
            }
            else -> "$day, $time"
        }
        val key = occurrence.key
        val id = key.hashCode()

        val builder = NotificationCompat.Builder(context, channelFor(importance))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (importance == Importance.HIGH) "High importance: $title" else title)
            .setContentText(text)
            .setSubText(event.location.takeIf { it.isNotBlank() })
            .setCategory(if (event.isTask) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    id,
                    MainActivity.eventIntent(context, event.id, occurrence.instanceId, key),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)

        when (importance) {
            Importance.LOW -> builder
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
            Importance.HIGH -> builder
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setColor(Importance.HIGH.argb)
                .setVibrate(URGENT_VIBRATION)
                // Swiping it away doesn't stop the repeats; only "Got it", snoozing, opening or finishing does.
                .addAction(0, "Got it", action(ReminderReceiver.ACTION_ACKNOWLEDGE, occurrence, 1))
            else -> builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        }
        if (importance != Importance.LOW) {
            builder.addAction(0, "Snooze $SNOOZE_MINUTES min", action(ReminderReceiver.ACTION_SNOOZE, occurrence, 2))
        }
        if (event.isTask) {
            builder.addAction(0, "Mark done", action(ReminderReceiver.ACTION_COMPLETE, occurrence, 3))
        }
        notifications.notify(id, builder.build())
    }

    private fun action(action: String, occurrence: Occurrence, slot: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        occurrence.key.hashCode() * 4 + slot,
        Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderReceiver.EXTRA_KEY, occurrence.key)
            .putExtra(ReminderReceiver.EXTRA_EVENT_ID, occurrence.event.id)
            .putExtra(ReminderReceiver.EXTRA_INSTANCE, occurrence.instanceId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun channelFor(importance: Importance?) = when (importance) {
        Importance.LOW -> CHANNEL_LOW
        Importance.HIGH -> CHANNEL_HIGH
        else -> CHANNEL_MEDIUM
    }

    private fun ensureChannels() {
        val manager = context.getSystemService<NotificationManager>()!!
        manager.deleteNotificationChannel(LEGACY_CHANNEL)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_LOW, "Low importance", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Quiet reminders that wait in the notification shade"
                },
                NotificationChannel(CHANNEL_MEDIUM, "Medium importance", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Standard reminders with sound"
                },
                NotificationChannel(CHANNEL_HIGH, "High importance", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Urgent reminders that repeat until you respond"
                    enableVibration(true)
                    vibrationPattern = URGENT_VIBRATION
                    enableLights(true)
                    lightColor = Importance.HIGH.argb
                },
            ),
        )
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acknowledged(): Set<String> = prefs.getStringSet(KEY_ACKNOWLEDGED, emptySet()).orEmpty()

    private fun snoozes(): List<Snooze> = prefs.getStringSet(KEY_SNOOZES, emptySet()).orEmpty().mapNotNull(Snooze::decode)

    /** Occurrence keys end with the instance's floating epoch second. */
    private fun keyInstant(key: String): Long = key.substringAfter('@').toLongOrNull()?.times(1000) ?: 0L

    private data class Snooze(val eventId: Long, val instanceId: Long, val at: Long) {
        fun encode() = "$eventId|$instanceId|$at"

        companion object {
            fun decode(value: String): Snooze? = value.split('|').takeIf { it.size == 3 }?.let { (e, i, a) ->
                Snooze(e.toLongOrNull() ?: return null, i.toLongOrNull() ?: return null, a.toLongOrNull() ?: return null)
            }
        }
    }

    private companion object {
        const val LEGACY_CHANNEL = "reminders"
        const val CHANNEL_LOW = "importance_low"
        const val CHANNEL_MEDIUM = "importance_medium"
        const val CHANNEL_HIGH = "importance_high"
        const val KEY_LAST_RUN = "last_run"
        const val KEY_ACKNOWLEDGED = "acknowledged"
        const val KEY_SNOOZES = "snoozes"
        const val SNOOZE_MINUTES = 10
        const val NAG_INTERVAL_MINUTES = 5L
        const val NAG_COUNT = 3
        val LOOKAHEAD = TimeUnit.DAYS.toMillis(21)
        val ALL_DAY_TIME: LocalTime = LocalTime.of(9, 0)
        val URGENT_VIBRATION = longArrayOf(0, 600, 200, 600, 200, 600)
    }
}
