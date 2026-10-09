package dev.lmnaide.calendar.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendars ORDER BY id")
    fun observeAll(): Flow<List<CalendarEntity>>

    @Query("SELECT COUNT(*) FROM calendars")
    suspend fun count(): Int

    @Insert
    suspend fun insert(calendar: CalendarEntity): Long

    @Update
    suspend fun update(calendar: CalendarEntity)

    @Delete
    suspend fun delete(calendar: CalendarEntity)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events")
    fun observeAll(): Flow<List<EventEntity>>

    @Query("SELECT * FROM events")
    suspend fun getAll(): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    fun observe(id: Long): Flow<EventEntity?>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun get(id: Long): EventEntity?

    @Insert
    suspend fun insert(event: EventEntity): Long

    @Update
    suspend fun update(event: EventEntity)

    @Delete
    suspend fun delete(event: EventEntity)
}
