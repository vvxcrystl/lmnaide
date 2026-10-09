package dev.lmnaide.calendar.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun longsToString(values: List<Long>): String = values.joinToString(",")

    @TypeConverter
    fun stringToLongs(value: String): List<Long> =
        value.split(',').mapNotNull { it.trim().toLongOrNull() }

    @TypeConverter
    fun intsToString(values: List<Int>): String = values.joinToString(",")

    @TypeConverter
    fun stringToInts(value: String): List<Int> =
        value.split(',').mapNotNull { it.trim().toIntOrNull() }
}
