package dev.lmnaide.calendar.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CanadianHolidaysTest {
    private fun dateIn(title: String, year: Int) = CanadianHolidays.all.first { it.title == title }.rule(year)

    @Test fun easter() {
        assertEquals(LocalDate.of(2025, 4, 20), CanadianHolidays.easter(2025))
        assertEquals(LocalDate.of(2026, 4, 5), CanadianHolidays.easter(2026))
        assertEquals(LocalDate.of(2027, 3, 28), CanadianHolidays.easter(2027))
        assertEquals(LocalDate.of(2038, 4, 25), CanadianHolidays.easter(2038))
    }

    @Test fun easterRelated() {
        assertEquals(LocalDate.of(2026, 4, 3), dateIn("Good Friday", 2026))
        assertEquals(LocalDate.of(2026, 4, 6), dateIn("Easter Monday", 2026))
    }

    @Test fun floatingHolidays() {
        assertEquals(LocalDate.of(2026, 2, 16), dateIn("Family Day", 2026))
        assertEquals(LocalDate.of(2026, 5, 18), dateIn("Victoria Day", 2026))
        assertEquals(LocalDate.of(2027, 5, 24), dateIn("Victoria Day", 2027)) // May 24 is itself a Monday
        assertEquals(LocalDate.of(2026, 5, 10), dateIn("Mother's Day", 2026))
        assertEquals(LocalDate.of(2026, 6, 21), dateIn("Father's Day", 2026))
        assertEquals(LocalDate.of(2026, 8, 3), dateIn("Civic Holiday", 2026))
        assertEquals(LocalDate.of(2026, 8, 17), dateIn("Discovery Day", 2026))
        assertEquals(LocalDate.of(2026, 9, 7), dateIn("Labour Day", 2026))
        assertEquals(LocalDate.of(2026, 10, 12), dateIn("Thanksgiving", 2026))
    }

    @Test fun mondayNearest() {
        assertEquals(LocalDate.of(2026, 4, 20), dateIn("St. George's Day", 2026)) // Thu Apr 23
        assertEquals(LocalDate.of(2026, 7, 13), dateIn("Orangemen's Day", 2026)) // Sun Jul 12
    }

    @Test fun generatesEveryYearForFloatingHolidaysOnly() {
        val labour = CanadianHolidays.all.first { it.title == "Labour Day" }
        val christmas = CanadianHolidays.all.first { it.title == "Christmas Day" }
        assertEquals(CanadianHolidays.years.count(), CanadianHolidays.occurrences(labour).size)
        assertEquals(1, CanadianHolidays.occurrences(christmas).size)
    }
}
