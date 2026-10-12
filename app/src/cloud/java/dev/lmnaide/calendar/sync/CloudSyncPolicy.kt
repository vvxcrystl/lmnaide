package dev.lmnaide.calendar.sync

import java.security.MessageDigest

internal data class HolidaySeedRow(val localId: Long, val title: String, val start: Long)

/** Gives only the first identical built-in holiday the shared identity; copies stay device-local. */
internal fun canonicalHolidaySeedIds(rows: List<HolidaySeedRow>): Map<Long, String> {
    val seen = mutableSetOf<Pair<String, Long>>()
    return buildMap {
        for (row in rows.sortedBy { it.localId }) {
            if (!seen.add(row.title to row.start)) continue
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${row.title}:${row.start}".toByteArray())
                .joinToString("") { "%02x".format(it) }
            put(row.localId, "seed:holiday:$digest")
        }
    }
}

/** Old bindings store address:userId; only an account change should lock local data. */
internal fun boundToOtherAccount(previous: String?, userId: String): Boolean =
    previous?.substringAfterLast(':')?.let { it != userId } ?: false

internal class SyncValidationFailure(val error: String, val operationId: String) : Exception(error)

internal fun syncValidationFailure(
    code: Int,
    path: String,
    error: String,
    operationId: String,
): SyncValidationFailure? =
    if (code == 400 && path == "sync" && operationId.isNotBlank()) {
        SyncValidationFailure(error, operationId)
    } else null

/** Adds complete groups until a request limit is reached; the first group may exceed a soft cap. */
internal fun <T> takeWholeGroups(
    groups: List<List<T>>,
    maxItems: Int,
    maxBytes: Int,
    sizeOf: (T) -> Int,
): List<T> {
    val result = mutableListOf<T>()
    var bytes = 0
    for (group in groups) {
        val groupBytes = group.sumOf(sizeOf)
        if (result.isNotEmpty() && (result.size + group.size > maxItems || bytes + groupBytes > maxBytes)) break
        result += group
        bytes += groupBytes
    }
    return result
}

internal fun <T> failedChangeGroup(
    operationId: String,
    changes: List<T>,
    operationIdOf: (T) -> String,
    groupOf: (T) -> String?,
): List<T>? {
    val failed = changes.firstOrNull { operationIdOf(it) == operationId } ?: return null
    val group = groupOf(failed) ?: return listOf(failed)
    return changes.filter { groupOf(it) == group }
}

/** Keeps the original sent snapshot available while applying acknowledgements from later pages. */
internal suspend fun <T> visitSyncPages(
    initialCursor: Long,
    request: suspend (cursor: Long, includeChanges: Boolean) -> T,
    cursorOf: (T) -> Long,
    hasMore: (T) -> Boolean,
    applyPage: suspend (T) -> Unit,
) {
    var cursor = initialCursor
    var includeChanges = true
    do {
        val page = request(cursor, includeChanges)
        applyPage(page)
        val next = cursorOf(page)
        val more = hasMore(page)
        check(!more || next > cursor) { "Sync pagination did not advance the cursor" }
        cursor = next
        includeChanges = false
    } while (more)
}
