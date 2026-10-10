package dev.lmnaide.calendar.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun peopleToString(people: List<dev.lmnaide.calendar.contacts.EventPerson>): String =
        org.json.JSONArray().apply { people.forEach { person ->
            put(org.json.JSONObject().put("name", person.name).put("uri", person.lookupUri))
        } }.toString()

    @TypeConverter
    fun stringToPeople(value: String): List<dev.lmnaide.calendar.contacts.EventPerson> {
        val array = org.json.JSONArray(value)
        return (0 until array.length()).map { index ->
            val person = array.getJSONObject(index)
            dev.lmnaide.calendar.contacts.EventPerson(person.getString("name"), person.getString("uri"))
        }
    }

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
