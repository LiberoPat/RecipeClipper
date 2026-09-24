package com.example.recipeclipper.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The word and density tables both apps share, from `shared/tables/` at the repository root
 * (#9). They are packaged as Java resources, so the same call works in the app and in JVM
 * tests, with no Context. iOS loads the same files, so a table is edited once for both.
 *
 * A missing or malformed table is a build mistake, not a runtime condition, so it throws.
 */
internal object SharedTables {

    const val LANGUAGE = "en"

    /** A table for the app's language, e.g. `"units"` for `tables/en/units.json`. */
    fun load(name: String): JSONObject = read("$LANGUAGE/$name")

    /** A table that no language changes, e.g. `"url"` for `tables/url.json`. */
    fun read(path: String): JSONObject {
        val resource = "/tables/$path.json"
        val stream = SharedTables::class.java.getResourceAsStream(resource)
            ?: error("Missing shared table $resource")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else List(array.length()) { array.getString(it) }

    fun objects(array: JSONArray): List<JSONObject> = List(array.length()) { array.getJSONObject(it) }

    /** Regex fragments joined as one non-capturing alternation. */
    fun alternation(fragments: List<String>): String = fragments.joinToString("|", "(?:", ")")

    /** Words that join the ends of a range, as one alternation. */
    val RANGE_WORDS: String by lazy { alternation(strings(load("ranges").getJSONArray("words"))) }
}
