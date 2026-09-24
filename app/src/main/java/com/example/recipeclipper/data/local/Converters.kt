package com.example.recipeclipper.data.local

import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * Room stores the ingredient and step lists, and the set of ticked ingredients, as JSON
 * text in a single column. JSON (not a comma join) so an ingredient containing a comma or
 * a quote survives the round trip.
 */
class Converters {

    @TypeConverter
    fun stringListToJson(list: List<String>): String = JSONArray(list).toString()

    @TypeConverter
    fun jsonToStringList(json: String): List<String> {
        val array = JSONArray(json)
        return List(array.length()) { array.getString(it) }
    }

    @TypeConverter
    fun intSetToJson(set: Set<Int>): String = JSONArray(set.sorted()).toString()

    @TypeConverter
    fun jsonToIntSet(json: String): Set<Int> {
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getInt(it) }.toSet()
    }
}
