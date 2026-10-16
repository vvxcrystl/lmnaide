package dev.lmnaide.calendar.reminders

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.AlarmClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import dev.lmnaide.calendar.data.CalendarRepository
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.domain.Occurrence
import dev.lmnaide.calendar.domain.RecurrenceExpander
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ClockAlarmResult { REQUESTED, ALREADY_REQUESTED, TOO_EARLY, PAST, INELIGIBLE, UNAVAILABLE, UNSUPPORTED_TIME }

/** Foreground-only handoff to the user's Clock app. The receiving app owns the alarm afterwards. */
class ClockAlarmController(context: Context, private val repository: CalendarRepository) {
    private val prefs = context.getSharedPreferences("clock_alarms", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    fun plan(occurrence: Occurrence): ClockAlarmPlan = ClockAlarmPolicy.plan(occurrence)

    suspend fun request(context: Context, eventId: Long, instanceId: Long): ClockAlarmResult = mutex.withLock {
        // Re-read the event so stale notification actions cannot recreate a deleted/completed alarm.
        val event = repository.getEvent(eventId) ?: return@withLock ClockAlarmResult.INELIGIBLE
        val zone = ZoneId.systemDefault()
        val date = if (event.recurrence == Recurrence.NONE) event.localStart(zone).toLocalDate()
        else if (instanceId != 0L) Occurrence.instanceStart(instanceId).toLocalDate() else LocalDate.now(zone)
        val occurrence = RecurrenceExpander.expand(event, 0, "", date, date, zone)
            .firstOrNull { event.recurrence == Recurrence.NONE || instanceId == 0L || it.instanceId == instanceId }
            ?: return@withLock ClockAlarmResult.INELIGIBLE
        val plan = plan(occurrence)
        when (plan.status) {
            ClockAlarmStatus.TOO_EARLY -> return@withLock ClockAlarmResult.TOO_EARLY
            ClockAlarmStatus.PAST -> return@withLock ClockAlarmResult.PAST
            ClockAlarmStatus.INELIGIBLE -> return@withLock ClockAlarmResult.INELIGIBLE
            ClockAlarmStatus.UNSUPPORTED_TIME -> return@withLock ClockAlarmResult.UNSUPPORTED_TIME
            ClockAlarmStatus.READY -> Unit
        }
        val fingerprint = fingerprint(occurrence, zone)
        if (fingerprint in records(REQUESTED)) return@withLock ClockAlarmResult.ALREADY_REQUESTED
        val at = checkNotNull(plan.alarmAt)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, at.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, at.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label(occurrence))
            .putExtra(AlarmClock.EXTRA_VIBRATE, true)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        try {
            val launched = withContext(Dispatchers.Main.immediate) {
                // Reading Room may suspend while the user leaves. A blocked background start can
                // fail silently, so do not record it as a successful handoff.
                val owner = lifecycleOwner(context)
                if (owner != null && !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    false
                } else {
                    context.startActivity(intent)
                    true
                }
            }
            if (!launched) return@withLock ClockAlarmResult.UNAVAILABLE
        } catch (_: ActivityNotFoundException) {
            return@withLock ClockAlarmResult.UNAVAILABLE
        } catch (_: SecurityException) {
            return@withLock ClockAlarmResult.UNAVAILABLE
        }
        // This records a request, not a confirmation: ACTION_SET_ALARM has no success result.
        remember(REQUESTED, fingerprint)
        NotificationManagerCompat.from(context).cancel("clock_setup", fingerprint.hashCode())
        ClockAlarmResult.REQUESTED
    }

    internal fun needsSetupPrompt(occurrence: Occurrence, zone: ZoneId): Boolean {
        val fingerprint = fingerprint(occurrence, zone)
        return fingerprint !in records(REQUESTED) && fingerprint !in records(PROMPTED)
    }

    internal fun markPrompted(occurrence: Occurrence, zone: ZoneId) = remember(PROMPTED, fingerprint(occurrence, zone))

    internal fun wasRequested(occurrence: Occurrence, zone: ZoneId): Boolean = fingerprint(occurrence, zone) in records(REQUESTED)

    internal fun fingerprint(occurrence: Occurrence, zone: ZoneId): String {
        val at = ClockAlarmPolicy.alarmAt(occurrence, zone).toInstant().toEpochMilli()
        val value = "${occurrence.key}|$at|${label(occurrence)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "$at|$digest"
    }

    private fun label(occurrence: Occurrence) = "${occurrence.event.title.ifBlank { "(No title)" }} — 30 min before (${occurrence.startDate})"

    private tailrec fun lifecycleOwner(context: Context): LifecycleOwner? = when (context) {
        is LifecycleOwner -> context
        is ContextWrapper -> if (context.baseContext !== context) lifecycleOwner(context.baseContext) else null
        else -> null
    }

    private fun records(key: String): Set<String> = prefs.getStringSet(key, emptySet()).orEmpty()

    private fun remember(key: String, value: String) {
        val now = Instant.now().toEpochMilli()
        prefs.edit { putStringSet(key, records(key).filter { (it.substringBefore('|').toLongOrNull() ?: 0) > now }.toSet() + value) }
    }

    private companion object {
        const val REQUESTED = "requested"
        const val PROMPTED = "prompted"
    }
}
