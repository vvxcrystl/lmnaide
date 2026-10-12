package dev.lmnaide.calendar.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import dev.lmnaide.calendar.BuildConfig
import dev.lmnaide.calendar.container
import dev.lmnaide.calendar.data.*
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class SyncState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val email: String = "",
    val server: String = "",
    val message: String = "Connect your devices",
    val lastSync: Long = 0,
)

class CloudSync(
    private val context: Context,
    private val repository: CalendarRepository,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var foreground = false
    private val db
        get() = repository.db

    private val sql
        get() = db.openHelper.writableDatabase

    private val mutableState = MutableStateFlow(readState())
    val state = mutableState.asStateFlow()
    private var syncJob: Job? = null
    private val deviceId by lazy {
        prefs.getString("deviceId", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).commit() }
    }

    private fun readState() =
        SyncState(
            connected = prefs.contains("token"),
            email = prefs.getString("email", "").orEmpty(),
            server = prefs.getString("server", "").orEmpty(),
            lastSync = prefs.getLong("lastSync", 0),
            message = if (prefs.contains("token")) "Ready to sync" else "Connect your devices",
        )

    @OptIn(FlowPreview::class)
    suspend fun start() {
        // Stable IDs for the built-in seed stop fresh devices duplicating calendars and holidays.
        if (!prefs.getBoolean("seedMapped", false)) {
            db.withTransaction {
                for (name in listOf("Personal", "Work", "Holidays")) {
                    sql.execSQL(
                        "UPDATE sync_meta SET remoteId=? WHERE kind='calendar' AND version=0 AND localId=(SELECT id FROM calendars WHERE name=? ORDER BY id LIMIT 1)",
                        arrayOf<Any>("seed:calendar:$name", name),
                    )
                }
                sql.query(
                        "SELECT e.id,e.title,e.start FROM events e JOIN calendars c ON e.calendarId=c.id WHERE c.name='Holidays'"
                    )
                    .use { rows ->
                        while (rows.moveToNext()) {
                            val digest =
                                MessageDigest.getInstance("SHA-256")
                                    .digest("${rows.getString(1)}:${rows.getLong(2)}".toByteArray())
                                    .joinToString("") { "%02x".format(it) }
                            sql.execSQL(
                                "UPDATE sync_meta SET remoteId=? WHERE kind='event' AND localId=? AND version=0",
                                arrayOf<Any>("seed:holiday:$digest", rows.getLong(0)),
                            )
                        }
                    }
            }
            prefs.edit().putBoolean("seedMapped", true).commit()
        }
        ready.complete(Unit)
        schedule()
        scope.launch {
            while (isActive) {
                delay(10_000)
                if (foreground) requestSync()
            }
        }
        scope.launch {
            combine(repository.events, repository.calendars) { _, _ -> Unit }
                .debounce(700)
                .collect { requestSync() }
        }
        requestSync()
    }

    private fun schedule() {
        val manager = WorkManager.getInstance(context)
        if (!prefs.contains("token")) {
            manager.cancelUniqueWork("cloud-periodic")
            return
        }
        manager.enqueueUniquePeriodicWork(
            "cloud-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CloudSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
    }

    fun setForeground(active: Boolean) {
        foreground = active
        if (active) requestSync()
    }

    @Synchronized
    fun requestSync() {
        if (prefs.contains("token") && syncJob?.isActive != true) {
            syncJob = scope.launch(Dispatchers.IO) { sync() }
        }
    }

    suspend fun connect(server: String, email: String, password: String, register: Boolean) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                mutableState.value = state.value.copy(busy = true, message = "Connecting…")
                try {
                    val uri = URI(server.trim().trimEnd('/'))
                    require(
                        uri.host != null &&
                            uri.rawUserInfo == null &&
                            uri.rawQuery == null &&
                            uri.rawFragment == null &&
                            uri.path.orEmpty().isEmpty()
                    ) {
                        "Enter the server address, such as https://calendar.example.com"
                    }
                    require(uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) {
                        "Use an HTTPS server address"
                    }
                    val address = uri.toString()
                    val result =
                        request(
                            address,
                            if (register) "register" else "login",
                            JSONObject().put("email", email.trim()).put("password", password),
                            null,
                        )
                    val binding = address + ":" + result.getString("userId")
                    val previous = prefs.getString("binding", null)
                    if (previous != null && previous != binding) {
                        runCatching {
                            request(address, "logout", JSONObject(), result.getString("token"))
                        }
                        error(
                            "This local calendar belongs to ${prefs.getString("email", "another account")}. Sign in to that account to keep data separate."
                        )
                    }
                    prefs
                        .edit()
                        .putString("server", address)
                        .putString("email", result.getString("email"))
                        .putString("token", TokenVault.encrypt(result.getString("token")))
                        .putString("binding", binding)
                        .commit()
                    mutableState.value = readState()
                    schedule()
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        mutableState.value = readState()
                        throw e
                    }
                    mutableState.value =
                        readState()
                            .copy(
                                message =
                                    if (e is java.io.IOException)
                                        "Could not reach the server. Check the address and your connection."
                                    else e.message ?: "Could not connect"
                            )
                }
            }
            if (prefs.contains("token")) sync()
        }

    suspend fun disconnect() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val token = runCatching {
                    TokenVault.decrypt(prefs.getString("token", "").orEmpty())
                }
                    .getOrNull()
                if (token != null)
                    runCatching {
                        request(
                            prefs.getString("server", "").orEmpty(),
                            "logout",
                            JSONObject(),
                            token,
                        )
                    }
                prefs.edit().remove("token").commit()
                WorkManager.getInstance(context).cancelUniqueWork("cloud-periodic")
                mutableState.value =
                    readState()
                        .copy(message = "Signed out. Your local calendar stays on this device.")
            }
        }

    private fun request(
        server: String,
        path: String,
        body: JSONObject,
        token: String?,
    ): JSONObject {
        val connection = URL("$server/api/$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val result = stream?.bufferedReader()?.use { JSONObject(it.readText()) } ?: JSONObject()
            if (code !in 200..299) {
                if (code == 401 && path == "sync") {
                    prefs.edit().remove("token").commit()
                    schedule()
                }
                error(result.optString("error", "Server returned $code"))
            }
            return result
        } finally {
            connection.disconnect()
        }
    }

    internal data class Pending(
        val kind: String,
        val id: String,
        val localId: Long,
        val generation: Long,
        val change: JSONObject,
    )

    internal suspend fun pending(): List<Pending> = db.withTransaction {
        val calendars = db.calendarDao().getAll().associateBy { it.id }
        val events = db.eventDao().getAll().associateBy { it.id }
        val calendarIds = mutableMapOf<Long, String>()
        sql.query("SELECT localId,remoteId FROM sync_meta WHERE kind='calendar'").use {
            while (it.moveToNext()) calendarIds[it.getLong(0)] = it.getString(1)
        }
        val result = mutableListOf<Pending>()
        var payloadBytes = 0
        sql.query(
                "SELECT kind,localId,remoteId,version,generation,deleted FROM sync_meta WHERE dirty=1 ORDER BY CASE kind WHEN 'calendar' THEN 0 ELSE 1 END,localId LIMIT 1000"
            )
            .use { rows ->
                while (rows.moveToNext()) {
                    val kind = rows.getString(0)
                    val localId = rows.getLong(1)
                    val id = rows.getString(2)
                    val generation = rows.getLong(4)
                    val deleted = rows.getInt(5) == 1
                    val data =
                        if (deleted) null
                        else if (kind == "calendar")
                            calendars[localId]?.let { c ->
                                JSONObject()
                                    .put("name", c.name)
                                    .put("color", c.color)
                                    .put("visible", c.visible)
                                    .put("keywords", c.keywords)
                                    .put(
                                        "defaultImportance",
                                        c.defaultImportance?.name ?: JSONObject.NULL,
                                    )
                            }
                        else
                            events[localId]?.let { e ->
                                JSONObject()
                                    .put("calendarId", calendarIds[e.calendarId])
                                    .put("title", e.title)
                                    .put("description", e.description)
                                    .put("location", e.location)
                                    .put("start", e.start)
                                    .put("end", e.end)
                                    .put("allDay", e.allDay)
                                    .put("color", e.color ?: JSONObject.NULL)
                                    .put("recurrence", e.recurrence.name)
                                    .put("recurrenceUntil", e.recurrenceUntil ?: JSONObject.NULL)
                                    .put("exceptions", JSONArray(e.exceptions))
                                    .put("reminders", JSONArray(e.reminders))
                                    .put("kind", e.kind.name)
                                    .put("completions", JSONArray(e.completions))
                                    .put("importance", e.importance?.name ?: JSONObject.NULL)
                            }
                    if (!deleted && data == null) continue
                    val pending =
                        Pending(
                            kind,
                            id,
                            localId,
                            generation,
                            JSONObject()
                                .put("kind", kind)
                                .put("id", id)
                                .put("baseVersion", rows.getLong(3))
                                .put("operationId", "$deviceId:$id:$generation")
                                .put("deleted", deleted)
                                .put("data", data ?: JSONObject.NULL),
                        )
                    val bytes = pending.change.toString().toByteArray(Charsets.UTF_8).size
                    if (result.isNotEmpty() && payloadBytes + bytes > 1_000_000) break
                    payloadBytes += bytes
                    result += pending
                }
            }
        result
    }

    suspend fun sync(): Boolean =
        withContext(Dispatchers.IO) {
            ready.await()
            mutex.withLock {
                if (!prefs.contains("token")) return@withLock true
                mutableState.value = state.value.copy(busy = true, message = "Syncing…")
                try {
                    val token = TokenVault.decrypt(prefs.getString("token", "").orEmpty())
                    var conflictCount = 0
                    var recoveredCount = 0
                    var rounds = 0
                    var again: Boolean
                    do {
                        rounds++
                        val sent = pending()
                        val response =
                            request(
                                prefs.getString("server", "").orEmpty(),
                                "sync",
                                JSONObject()
                                    .put("cursor", prefs.getLong("cursor", 0))
                                    .put("changes", JSONArray(sent.map { it.change })),
                                token,
                            )
                        conflictCount += response.getJSONArray("conflicts").length()
                        val recoveredNow = apply(response.getJSONArray("records"), sent)
                        recoveredCount += recoveredNow
                        prefs
                            .edit()
                            .putLong("cursor", response.getLong("cursor"))
                            .putLong("lastSync", System.currentTimeMillis())
                            .commit()
                        again =
                            (sent.isNotEmpty() || recoveredNow > 0) && hasPending() && rounds < 10
                    } while (again)
                    mutableState.value =
                        readState()
                            .copy(
                                message =
                                    if (recoveredCount > 0)
                                        "$recoveredCount unsent edit(s) saved in Recovered edits"
                                    else if (hasPending())
                                        "Changes queued. Sync will continue automatically."
                                    else if (conflictCount > 0)
                                        "$conflictCount conflicting edit(s) saved in server Edit history"
                                    else "Up to date"
                            )
                    true
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        mutableState.value = readState()
                        throw e
                    }
                    mutableState.value =
                        readState()
                            .copy(
                                message =
                                    if (e is java.io.IOException)
                                        "Offline. Your changes are saved and will sync when connected."
                                    else e.message ?: "Could not sync"
                            )
                    false
                }
            }
        }

    private data class Meta(
        val localId: Long,
        val generation: Long,
        val dirty: Boolean,
        val version: Long,
    )

    private fun meta(kind: String, id: String): Meta? =
        sql.query(
                "SELECT localId,generation,dirty,version FROM sync_meta WHERE kind=? AND remoteId=?",
                arrayOf<Any>(kind, id),
            )
            .use {
                if (it.moveToFirst())
                    Meta(it.getLong(0), it.getLong(1), it.getInt(2) == 1, it.getLong(3))
                else null
            }

    private fun hasPending(): Boolean =
        sql.query("SELECT 1 FROM sync_meta WHERE dirty=1 LIMIT 1").use { it.moveToFirst() }

    internal suspend fun apply(records: JSONArray, sent: List<Pending>): Int = db.withTransaction {
        var recovered = 0
        sql.execSQL("UPDATE sync_control SET applying=1 WHERE id=1")
        try {
            val ordered =
                (0 until records.length())
                    .map { records.getJSONObject(it) }
                    .sortedBy {
                        if (it.getString("kind") == "calendar" && !it.getBoolean("deleted")) 0
                        else if (it.getString("kind") == "event") 1 else 2
                    }
            for (r in ordered) {
                val kind = r.getString("kind")
                val id = r.getString("id")
                val old = meta(kind, id)
                val snapshot = sent.firstOrNull { it.kind == kind && it.id == id }
                if (
                    old != null &&
                        old.dirty &&
                        (snapshot == null || snapshot.generation != old.generation)
                ) {
                    // Keep edits made during HTTP queued. Rebase only on our own acknowledged
                    // value.
                    if (
                        snapshot != null &&
                            r.getBoolean("deleted") == snapshot.change.getBoolean("deleted") &&
                            samePayload(r.opt("data"), snapshot.change.opt("data"))
                    )
                        sql.execSQL(
                            "UPDATE sync_meta SET version=? WHERE kind=? AND remoteId=?",
                            arrayOf<Any>(r.getLong("version"), kind, id),
                        )
                    continue
                }
                if (old != null && r.getLong("version") < old.version) continue
                var localId = old?.localId ?: 0L
                val deleted = r.getBoolean("deleted")
                if (deleted) {
                    if (old != null) {
                        if (kind == "calendar") {
                            recovered += recoverUnsentEvents(localId)
                            sql.execSQL("DELETE FROM calendars WHERE id=?", arrayOf<Any>(localId))
                        } else db.eventDao().get(localId)?.let { db.eventDao().delete(it) }
                    } else localId = -(System.nanoTime().and(Long.MAX_VALUE))
                } else {
                    val d = r.getJSONObject("data")
                    if (kind == "calendar") {
                        val value =
                            CalendarEntity(
                                id = localId,
                                name = d.getString("name"),
                                color = d.getInt("color"),
                                visible = d.getBoolean("visible"),
                                keywords = d.getString("keywords"),
                                defaultImportance =
                                    d.nullString("defaultImportance")?.let(Importance::valueOf),
                            )
                        // Deleted local records need to be inserted again when history is restored.
                        val exists = db.calendarDao().getAll().any { it.id == localId }
                        if (!exists) localId = db.calendarDao().insert(value)
                        else db.calendarDao().update(value)
                    } else {
                        val calendarId =
                            meta("calendar", d.getString("calendarId"))?.localId
                                ?: error("Missing calendar; try sync again")
                        val local = db.eventDao().get(localId)
                        val value =
                            EventEntity(
                                id = localId,
                                calendarId = calendarId,
                                title = d.getString("title"),
                                description = d.getString("description"),
                                location = d.getString("location"),
                                start = d.getLong("start"),
                                end = d.getLong("end"),
                                allDay = d.getBoolean("allDay"),
                                color = if (d.isNull("color")) null else d.getInt("color"),
                                recurrence = Recurrence.valueOf(d.getString("recurrence")),
                                recurrenceUntil =
                                    if (d.isNull("recurrenceUntil")) null
                                    else d.getLong("recurrenceUntil"),
                                exceptions = d.longs("exceptions"),
                                reminders = d.longs("reminders").map(Long::toInt),
                                kind = EventKind.valueOf(d.getString("kind")),
                                completions = d.longs("completions"),
                                importance = d.nullString("importance")?.let(Importance::valueOf),
                                people = local?.people ?: emptyList(),
                            )
                        if (local == null) localId = db.eventDao().insert(value)
                        else db.eventDao().update(value)
                    }
                }
                sql.execSQL(
                    "INSERT OR REPLACE INTO sync_meta(kind,localId,remoteId,version,generation,dirty,deleted) VALUES(?,?,?,?,?,0,?)",
                    arrayOf<Any>(
                        kind,
                        localId,
                        id,
                        r.getLong("version"),
                        old?.generation ?: 1L,
                        if (deleted) 1 else 0,
                    ),
                )
            }
        } finally {
            sql.execSQL("UPDATE sync_control SET applying=0 WHERE id=1")
        }
        recovered
    }

    /** Preserve edits outside the acknowledged snapshot before a remote deletion cascade. */
    private suspend fun recoverUnsentEvents(calendarId: Long): Int {
        val unsent =
            db.eventDao().getAll().filter { event ->
                event.calendarId == calendarId &&
                    sql.query(
                            "SELECT 1 FROM sync_meta WHERE kind='event' AND localId=? AND dirty=1 AND deleted=0",
                            arrayOf<Any>(event.id),
                        )
                        .use { it.moveToFirst() }
            }
        if (unsent.isEmpty()) return 0
        val existing =
            db.calendarDao().getAll().firstOrNull {
                it.name == "Recovered edits" && it.id != calendarId
            }
        val target =
            existing?.id
                ?: db.calendarDao()
                    .insert(CalendarEntity(name = "Recovered edits", color = 0xFFEF6C00.toInt()))
                    .also {
                        sql.execSQL(
                            "INSERT INTO sync_meta(kind,localId,remoteId) VALUES('calendar',?,?)",
                            arrayOf<Any>(it, UUID.randomUUID().toString()),
                        )
                    }
        for (event in unsent) {
            val copyId = db.eventDao().insert(event.copy(id = 0, calendarId = target))
            sql.execSQL(
                "INSERT INTO sync_meta(kind,localId,remoteId) VALUES('event',?,?)",
                arrayOf<Any>(copyId, UUID.randomUUID().toString()),
            )
            sql.execSQL(
                "UPDATE sync_meta SET dirty=0,deleted=1 WHERE kind='event' AND localId=?",
                arrayOf<Any>(event.id),
            )
        }
        return unsent.size
    }

    private fun samePayload(left: Any?, right: Any?): Boolean =
        when {
            left is JSONObject && right is JSONObject -> {
                val keys = left.keys().asSequence().toSet()
                keys == right.keys().asSequence().toSet() &&
                    keys.all { samePayload(left.opt(it), right.opt(it)) }
            }
            left is JSONArray && right is JSONArray ->
                left.length() == right.length() &&
                    (0 until left.length()).all { samePayload(left.opt(it), right.opt(it)) }
            left is Number && right is Number -> left.toDouble() == right.toDouble()
            else -> left == right
        }

    private fun JSONObject.nullString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.longs(key: String): List<Long> =
        getJSONArray(key).let { array -> (0 until array.length()).map { array.getLong(it) } }
}

class CloudSyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (applicationContext.container.cloud.sync()) Result.success() else Result.retry()
}
