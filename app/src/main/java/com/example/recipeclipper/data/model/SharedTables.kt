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

    /** A language's table, e.g. `"units"` for `tables/en/units.json`. See [LanguageWords]. */
    fun load(name: String, language: String): JSONObject = read("$language/$name")

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

    /**
     * Regex fragments joined as one non-capturing alternation. No fragments never matches:
     * an empty alternation would match everywhere.
     */
    fun alternation(fragments: List<String>): String =
        if (fragments.isEmpty()) "(?!)" else fragments.joinToString("|", "(?:", ")")
}
