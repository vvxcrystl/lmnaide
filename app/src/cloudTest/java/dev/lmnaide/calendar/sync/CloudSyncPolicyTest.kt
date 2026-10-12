package dev.lmnaide.calendar.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncPolicyTest {
    @Test
    fun duplicateHolidayIdentityMapsOnlyTheCanonicalCopy() {
        val ids = canonicalHolidaySeedIds(
            listOf(
                HolidaySeedRow(8, "New Year", 100),
                HolidaySeedRow(3, "New Year", 100),
                HolidaySeedRow(9, "New Year", 200),
            ),
        )

        assertEquals(setOf(3L, 9L), ids.keys)
        assertFalse(ids.containsKey(8L))
        assertTrue(ids.getValue(3L).startsWith("seed:holiday:"))
        assertTrue(ids.getValue(3L) != ids.getValue(9L))
    }

    @Test
    fun bindingAllowsHostnameMigrationButRejectsAnotherAccount() {
        assertFalse(boundToOtherAccount("https://old.example:8443:user-1", "user-1"))
        assertTrue(boundToOtherAccount("https://old.example:8443:user-1", "user-2"))
        assertFalse(boundToOtherAccount(null, "user-1"))
    }

    @Test
    fun validation400IsAssociatedWithItsOperationAndOtherFailuresStayGeneric() {
        val failure = syncValidationFailure(400, "sync", "title is too long", "device:id:2")
        assertNotNull(failure)
        assertEquals("title is too long", failure?.error)
        assertEquals("device:id:2", failure?.operationId)
        assertNull(syncValidationFailure(400, "login", "bad request", "device:id:2"))
        assertNull(syncValidationFailure(400, "sync", "bad request", ""))
    }

    @Test
    fun requestBatchNeverSplitsAnOperationGroup() {
        val selected = takeWholeGroups(
            groups = listOf(listOf("a", "b"), listOf("c", "d")),
            maxItems = 3,
            maxBytes = 100,
            sizeOf = { 1 },
        )

        assertEquals(listOf("a", "b"), selected)
    }

    @Test
    fun invalidChangeQuarantinesItsWholeLocalOperationGroup() {
        data class Change(val operationId: String, val group: String?)
        val changes = listOf(
            Change("device:series:4", "split-edit"),
            Change("device:tail:2", "split-edit"),
            Change("device:other:1", null),
        )

        val affected = failedChangeGroup(
            operationId = "device:tail:2",
            changes = changes,
            operationIdOf = { it.operationId },
            groupOf = { it.group },
        )

        assertEquals(changes.take(2), affected)
        assertNull(failedChangeGroup("missing", changes, { it.operationId }, { it.group }))
    }

    @Test
    fun paginationSendsChangesOnceAndAppliesEveryPageWithTheSameSnapshot() = runBlocking {
        data class Page(val cursor: Long, val more: Boolean, val records: List<String>)
        val requested = mutableListOf<Pair<Long, Boolean>>()
        val applied = mutableListOf<String>()

        visitSyncPages(
            initialCursor = 0,
            request = { cursor, includeChanges ->
                requested += cursor to includeChanges
                when (cursor) {
                    0L -> Page(1, true, listOf("ack-for-sent-change"))
                    1L -> Page(2, true, listOf("remote-page-two"))
                    else -> Page(3, false, listOf("remote-page-three"))
                }
            },
            cursorOf = { it.cursor },
            hasMore = { it.more },
            applyPage = { applied += it.records },
        )

        assertEquals(listOf(0L to true, 1L to false, 2L to false), requested)
        assertEquals(listOf("ack-for-sent-change", "remote-page-two", "remote-page-three"), applied)
    }
}
