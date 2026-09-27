package com.example.recipeclipper.data.local

import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.SavedTimer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The `recipes.cookState` column: a [CookProgress] as JSON text, or NULL when there is none.
 * One column rather than a timers table, so undo-delete and the re-share upsert carry it with
 * the row and nothing else. iOS writes the same shape (`CookStateJSON`).
 *
 * `{"active":true,"currentStep":2,"doneSteps":[0,1],
 *   "timers":[{"step":1,"totalSeconds":600,"remainingSeconds":600,"endsAt":1700000000000}]}`
 *
 * `endsAt` is present only while a timer runs. Text that can't be read decodes to an empty
 * [CookProgress]: losing a cook's place is better than failing to open the recipe.
 */
object CookStateJson {

    fun encode(progress: CookProgress): String? {
        if (progress.isEmpty) return null
        val timers = JSONArray()
        progress.timers.toSortedMap().forEach { (step, timer) ->
            timers.put(
                JSONObject()
                    .put("step", step)
                    .put("totalSeconds", timer.totalSeconds)
                    .put("remainingSeconds", timer.remainingSeconds)
                    .apply { timer.endsAt?.let { put("endsAt", it) } }
            )
        }
        return JSONObject()
            .put("active", progress.active)
            .put("currentStep", progress.currentStep)
            .put("doneSteps", JSONArray(progress.doneSteps.sorted()))
            .put("timers", timers)
            .toString()
    }

    fun decode(json: String?): CookProgress {
        if (json.isNullOrBlank()) return CookProgress()
        return try {
            val root = JSONObject(json)
            val done = root.optJSONArray("doneSteps") ?: JSONArray()
            val timers = root.optJSONArray("timers") ?: JSONArray()
            CookProgress(
                active = root.optBoolean("active", false),
                currentStep = root.optInt("currentStep", 0),
                doneSteps = (0 until done.length()).map { done.getInt(it) }.toSet(),
                timers = (0 until timers.length()).associate { i ->
                    val t = timers.getJSONObject(i)
                    t.getInt("step") to SavedTimer(
                        totalSeconds = t.getInt("totalSeconds"),
                        remainingSeconds = t.getInt("remainingSeconds"),
                        endsAt = if (t.has("endsAt")) t.getLong("endsAt") else null
                    )
                }
            )
        } catch (e: JSONException) {
            CookProgress()
        }
    }
}
