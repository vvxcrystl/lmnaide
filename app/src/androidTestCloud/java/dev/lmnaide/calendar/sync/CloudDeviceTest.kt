package dev.lmnaide.calendar.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmnaide.calendar.container
import dev.lmnaide.calendar.data.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run phases on different ADB devices to exercise the real Room + Keystore + HTTP path. */
@RunWith(AndroidJUnit4::class)
class CloudDeviceTest {
    @Test
    fun crossDevicePhase() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase =
            args.getString("phase")
                ?: error(
                    "Pass -e phase create|edit|verify|delete|verifyDeleted|queueOffline|verifyOffline"
                )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.container
        val repo = app.repository
        val run = args.getString("run") ?: "integration"
        val title = "Cloud integration $run"
        val calendarName = "Cloud integration $run"
        if (phase != "queueOffline") {
            app.cloud.connect(
                args.getString("server") ?: "http://127.0.0.1:3000",
                args.getString("email") ?: "device-test@example.com",
                args.getString("password") ?: "device-sync-test-2026",
                false,
            )
            assertTrue(app.cloud.state.value.message, app.cloud.state.value.connected)
            assertTrue(app.cloud.state.value.message, app.cloud.sync())
        }
        val today = LocalDate.now()
        when (phase) {
            "create" -> {
                repo.saveCalendar(
                    CalendarEntity(
                        name = calendarName,
                        color = -13388167,
                        keywords = "integration",
                        defaultImportance = Importance.HIGH,
                    )
                )
                val calendar = repo.getAllCalendars().single { it.name == calendarName }
                repo.saveEvent(
                    EventEntity(
                        calendarId = calendar.id,
                        title = title,
                        start = EventEntity.toStored(today.atStartOfDay(), true),
                        end = EventEntity.toStored(today.plusDays(1).atStartOfDay(), true),
                        allDay = true,
                        kind = EventKind.TASK,
                        recurrence = Recurrence.DAILY,
                        importance = Importance.HIGH,
                        reminders = listOf(10),
                        description = "Created on emulator one",
                    )
                )
                assertTrue(app.cloud.state.value.message, app.cloud.sync())
            }
            "edit" -> {
                val event = repo.getAllEvents().single { it.title == title }
                assertEquals(Recurrence.DAILY, event.recurrence)
                assertEquals(listOf(10), event.reminders)
                repo.saveEvent(
                    event.copy(description = "Edited on the real ADB phone", location = "Regina")
                )
                repo.setCompleted(event.id, today, true)
                repo.deleteOccurrence(repo.getEvent(event.id)!!, today.plusDays(1))
                assertTrue(app.cloud.state.value.message, app.cloud.sync())
            }
            "verify" -> {
                val event = repo.getAllEvents().single { it.title == title }
                assertEquals("Edited on the real ADB phone", event.description)
                assertEquals("Regina", event.location)
                assertEquals(listOf(today.toEpochDay()), event.completions)
                assertEquals(listOf(today.plusDays(1).toEpochDay()), event.exceptions)
                assertEquals(Importance.HIGH, event.importance)
                assertTrue(event.allDay)
                assertEquals(today, event.localStart().toLocalDate())
                assertEquals(1, repo.getAllCalendars().count { it.name == "Personal" })
                assertEquals(1, repo.getAllCalendars().count { it.name == "Holidays" })
            }
            "delete" -> {
                val calendar = repo.getAllCalendars().single { it.name == calendarName }
                repo.deleteCalendar(calendar)
                assertTrue(app.cloud.state.value.message, app.cloud.sync())
                assertFalse(repo.getAllEvents().any { it.title == title })
            }
            "verifyDeleted" -> {
                assertFalse(repo.getAllEvents().any { it.title == title })
                assertFalse(repo.getAllCalendars().any { it.name == calendarName })
            }
            "queueOffline" -> {
                assertTrue("Sign in before removing ADB reverse", app.cloud.state.value.connected)
                val calendar = repo.getAllCalendars().first { it.name == "Personal" }
                repo.saveEvent(
                    EventEntity(
                        calendarId = calendar.id,
                        title = "$title offline",
                        start = System.currentTimeMillis(),
                        end = System.currentTimeMillis() + 3600000,
                        description = "Saved without a server connection",
                    )
                )
                assertFalse("Sync should fail while ADB reverse is removed", app.cloud.sync())
                assertTrue(repo.getAllEvents().any { it.title == "$title offline" })
                repo.db.openHelper.readableDatabase
                    .query("SELECT count(*) FROM sync_meta WHERE dirty=1")
                    .use {
                        it.moveToFirst()
                        assertTrue(it.getInt(0) > 0)
                    }
            }
            "verifyDeletionRace" -> {
                val raceTitle = "Recovered race $run ${java.util.UUID.randomUUID()}"
                app.cloud.disconnect()
                repo.saveCalendar(CalendarEntity(name = "Deletion race $run", color = -13388167))
                val calendar = repo.getAllCalendars().single { it.name == "Deletion race $run" }
                val id =
                    repo.saveEvent(
                        EventEntity(
                            calendarId = calendar.id,
                            title = raceTitle,
                            start = System.currentTimeMillis(),
                            end = System.currentTimeMillis() + 3600000,
                            description = "Original snapshot",
                        )
                    )
                val snapshot = app.cloud.pending().single { it.kind == "event" && it.localId == id }
                repo.saveEvent(repo.getEvent(id)!!.copy(description = "Newer local edit"))
                val acknowledged =
                    JSONObject()
                        .put("kind", "event")
                        .put("id", snapshot.id)
                        .put("version", 1)
                        .put("deleted", false)
                        .put("data", snapshot.change.getJSONObject("data"))
                app.cloud.apply(JSONArray().put(acknowledged), listOf(snapshot))
                assertEquals("Newer local edit", repo.getEvent(id)!!.description)
                val sql = repo.db.openHelper.writableDatabase
                fun version(): Long =
                    sql.query(
                            "SELECT version FROM sync_meta WHERE kind='event' AND localId=?",
                            arrayOf<Any>(id),
                        )
                        .use {
                            it.moveToFirst()
                            it.getLong(0)
                        }
                assertEquals(1L, version())
                val competing =
                    JSONObject(snapshot.change.getJSONObject("data").toString())
                        .put("description", "Other device")
                app.cloud.apply(
                    JSONArray()
                        .put(
                            JSONObject(acknowledged.toString())
                                .put("version", 2)
                                .put("data", competing)
                        ),
                    listOf(snapshot),
                )
                assertEquals(
                    "Conflicting acknowledgement must not rebase an unsent edit",
                    1L,
                    version(),
                )
                val remoteCalendar =
                    sql.query(
                            "SELECT remoteId FROM sync_meta WHERE kind='calendar' AND localId=?",
                            arrayOf<Any>(calendar.id),
                        )
                        .use {
                            it.moveToFirst()
                            it.getString(0)
                        }
                sql.execSQL(
                    "UPDATE sync_meta SET dirty=0,version=1 WHERE kind='calendar' AND localId=?",
                    arrayOf<Any>(calendar.id),
                )
                val deletedEvent =
                    JSONObject()
                        .put("kind", "event")
                        .put("id", snapshot.id)
                        .put("version", 3)
                        .put("deleted", true)
                        .put("data", JSONObject.NULL)
                val deletedCalendar =
                    JSONObject()
                        .put("kind", "calendar")
                        .put("id", remoteCalendar)
                        .put("version", 4)
                        .put("deleted", true)
                        .put("data", JSONObject.NULL)
                assertEquals(
                    1,
                    app.cloud.apply(
                        JSONArray().put(deletedEvent).put(deletedCalendar),
                        emptyList(),
                    ),
                )
                assertFalse(repo.getAllCalendars().any { it.id == calendar.id })
                val recovered = repo.getAllEvents().single { it.title == raceTitle }
                assertEquals("Newer local edit", recovered.description)
                assertEquals(
                    "Recovered edits",
                    repo.getAllCalendars().single { it.id == recovered.calendarId }.name,
                )
                assertTrue(
                    app.cloud.pending().any { it.localId == recovered.id && it.kind == "event" }
                )
                app.cloud.connect(
                    args.getString("server") ?: "http://127.0.0.1:3000",
                    args.getString("email") ?: "device-test@example.com",
                    args.getString("password") ?: "device-sync-test-2026",
                    false,
                )
                assertTrue(app.cloud.state.value.message, app.cloud.sync())
            }
            "verifyBrowser" -> {
                val event = repo.getAllEvents().single { it.title == "Browser sync verification" }
                assertTrue(event.allDay)
                assertEquals(86400000L, event.end - event.start)
            }
            "verifyOffline" -> {
                assertEquals(1, repo.getAllEvents().count { it.title == "$title offline" })
                repo.db.openHelper.readableDatabase
                    .query("SELECT count(*) FROM sync_meta WHERE dirty=1")
                    .use {
                        it.moveToFirst()
                        assertEquals(0, it.getInt(0))
                    }
            }
            else -> error("Unknown phase $phase")
        }
        println("CLOUD_DEVICE_PASS phase=$phase run=$run")
        Unit
    }
}
