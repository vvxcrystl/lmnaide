package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.CalendarEntity
import dev.lmnaide.calendar.data.EventEntity
import java.time.LocalDate

/** Expands the visible events into occurrences for any date range. */
class EventIndex(
    events: List<EventEntity>,
    calendars: List<CalendarEntity>,
    showTasks: Boolean = true,
) {
    private val calendarsById = calendars.associateBy { it.id }
    private val visibleEvents = events.filter {
        calendarsById[it.calendarId]?.visible == true && (showTasks || !it.isTask)
    }

    fun occurrences(from: LocalDate, to: LocalDate): List<Occurrence> =
        visibleEvents
            .flatMap { event ->
                val calendar = calendarsById.getValue(event.calendarId)
                RecurrenceExpander.expand(event, event.color ?: calendar.color, calendar.name, from, to)
            }
            .sortedWith(OccurrenceOrder)

    fun byDay(from: LocalDate, to: LocalDate): Map<LocalDate, List<Occurrence>> {
        val days = HashMap<LocalDate, MutableList<Occurrence>>()
        for (occurrence in occurrences(from, to)) {
            var day = maxOf(occurrence.startDate, from)
            val last = minOf(occurrence.lastDate, to)
            while (day <= last) {
                days.getOrPut(day) { mutableListOf() } += occurrence
                day = day.plusDays(1)
            }
        }
        return days
    }

    fun search(query: String): List<EventEntity> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return visibleEvents.filter {
            it.title.contains(needle, ignoreCase = true) ||
                it.location.contains(needle, ignoreCase = true) ||
                it.description.contains(needle, ignoreCase = true)
        }
    }

    fun calendarOf(event: EventEntity): CalendarEntity? = calendarsById[event.calendarId]

    /** The next occurrence from [today] for a series, or the first one if none are upcoming. */
    fun representative(event: EventEntity, today: LocalDate): Occurrence? {
        val calendar = calendarsById[event.calendarId] ?: return null
        val color = event.color ?: calendar.color
        val first = event.localStart().toLocalDate()
        return RecurrenceExpander.expand(event, color, calendar.name, today, today.plusYears(5)).firstOrNull()
            ?: RecurrenceExpander.expand(event, color, calendar.name, first, first.plusDays(1)).firstOrNull()
    }

    companion object {
        val Empty = EventIndex(emptyList(), emptyList())

        /** All-day and multi-day first, then by start, then longer first. */
        val OccurrenceOrder: Comparator<Occurrence> =
            compareBy<Occurrence> { !it.inAllDayLane }
                .thenBy { it.start }
                .thenByDescending { it.end }
                .thenBy { it.event.title }
    }
}
