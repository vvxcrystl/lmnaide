package dev.lmnaide.calendar.domain

import dev.lmnaide.calendar.data.EventKind
import dev.lmnaide.calendar.data.Recurrence
import dev.lmnaide.calendar.data.SyncLimits
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A copy from a public event link. No local IDs, contacts or completion state are imported. */
data class SharedEventDraft(
    val title: String,
    val description: String,
    val location: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val recurrence: Recurrence,
    val recurrenceUntil: LocalDate?,
    val kind: EventKind,
) {
    companion object {
        /** Parses the untrusted query of lmnaide://add-event before opening an editable draft. */
        fun parse(query: String?, zone: ZoneId = ZoneId.systemDefault()): SharedEventDraft? = runCatching {
            require(query != null && query.length <= 300_000)
            val fields = query.split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                require(pair.size == 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            fun text(key: String, limit: Int): String = fields.getValue(key).also { require(it.length <= limit) }
            val title = text("title", SyncLimits.TITLE)
            val description = text("description", SyncLimits.DESCRIPTION)
            val location = text("location", SyncLimits.LOCATION)
            val startMs = fields.getValue("start").toLong()
            val endMs = fields.getValue("end").toLong()
            val min = SyncLimits.MIN_DATE.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val max = SyncLimits.MAX_DATE.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - 1
            require(startMs in min..max && endMs in startMs..max)
            val allDay = fields.getValue("allDay").toBooleanStrict()
            val timeZone = if (allDay) ZoneOffset.UTC else zone
            val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), timeZone)
            val end = LocalDateTime.ofInstant(Instant.ofEpochMilli(endMs), timeZone)
            require(SyncLimits.validDate(start.toLocalDate()) && SyncLimits.validDate(end.toLocalDate()))
            val until = fields["recurrenceUntil"]?.let { LocalDate.ofEpochDay(it.toLong()) }
            require(until == null || SyncLimits.validDate(until))
            SharedEventDraft(title, description, location, start, end, allDay,
                Recurrence.valueOf(fields.getValue("recurrence")), until,
                EventKind.valueOf(fields.getValue("kind")))
        }.getOrNull()
    }
}
