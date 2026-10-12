package dev.lmnaide.calendar.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.lmnaide.calendar.container
import dev.lmnaide.calendar.data.*
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run create/receive/delete on two devices with the same dedicated test account. */
@RunWith(AndroidJUnit4::class)
class EventShareDeviceTest {
    @Test
    fun sharingPhase(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("phase") ?: error("Pass create, receive or delete")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.container
        val repo = app.repository
        val run = args.getString("run") ?: "integration"
        val name = "Share integration $run"
        app.cloud.connect(
            args.getString("server") ?: "http://127.0.0.1:3000",
            args.getString("email") ?: "device-test@example.com",
            args.getString("password") ?: "device-sync-test-2026",
            false,
        )
        assertTrue(app.cloud.state.value.message, app.cloud.state.value.connected)
        assertEquals(
            app.cloud.state.value.message,
            args.getString("email") ?: "device-test@example.com",
            app.cloud.state.value.email,
        )
        assertTrue(app.cloud.state.value.message, app.cloud.sync())
        fun read(url: String): Pair<Int, JSONObject> {
            val connection =
                URL(url.replace("/s/", "/api/shared/")).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val code = connection.responseCode
                val stream = if (code == 200) connection.inputStream else connection.errorStream
                return code to JSONObject(stream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }
        when (phase) {
            "create" -> {
                repo.saveCalendar(CalendarEntity(name = name, color = -13388167))
                val calendar = repo.getAllCalendars().single { it.name == name }
                val today = LocalDate.now()
                repo.saveEvent(
                    EventEntity(
                        calendarId = calendar.id,
                        title = name,
                        description = "Shared from Android",
                        location = "Regina",
                        start = EventEntity.toStored(today.atStartOfDay(), true),
                        end = EventEntity.toStored(today.plusDays(2).atStartOfDay(), true),
                        allDay = true,
                        recurrence = Recurrence.WEEKLY,
                        reminders = listOf(10),
                    )
                )
                val event = repo.getAllEvents().single { it.title == name }
                assertNull(app.cloud.share(event.id, "status"))
                val url = app.cloud.share(event.id, "create")!!
                assertEquals(url, app.cloud.share(event.id, "create"))
                val (code, body) = read(url)
                assertEquals(200, code)
                assertEquals(name, body.getJSONObject("event").getString("title"))
                assertFalse(body.getJSONObject("event").has("reminders"))
                context.getSharedPreferences("share-test", 0).edit().putString(run, url).commit()
                println("SHARE_URL=$url")
            }
            "receive" -> {
                val event = repo.getAllEvents().single { it.title == name }
                val url = app.cloud.share(event.id, "status")!!
                repo.saveEvent(event.copy(description = "Updated on second emulator"))
                assertTrue(app.cloud.sync())
                assertEquals(
                    "Updated on second emulator",
                    read(url).second.getJSONObject("event").getString("description"),
                )
                assertNull(app.cloud.share(event.id, "revoke"))
                assertEquals(404, read(url).first)
                assertNull(app.cloud.share(event.id, "status"))
            }
            "delete" -> {
                val event = repo.getAllEvents().single { it.title == name }
                val old = context.getSharedPreferences("share-test", 0).getString(run, null)!!
                assertNull(app.cloud.share(event.id, "status"))
                val url = app.cloud.share(event.id, "create")!!
                assertNotEquals(old, url)
                assertEquals(404, read(old).first)
                repo.deleteCalendar(repo.getAllCalendars().single { it.name == name })
                assertTrue(app.cloud.sync())
                assertEquals(404, read(url).first)
                context.getSharedPreferences("share-test", 0).edit().remove(run).commit()
            }
            else -> error("Unknown phase $phase")
        }
    }
}
