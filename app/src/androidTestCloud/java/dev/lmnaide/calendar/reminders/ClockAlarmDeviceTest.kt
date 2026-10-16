package dev.lmnaide.calendar.reminders

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.app.NotificationManager
import android.app.Notification
import android.provider.AlarmClock
import androidx.room.Room
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmnaide.calendar.data.AppDatabase
import dev.lmnaide.calendar.data.CalendarRepository
import dev.lmnaide.calendar.data.EventEntity
import dev.lmnaide.calendar.data.EventKind
import dev.lmnaide.calendar.data.Importance
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.data.SettingsRepository
import dev.lmnaide.calendar.domain.Occurrence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Uses an isolated database and captures the native handoff without modifying the device's Clock. */
@RunWith(AndroidJUnit4::class)
class ClockAlarmDeviceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var repo: CalendarRepository
    private lateinit var context: CapturingContext
    private lateinit var controller: ClockAlarmController
    private var calendarId = 0L

    private class CapturingContext(base: Context) : ContextWrapper(base) {
        private val prefix = "clock-test-${UUID.randomUUID()}-"
        val launches = mutableListOf<Intent>()
        var unavailable = false
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name, mode)
        override fun startActivity(intent: Intent) {
            if (unavailable) throw ActivityNotFoundException("Test device has no Clock handler")
            launches += Intent(intent)
        }
    }

    @Before fun setUp() = runBlocking {
        context = CapturingContext(InstrumentationRegistry.getInstrumentation().targetContext)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = CalendarRepository({ db }, scope)
        repo.ensureDefaults()
        calendarId = repo.getAllCalendars().first().id
        controller = ClockAlarmController(context, repo)
    }

    @After fun tearDown() {
        scope.cancel()
        db.close()
    }

    private suspend fun event(start: LocalDateTime = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0)): EventEntity {
        val draft = EventEntity(calendarId = calendarId, title = "Clock integration", importance = Importance.HIGH,
            start = EventEntity.toStored(start, false), end = EventEntity.toStored(start.plusHours(1), false))
        return draft.copy(id = repo.saveEvent(draft))
    }

    private fun EventEntity.instance() = localStart().toEpochSecond(ZoneOffset.UTC)

    @Test fun nativeIntentContainsThirtyMinuteTimeAndDeduplicatesAcrossControllerRestart() = runBlocking {
        val event = event()
        assertEquals(ClockAlarmResult.REQUESTED, controller.request(context, event.id, event.instance()))
        val intent = context.launches.single()
        val at = event.localStart().minusMinutes(30)
        assertEquals(AlarmClock.ACTION_SET_ALARM, intent.action)
        assertEquals(at.hour, intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(at.minute, intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertTrue(intent.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, false))
        assertFalse(intent.hasExtra(AlarmClock.EXTRA_DAYS))
        assertTrue(intent.getStringExtra(AlarmClock.EXTRA_MESSAGE)!!.contains(event.title))
        assertEquals(ClockAlarmResult.ALREADY_REQUESTED,
            ClockAlarmController(context, repo).request(context, event.id, event.instance()))
        assertEquals(1, context.launches.size)
    }

    @Test fun unavailableClockCanBeRetriedAndDoesNotRecordSuccess() = runBlocking {
        val event = event()
        context.unavailable = true
        assertEquals(ClockAlarmResult.UNAVAILABLE, controller.request(context, event.id, event.instance()))
        context.unavailable = false
        assertEquals(ClockAlarmResult.REQUESTED, controller.request(context, event.id, event.instance()))
    }

    @Test fun leavingActivityDuringLoadDoesNotRecordABlockedBackgroundLaunch() = runBlocking {
        val event = event()
        val paused = object : ContextWrapper(context), LifecycleOwner {
            override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this)
            override fun startActivity(intent: Intent) = error("Must not launch from a paused activity")
        }
        assertEquals(ClockAlarmResult.UNAVAILABLE, controller.request(paused, event.id, event.instance()))
        assertEquals(ClockAlarmResult.REQUESTED, controller.request(context, event.id, event.instance()))
    }

    @Test fun staleActionsRecheckDeletionPriorityAndTaskCompletion() = runBlocking {
        val event = event()
        repo.saveEvent(event.copy(importance = Importance.LOW))
        assertEquals(ClockAlarmResult.INELIGIBLE, controller.request(context, event.id, event.instance()))
        repo.saveEvent(event.copy(kind = EventKind.TASK, completions = listOf(event.localStart().toLocalDate().toEpochDay())))
        assertEquals(ClockAlarmResult.INELIGIBLE, controller.request(context, event.id, event.instance()))
        repo.deleteEvent(event)
        assertEquals(ClockAlarmResult.INELIGIBLE, controller.request(context, event.id, event.instance()))
        assertTrue(context.launches.isEmpty())
    }

    @Test fun laterDatesAndPastTimesNeverLaunchClock() = runBlocking {
        val future = event(LocalDateTime.now().plusDays(5))
        assertEquals(ClockAlarmResult.TOO_EARLY, controller.request(context, future.id, future.instance()))
        val past = event(LocalDateTime.now().minusHours(1))
        assertEquals(ClockAlarmResult.PAST, controller.request(context, past.id, past.instance()))
        assertTrue(context.launches.isEmpty())
    }

    @Test fun skippedOrMovedRecurringOccurrenceCannotBeRecreatedFromAnOldNotification() = runBlocking {
        val event = event().copy(recurrence = Recurrence.DAILY)
        repo.saveEvent(event.copy(exceptions = listOf(event.localStart().toLocalDate().toEpochDay())))
        assertEquals(ClockAlarmResult.INELIGIBLE, controller.request(context, event.id, event.instance()))
        val shifted = event.copy(start = event.start + 3_600_000, end = event.end + 3_600_000)
        repo.saveEvent(shifted)
        assertEquals(ClockAlarmResult.INELIGIBLE, controller.request(context, event.id, event.instance()))
        assertTrue(context.launches.isEmpty())
    }

    @Test fun changingTimeCreatesANewRequestButChangingNotesDoesNot() = runBlocking {
        val event = event()
        assertEquals(ClockAlarmResult.REQUESTED, controller.request(context, event.id, event.instance()))
        repo.saveEvent(event.copy(description = "Updated notes"))
        assertEquals(ClockAlarmResult.ALREADY_REQUESTED, controller.request(context, event.id, event.instance()))
        val shifted = event.copy(start = event.start + 3_600_000, end = event.end + 3_600_000)
        repo.saveEvent(shifted)
        assertEquals(ClockAlarmResult.REQUESTED, controller.request(context, event.id, shifted.instance()))
        assertEquals(2, context.launches.size)
    }

    @Test fun highItemWithoutConfiguredRemindersStillPostsThirtyMinuteReminder() = runBlocking {
        val event = event(LocalDateTime.now().plusMinutes(30).minusSeconds(5))
        context.getSharedPreferences("reminders", 0).edit()
            .putLong("last_run", System.currentTimeMillis() - 30_000).commit()
        val scheduler = ReminderScheduler(context, repo, SettingsRepository(context), controller)
        scheduler.fireDue()
        val manager = context.getSystemService(NotificationManager::class.java)
        val key = "${event.id}@${event.instance()}"
        val posted = manager.activeNotifications.single { it.tag == null && it.id == key.hashCode() }
        assertEquals("High importance: ${event.title}", posted.notification.extras.getString(Notification.EXTRA_TITLE))
        manager.cancel(key.hashCode())
    }

    @Test fun setupPromptIsDeduplicatedAndRemovedWhenEventIsDeleted() = runBlocking {
        val event = event()
        val scheduler = ReminderScheduler(context, repo, SettingsRepository(context), controller)
        val manager = context.getSystemService(NotificationManager::class.java)
        scheduler.reschedule()
        val posted = manager.activeNotifications.single { it.tag == "clock_setup" }
        assertTrue(posted.notification.extras.getString(Notification.EXTRA_TITLE)!!.contains(event.title))
        scheduler.reschedule()
        assertEquals(posted.postTime, manager.activeNotifications.single { it.tag == "clock_setup" }.postTime)
        repo.deleteEvent(event)
        scheduler.reschedule()
        assertFalse(manager.activeNotifications.any { it.tag == "clock_setup" })
    }
}
