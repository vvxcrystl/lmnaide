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
    val username: String = "",
    val server: String = "",
    val message: String = "Connect your devices",
    val lastSync: Long = 0,
    /** Set after a sign-in while this device has unsynced items, until the user decides. */
    val importPrompt: ImportPrompt? = null,
)

/** Items made on this device while signed out, waiting for the user to choose whether to sync them. */
data class ImportPrompt(val events: Int, val tasks: Int, val calendars: Int, val email: String) {
    val single: Boolean
        get() = events + tasks + calendars == 1

    val summary: String
        get() =
            listOf(events to "event", tasks to "task", calendars to "calendar")
                .filter { it.first > 0 }
                .map { (n, noun) -> "$n $noun" + if (n == 1) "" else "s" }
                .let { if (it.size > 1) it.dropLast(1).joinToString(", ") + " and " + it.last() else it.single() }
}

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

    /** A successful sign-in held in memory until the user answers the import prompt. */
    private class HeldSignIn(val address: String, val result: JSONObject, val binding: String)
    @Volatile private var held: HeldSignIn? = null
    private val deviceId by lazy {
        prefs.getString("deviceId", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).commit() }
    }

    private fun readState() =
        SyncState(
            connected = prefs.contains("token"),
            email = prefs.getString("email", "").orEmpty(),
            username = prefs.getString("username", "").orEmpty(),
            server = prefs.getString("server", "").orEmpty(),
            lastSync = prefs.getLong("lastSync", 0),
            message = if (prefs.contains("token")) "Ready to sync" else "Connect your devices",
        )

    @OptIn(FlowPreview::class)
    suspend fun start() {
        // Stable IDs for the built-in seed stop fresh devices duplicating calendars and holidays.
        if (!prefs.getBoolean("seedMapped", false)) {
            val mapped = runCatching {
                db.withTransaction {
                    for (name in listOf("Personal", "Work", "Holidays")) {
                        sql.execSQL(
                            "UPDATE sync_meta SET remoteId=? WHERE kind='calendar' AND version=0 AND localId=(SELECT id FROM calendars WHERE name=? ORDER BY id LIMIT 1)",
                            arrayOf<Any>("seed:calendar:$name", name),
                        )
                    }
                    val seeds = buildList {
                        sql.query(
                            "SELECT e.id,e.title,e.start FROM events e JOIN calendars c ON e.calendarId=c.id WHERE c.name='Holidays' ORDER BY e.id"
                        ).use { rows ->
                            while (rows.moveToNext()) add(HolidaySeedRow(rows.getLong(0), rows.getString(1), rows.getLong(2)))
                        }
                    }
                    for ((localId, remoteId) in canonicalHolidaySeedIds(seeds)) {
                        sql.execSQL(
                            "UPDATE sync_meta SET remoteId=? WHERE kind='event' AND localId=? AND version=0",
                            arrayOf<Any>(remoteId, localId),
                        )
                    }
                }
            }.isSuccess
            if (mapped) prefs.edit().putBoolean("seedMapped", true).commit()
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

    suspend fun connect(server: String, email: String, password: String, register: Boolean, username: String = "") =
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
                            JSONObject().put("email", email.trim()).put("password", password).apply {
                                if (register) put("username", username.trim())
                            },
                            null,
                        )
                    val binding = address + ":" + result.getString("userId")
                    val previous = prefs.getString("binding", null)
                    if (boundToOtherAccount(previous, result.getString("userId"))) {
                        runCatching {
                            request(address, "logout", JSONObject(), result.getString("token"))
                        }
                        error(
                            "This local calendar belongs to ${prefs.getString("email", "another account")}. Sign in to that account to keep data separate."
                        )
                    }
                    val prompt = offlineItems(result.getString("email"))
                    if (prompt == null) bind(address, result, binding)
                    else {
                        // Nothing is stored or uploaded until the user chooses in the prompt.
                        held = HeldSignIn(address, result, binding)
                        mutableState.value = readState().copy(importPrompt = prompt, message = "Choose whether to sync this device")
                    }
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

    private fun bind(address: String, result: JSONObject, binding: String) {
        prefs
            .edit()
            .putString("server", address)
            .putString("email", result.getString("email"))
            .putString("username", if (result.isNull("username")) "" else result.optString("username"))
            .putString("token", TokenVault.encrypt(result.getString("token")))
            .putString("binding", binding)
            .commit()
        mutableState.value = readState()
        schedule()
    }

    /** Events, tasks and calendars made or changed while signed out; the built-in seed doesn't count. */
    private suspend fun offlineItems(email: String): ImportPrompt? {
        ready.await()
        return db.withTransaction {
            val local = mutableMapOf<String, MutableSet<Long>>()
            sql.query(
                    "SELECT kind,localId FROM sync_meta WHERE dirty=1 AND deleted=0 AND remoteId NOT LIKE 'seed:%'"
                )
                .use { while (it.moveToNext()) local.getOrPut(it.getString(0)) { mutableSetOf() } += it.getLong(1) }
            val ids = local["event"].orEmpty()
            val items = db.eventDao().getAll().filter { it.id in ids }
            val tasks = items.count { it.kind == EventKind.TASK }
            val calendars = db.calendarDao().getAll().count { it.id in local["calendar"].orEmpty() }
            ImportPrompt(items.size - tasks, tasks, calendars, email)
                .takeIf { items.isNotEmpty() || calendars > 0 }
        }
    }

    /** Answers the import prompt: sync the held sign-in, or sign out and keep everything local. */
    suspend fun finishSignIn(syncLocal: Boolean) =
        withContext(Dispatchers.IO) {
            val signedIn =
                mutex.withLock {
                    val sign = held ?: return@withLock false
                    held = null
                    if (syncLocal) bind(sign.address, sign.result, sign.binding)
                    else {
                        runCatching {
                            request(sign.address, "logout", JSONObject(), sign.result.getString("token"))
                        }
                        mutableState.value =
                            readState().copy(message = "Not signed in. Your events stay on this device.")
                    }
                    syncLocal
                }
            if (signedIn) sync()
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

    suspend fun updateUsername(username: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutableState.value = state.value.copy(busy = true)
            try {
                val token = prefs.getString("token", null)?.let { TokenVault.decrypt(it) }
                check(token != null) { "Sign in to Cloud sync again." }
                val result = request(
                    prefs.getString("server", "").orEmpty(), "profile",
                    JSONObject().put("username", username.trim()), token,
                )
                prefs.edit().putString("username", if (result.isNull("username")) "" else result.optString("username")).commit()
                mutableState.value = readState().copy(message = "Username updated")
            } catch (e: Exception) {
                mutableState.value = readState().copy(message = e.message ?: "Could not update username")
                if (e is CancellationException) throw e
            }
        }
    }

    suspend fun publicCalendar(action: String): String? = withContext(Dispatchers.IO) {
        ready.await()
        check(state.value.connected) { "Connect Cloud sync before sharing your calendar." }
        if (action == "create") check(sync()) { state.value.message }
        mutex.withLock {
            val server = prefs.getString("server", "").orEmpty()
            val token = prefs.getString("token", null)?.let { TokenVault.decrypt(it) }
            check(token != null) { "Sign in to Cloud sync again." }
            val result = request(server, "public", JSONObject().put("action", action), token)
            if (!result.getBoolean("active")) null
            else if (!result.isNull("url")) result.getString("url")
            else URI(server + "/").resolve(result.getString("path")).toString()
        }
    }

    /** Share the synced event/series; reminders, contacts and calendar metadata stay private. */
    suspend fun share(eventId: Long, action: String): String? =
        withContext(Dispatchers.IO) {
            ready.await()
            check(state.value.connected) { "Connect Cloud sync in Settings before sharing events." }
            if (action != "revoke") check(sync()) { state.value.message }
            mutex.withLock {
                val remoteId =
                    sql.query(
                            "SELECT remoteId,dirty FROM sync_meta WHERE kind='event' AND localId=? AND deleted=0",
                            arrayOf<Any>(eventId),
                        )
                        .use {
                            check(it.moveToFirst()) { "This event is no longer available." }
                            if (action == "create")
                                check(it.getInt(1) == 0) { "Sync this event before sharing it." }
                            it.getString(0)
                        }
                val server = prefs.getString("server", "").orEmpty()
                val token = prefs.getString("token", null)?.let { TokenVault.decrypt(it) }
                check(token != null) { "Sign in to Cloud sync again." }
                val result =
                    request(
                        server,
                        "share",
                        JSONObject().put("eventId", remoteId).put("action", action),
                        token,
                    )
                if (!result.getBoolean("active")) null
                else if (!result.isNull("url")) result.getString("url")
                else URI(server + "/").resolve(result.getString("path")).toString()
            }
        }

    private fun request(
        server: String,
        path: String,
        body: JSONObject,
        token: String?,
    ): JSONObject {
        val connection = URL("$server/api/v1/$path").openConnection() as HttpURLConnection
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
                syncValidationFailure(
                    code,
                    path,
                    result.optString("error", "Server returned $code"),
                    result.optString("operationId"),
                )?.let { throw it }
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
        val group: String?,
        val change: JSONObject,
    )

    internal suspend fun pending(): List<Pending> = db.withTransaction {
        val calendars = db.calendarDao().getAll().associateBy { it.id }
        val events = db.eventDao().getAll().associateBy { it.id }
        val calendarIds = mutableMapOf<Long, String>()
        sql.query("SELECT localId,remoteId FROM sync_meta WHERE kind='calendar'").use {
            while (it.moveToNext()) calendarIds[it.getLong(0)] = it.getString(1)
        }
        data class Row(
            val kind: String,
            val localId: Long,
            val id: String,
            val version: Long,
            val generation: Long,
            val deleted: Boolean,
            val group: String?,
        )
        val groups = linkedMapOf<String, MutableList<Row>>()
        sql.query(
            "SELECT kind,localId,remoteId,version,generation,deleted,opGroup FROM sync_meta WHERE dirty=1 ORDER BY CASE kind WHEN 'calendar' THEN 0 ELSE 1 END,localId"
        ).use { rows ->
            while (rows.moveToNext()) {
                val kind = rows.getString(0)
                val localId = rows.getLong(1)
                val group = rows.getString(6)
                val key = group?.let { "group:$it" } ?: "row:$kind:$localId"
                groups.getOrPut(key) { mutableListOf() } += Row(
                    kind = kind,
                    localId = localId,
                    id = rows.getString(2),
                    version = rows.getLong(3),
                    generation = rows.getLong(4),
                    deleted = rows.getInt(5) == 1,
                    group = group,
                )
            }
        }
        val pendingGroups = mutableListOf<List<Pending>>()
        for (group in groups.values) {
            val changes = group.mapNotNull { row ->
                val data =
                    if (row.deleted) null
                    else if (row.kind == "calendar")
                        calendars[row.localId]?.let { c ->
                            JSONObject()
                                .put("name", c.name)
                                .put("color", c.color)
                                .put("visible", c.visible)
                                .put("publicDetails", c.publicDetails)
                                .put("keywords", c.keywords)
                                .put("defaultImportance", c.defaultImportance?.name ?: JSONObject.NULL)
                        }
                    else
                        events[row.localId]?.let { e ->
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
                if (!row.deleted && data == null) return@mapNotNull null
                val change = JSONObject()
                    .put("kind", row.kind)
                    .put("id", row.id)
                    .put("baseVersion", row.version)
                    .put("operationId", "$deviceId:${row.id}:${row.generation}")
                    .put("deleted", row.deleted)
                    .put("data", data ?: JSONObject.NULL)
                row.group?.let { change.put("group", it) }
                Pending(row.kind, row.id, row.localId, row.generation, row.group, change)
            }
            if (changes.isNotEmpty()) pendingGroups += changes
        }
        takeWholeGroups(pendingGroups, maxItems = 1000, maxBytes = 1_000_000) {
            it.change.toString().toByteArray(Charsets.UTF_8).size
        }
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
                    val quarantined = mutableListOf<String>()
                    var rounds = 0
                    var again: Boolean
                    do {
                        rounds++
                        val sent = pending()
                        var recoveredNow = 0
                        var validationFailure: SyncValidationFailure? = null
                        try {
                            visitSyncPages(
                                initialCursor = prefs.getLong("cursor", 0),
                                request = { cursor, includeChanges ->
                                    request(
                                        prefs.getString("server", "").orEmpty(),
                                        "sync",
                                        JSONObject()
                                            .put("cursor", cursor)
                                            .put("changes", JSONArray(if (includeChanges) sent.map { it.change } else emptyList<JSONObject>())),
                                        token,
                                    )
                                },
                                cursorOf = { it.getLong("cursor") },
                                hasMore = { it.optBoolean("more", false) },
                                applyPage = { response ->
                                    conflictCount += response.optJSONArray("conflicts")?.length() ?: 0
                                    recoveredNow += apply(response.getJSONArray("records"), sent)
                                    prefs.edit()
                                        .putLong("cursor", response.getLong("cursor"))
                                        .putLong("lastSync", System.currentTimeMillis())
                                        .commit()
                                },
                            )
                        } catch (e: SyncValidationFailure) {
                            validationFailure = e
                        }
                        recoveredCount += recoveredNow
                        val failure = validationFailure
                        if (failure != null) {
                            val message = quarantine(failure, sent)
                            if (message == null) throw failure
                            quarantined += message
                            again = hasPending() && rounds < 1000
                            continue
                        }
                        again =
                            (sent.isNotEmpty() || recoveredNow > 0) && hasPending() && rounds < 1000
                    } while (again)
                    mutableState.value =
                        readState()
                            .copy(
                                message =
                                    if (quarantined.isNotEmpty())
                                        quarantined.first() +
                                            if (quarantined.size > 1) " (${quarantined.size} changes skipped)" else ""
                                    else if (recoveredCount > 0)
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

    private suspend fun quarantine(failure: SyncValidationFailure, sent: List<Pending>): String? {
        val affected = failedChangeGroup(
            failure.operationId,
            sent,
            operationIdOf = { it.change.optString("operationId") },
            groupOf = { it.group },
        ) ?: return null
        val safelyMarked = db.withTransaction {
            val unchanged = affected.all { pending ->
                sql.query(
                    "SELECT generation,dirty FROM sync_meta WHERE kind=? AND localId=?",
                    arrayOf<Any>(pending.kind, pending.localId),
                ).use { rows ->
                    rows.moveToFirst() && rows.getLong(0) == pending.generation && rows.getInt(1) == 1
                }
            }
            if (!unchanged) return@withTransaction false
            for (pending in affected) {
                sql.execSQL(
                    "UPDATE sync_meta SET dirty=0,syncError=?,opGroup=NULL WHERE kind=? AND localId=? AND generation=?",
                    arrayOf<Any>(failure.error, pending.kind, pending.localId, pending.generation),
                )
            }
            true
        }
        if (!safelyMarked) return null
        val name = affected.firstNotNullOfOrNull { pending ->
            val table = if (pending.kind == "event") "events" else "calendars"
            sql.query("SELECT ${if (pending.kind == "event") "title" else "name"} FROM $table WHERE id=?", arrayOf<Any>(pending.localId))
                .use { if (it.moveToFirst()) it.getString(0) else null }
        } ?: "local change"
        val related = if (affected.size > 1) " and ${affected.size - 1} related change(s)" else ""
        return "Could not sync ‘$name’$related: ${failure.error}. The local copy was kept."
    }

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
                                publicDetails = d.optBoolean("publicDetails", false),
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
