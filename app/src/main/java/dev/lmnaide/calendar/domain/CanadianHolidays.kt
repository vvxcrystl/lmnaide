package dev.lmnaide.calendar.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters

/** The illustration shown above a holiday in the event details. */
enum class HolidayArt { CHRISTMAS, FIREWORKS, HEARTS, EGGS, HALLOWEEN, HARVEST, MAPLE, SUNRISE, POPPY, SHAMROCK, LANDSCAPE }

/** A holiday or observance. [rule] gives its date in a year; fixed-date ones repeat yearly. */
data class Holiday(
    val title: String,
    val description: String,
    val fixed: Boolean,
    val art: HolidayArt,
    val rule: (Int) -> LocalDate,
)

/** Federal, provincial and territorial holidays in Canada, plus widely observed days. */
object CanadianHolidays {
    const val CALENDAR_NAME = "Canadian Holidays"

    const val COLOR = 0xFF479A8F.toInt()

    /** Dated holidays are generated for these years; fixed-date ones just repeat yearly. */
    val years = 2024..2050

    private val ALL = "all provinces and territories"

    val all: List<Holiday> = listOf(
        fixed("New Year's Day", "Statutory holiday in $ALL.", Month.JANUARY, 1, HolidayArt.FIREWORKS),
        fixed("Groundhog Day", "Observance.", Month.FEBRUARY, 2, HolidayArt.LANDSCAPE),
        fixed("Valentine's Day", "Observance.", Month.FEBRUARY, 14, HolidayArt.HEARTS),
        nth(
            "Family Day",
            "Statutory holiday in AB, BC, ON, SK and NB. Also Louis Riel Day (MB), Islander Day (PE) and Heritage Day (NS).",
            Month.FEBRUARY, DayOfWeek.MONDAY, 3, HolidayArt.LANDSCAPE,
        ),
        fixed("St. Patrick's Day", "Observance; a provincial holiday in NL.", Month.MARCH, 17, HolidayArt.SHAMROCK),
        dated("Good Friday", "Statutory holiday in $ALL except QC, where Good Friday or Easter Monday is observed.", HolidayArt.EGGS) { easter(it).minusDays(2) },
        dated("Easter Sunday", "Observance.", HolidayArt.EGGS) { easter(it) },
        dated("Easter Monday", "Optional holiday in several provinces and for federal employees; observed in QC.", HolidayArt.EGGS) { easter(it).plusDays(1) },
        dated("St. George's Day", "Provincial holiday in NL, observed on the Monday nearest April 23.", HolidayArt.LANDSCAPE) { mondayNearest(it, Month.APRIL, 23) },
        nth("Mother's Day", "Observance.", Month.MAY, DayOfWeek.SUNDAY, 2, HolidayArt.HEARTS),
        dated("Victoria Day", "Statutory holiday in $ALL except NB, NL, NS and NU. Called National Patriots' Day in QC.", HolidayArt.FIREWORKS) {
            LocalDate.of(it, Month.MAY, 25).with(TemporalAdjusters.previous(DayOfWeek.MONDAY))
        },
        nth("Father's Day", "Observance.", Month.JUNE, DayOfWeek.SUNDAY, 3, HolidayArt.LANDSCAPE),
        fixed("National Indigenous Peoples Day", "Statutory holiday in NT and YT; observance elsewhere.", Month.JUNE, 21, HolidayArt.SUNRISE),
        fixed("Saint-Jean-Baptiste Day", "Statutory holiday in QC (Fête nationale du Québec).", Month.JUNE, 24, HolidayArt.FIREWORKS),
        fixed("Canada Day", "Statutory holiday in $ALL. Memorial Day in NL.", Month.JULY, 1, HolidayArt.MAPLE),
        fixed("Nunavummiut Day", "Statutory holiday in NU.", Month.JULY, 9, HolidayArt.SUNRISE),
        dated("Orangemen's Day", "Provincial holiday in NL, observed on the Monday nearest July 12.", HolidayArt.LANDSCAPE) { mondayNearest(it, Month.JULY, 12) },
        nth(
            "Civic Holiday",
            "Statutory holiday in several provinces and territories under local names: BC Day (BC), Heritage Day (AB), Saskatchewan Day (SK), Terry Fox Day (MB), Civic Holiday (ON, NT, NU), New Brunswick Day (NB), Natal Day (NS).",
            Month.AUGUST, DayOfWeek.MONDAY, 1, HolidayArt.LANDSCAPE,
        ),
        nth("Discovery Day", "Statutory holiday in YT.", Month.AUGUST, DayOfWeek.MONDAY, 3, HolidayArt.LANDSCAPE),
        nth("Labour Day", "Statutory holiday in $ALL.", Month.SEPTEMBER, DayOfWeek.MONDAY, 1, HolidayArt.LANDSCAPE),
        fixed(
            "National Day for Truth and Reconciliation",
            "Federal statutory holiday; also a statutory holiday in BC, MB, NT, NU, PE and YT.",
            Month.SEPTEMBER, 30, HolidayArt.SUNRISE,
        ),
        nth("Thanksgiving", "Statutory holiday in $ALL except NB, NL, NS and PE.", Month.OCTOBER, DayOfWeek.MONDAY, 2, HolidayArt.HARVEST),
        fixed("Halloween", "Observance.", Month.OCTOBER, 31, HolidayArt.HALLOWEEN),
        fixed("Remembrance Day", "Statutory holiday in all provinces and territories except NS, ON and QC; observance elsewhere.", Month.NOVEMBER, 11, HolidayArt.POPPY),
        fixed("Christmas Eve", "Observance.", Month.DECEMBER, 24, HolidayArt.CHRISTMAS),
        fixed("Christmas Day", "Statutory holiday in $ALL.", Month.DECEMBER, 25, HolidayArt.CHRISTMAS),
        fixed("Boxing Day", "Statutory holiday federally and in ON; observed in most other provinces.", Month.DECEMBER, 26, HolidayArt.CHRISTMAS),
        fixed("New Year's Eve", "Observance.", Month.DECEMBER, 31, HolidayArt.FIREWORKS),
    )

    private fun fixed(title: String, description: String, month: Month, day: Int, art: HolidayArt) =
        Holiday(title, description, fixed = true, art = art) { LocalDate.of(it, month, day) }

    private fun dated(title: String, description: String, art: HolidayArt, rule: (Int) -> LocalDate) =
        Holiday(title, description, fixed = false, art = art, rule = rule)

    private fun nth(title: String, description: String, month: Month, day: DayOfWeek, n: Int, art: HolidayArt) =
        dated(title, description, art) { LocalDate.of(it, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, day)) }

    /** The illustration for an event whose title matches a holiday, if any. */
    fun artFor(title: String): HolidayArt? = byTitle[title.trim().lowercase()]?.art

    private val byTitle = all.associateBy { it.title.lowercase() }

    private fun mondayNearest(year: Int, month: Month, day: Int): LocalDate {
        val date = LocalDate.of(year, month, day)
        val before = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val after = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        return if (date.toEpochDay() - before.toEpochDay() <= after.toEpochDay() - date.toEpochDay()) before else after
    }

    /** Western Easter Sunday (anonymous Gregorian algorithm). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    /** Every date a holiday falls on in [years], or just its first date if it repeats yearly. */
    fun occurrences(holiday: Holiday): List<LocalDate> =
        if (holiday.fixed) listOf(holiday.rule(years.first)) else years.map(holiday.rule)
}
