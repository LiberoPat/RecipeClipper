package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.LineRun
import com.example.recipeclipper.data.model.PagePick
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The Prompt API's reply as a [PagePick] (#103, line runs since #128), strictly: one JSON
 * object, optionally in one Markdown code fence, and nothing else around it; `name`, `yield` and
 * the times a string or null, `ingredients` and `steps` arrays of `{"first": n, "last": n}` with
 * whole numbers. Anything else (text around the object, a number where a string belongs, a run
 * as text or as a pair) is no answer at all, never a guess at what the model meant. Keys it
 * doesn't know are ignored. Pure.
 */
internal object PagePickJson {

    private val FENCE = Regex("^```(?:json)?\\s*\\n(.*)\\n\\s*```$", RegexOption.DOT_MATCHES_ALL)

    fun parse(reply: String): PagePick? {
        val trimmed = reply.trim()
        val body = FENCE.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
        return try {
            val tokener = JSONTokener(body)
            val json = tokener.nextValue() as? JSONObject ?: return null
            if (tokener.nextClean() != 0.toChar()) return null
            PagePick(
                name = string(json, "name"),
                ingredients = runs(json, "ingredients") ?: return null,
                steps = runs(json, "steps") ?: return null,
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

    /** The array's runs (absent or null is none); null if it isn't an array of runs. */
    private fun runs(json: JSONObject, key: String): List<LineRun>? = when (val v = json.opt(key)) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> List(v.length()) { i ->
            val run = v.opt(i) as? JSONObject ?: return null
            LineRun(run.opt("first") as? Int ?: return null, run.opt("last") as? Int ?: return null)
        }
        else -> null
    }
}
