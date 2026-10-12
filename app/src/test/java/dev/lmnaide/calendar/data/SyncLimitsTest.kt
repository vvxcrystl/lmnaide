package dev.lmnaide.calendar.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SyncLimitsTest {
    @Test
    fun limitsAcceptBoundariesAndRejectOverages() {
        assertTrue(SyncLimits.validCalendar("A", "x".repeat(2_000)))
        assertFalse(SyncLimits.validCalendar(" ", ""))
        assertFalse(SyncLimits.validCalendar("x".repeat(201), ""))
        assertTrue(SyncLimits.validReminders(listOf(0, 525_600)))
        assertFalse(SyncLimits.validReminders(listOf(-1)))
        assertFalse(SyncLimits.validReminders(List(101) { 1 }))
        assertTrue(SyncLimits.validDate(LocalDate.of(1900, 1, 1)))
        assertTrue(SyncLimits.validDate(LocalDate.of(2200, 12, 31)))
        assertFalse(SyncLimits.validDate(LocalDate.of(1899, 12, 31)))
    }
}
