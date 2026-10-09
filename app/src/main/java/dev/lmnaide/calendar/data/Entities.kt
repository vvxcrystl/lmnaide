package dev.lmnaide.calendar.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

enum class Recurrence { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY, YEARLY }

enum class EventKind { EVENT, TASK }

/** How much an item matters; higher levels remind more insistently. */
enum class Importance(val label: String, val argb: Int) {
    LOW("Low", 0xFF34A853.toInt()),
    MEDIUM("Medium", 0xFFFBBC04.toInt()),
    HIGH("High", 0xFFEA4335.toInt()),
}

@Entity(tableName = "calendars")
data class CalendarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
    val visible: Boolean = true,
)

/**
 * Timed events store real instants (epoch millis). All-day events store "floating" dates as
 * UTC midnight, so they stay on the same calendar day when the device time zone changes.
 * [end] is exclusive in both cases.
 */
@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(
            entity = CalendarEntity::class,
            parentColumns = ["id"],
            childColumns = ["calendarId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("calendarId")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val calendarId: Long,
    val title: String,
    val description: String = "",
    val location: String = "",
    val start: Long,
    val end: Long,
    val allDay: Boolean = false,
    /** Overrides the calendar color when set. */
    val color: Int? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    /** Last day (epoch day, inclusive) on which the series may start an occurrence. */
    val recurrenceUntil: Long? = null,
    /** Epoch days of occurrences removed from the series. */
    val exceptions: List<Long> = emptyList(),
    /** Minutes before the start. For all-day events, relative to 9:00 on the first day. */
    val reminders: List<Int> = emptyList(),
    /** Tasks have a due time instead of a duration: [end] equals [start], or the next day if all-day. */
    @ColumnInfo(defaultValue = "EVENT") val kind: EventKind = EventKind.EVENT,
    /** Epoch days of the task occurrences that have been completed. */
    @ColumnInfo(defaultValue = "") val completions: List<Long> = emptyList(),
    val importance: Importance? = null,
) {
    val isTask: Boolean get() = kind == EventKind.TASK

    fun localStart(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = toLocal(start, allDay, zone)

    fun localEnd(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime = toLocal(end, allDay, zone)

    companion object {
        fun toLocal(millis: Long, allDay: Boolean, zone: ZoneId): LocalDateTime =
            LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), if (allDay) ZoneOffset.UTC else zone)

        fun toStored(time: LocalDateTime, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): Long =
            if (allDay) {
                time.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            } else {
                time.atZone(zone).toInstant().toEpochMilli()
            }
    }
}
