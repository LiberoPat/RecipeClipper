package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.PageSelection
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The Prompt API's reply as a [PageSelection] (#103), strictly: one JSON object, optionally in
 * one Markdown code fence, and nothing else around it; `name`, `yield` and the times a string
 * or null, `ingredients` and `steps` arrays of strings. Anything else (text around the object,
 * a number where a string belongs, a list of objects) is no answer at all, never a guess at
 * what the model meant. Keys it doesn't know are ignored. Pure.
 */
internal object PageSelectionJson {

    private val FENCE = Regex("^```(?:json)?\\s*\\n(.*)\\n\\s*```$", RegexOption.DOT_MATCHES_ALL)

    fun parse(reply: String): PageSelection? {
        val trimmed = reply.trim()
        val body = FENCE.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
        return try {
            val tokener = JSONTokener(body)
            val json = tokener.nextValue() as? JSONObject ?: return null
            if (tokener.nextClean() != 0.toChar()) return null
            PageSelection(
                name = string(json, "name"),
                ingredients = strings(json, "ingredients") ?: return null,
                steps = strings(json, "steps") ?: return null,
                yield = string(json, "yield"),
                prepTime = string(json, "prepTime"),
                cookTime = string(json, "cookTime"),
                totalTime = string(json, "totalTime")
            )
        } catch (e: JSONException) {
            null
        } catch (e: WrongType) {
            null
        }
    }

    private class WrongType : Exception()

    private fun string(json: JSONObject, key: String): String? = when (val v = json.opt(key)) {
        null, JSONObject.NULL -> null
        is String -> v.ifBlank { null }
        else -> throw WrongType()
    }

    /** The array's strings (absent or null is empty); null if it isn't an array of strings. */
    private fun strings(json: JSONObject, key: String): List<String>? = when (val v = json.opt(key)) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> List(v.length()) { v.opt(it) as? String ?: return null }.filter { it.isNotBlank() }
        else -> null
    }
}
