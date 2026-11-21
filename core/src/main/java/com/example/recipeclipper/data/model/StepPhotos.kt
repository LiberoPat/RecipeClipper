package com.example.recipeclipper.data.model

import org.json.JSONArray

/**
 * One photo of a step (#433), as the screens show it: [url] a web image, [step] the step's index,
 * [number] what its badge says (the step text's own "(n)" marker for it, else its place among the
 * recipe's step photos, 1-based) and [position] its place in the viewer (0-based, every step photo
 * of the recipe in step order).
 */
data class StepPhoto(val url: String, val step: Int, val number: Int, val position: Int)

/**
 * The photos a source gives each step (#433): Cookist ends its steps in "(1)" or "(3) (4)",
 * markers that point at the step photos in its JSON-LD (`HowToStep.image`). Stored as
 * `Recipe.stepPhotos`, one list of web image links per step, lined up with the steps; an empty
 * list for a recipe with none. The text is never touched: the markers stay as written, and a badge
 * on each thumbnail carries the same number. Pure; iOS's `StepPhotos` is the same.
 */
object StepPhotos {

    /** The most photos one step keeps: a page can't fill the database with one step's images. */
    const val MAX_PER_STEP = 8

    // "(1)", "(13)": a bare number in brackets, as Cookist writes its photo markers.
    private val MARKER = Regex("""\((\d{1,2})\)""")

    /** The "(n)" markers in [step], in order. */
    fun markers(step: String): List<Int> = MARKER.findAll(step).map { it.groupValues[1].toInt() }.toList()

    /**
     * [photos] lined up with [stepCount] steps: one list per step (cut, or padded with empty
     * lists), or an empty list when no step has a photo, so a recipe without any stores nothing.
     */
    fun aligned(photos: List<List<String>>, stepCount: Int): List<List<String>> {
        val lined = List(stepCount) { photos.getOrNull(it).orEmpty() }
        return if (lined.all { it.isEmpty() }) emptyList() else lined
    }

    /**
     * Each step's photos as the screens show them. A step's markers number its photos when there
     * are exactly as many markers as photos ("(3) (4)" for two); otherwise its photos are numbered
     * by their place among the recipe's step photos. [photos] not lined up with [steps] is read
     * as [aligned] reads it.
     */
    fun numbered(steps: List<String>, photos: List<List<String>>): List<List<StepPhoto>> {
        val lined = aligned(photos, steps.size)
        if (lined.isEmpty()) return emptyList()
        var position = 0
        return lined.mapIndexed { step, urls ->
            val marks = markers(steps[step]).takeIf { it.size == urls.size }
            urls.mapIndexed { i, url ->
                StepPhoto(url, step, marks?.get(i) ?: (position + 1), position).also { position++ }
            }
        }
    }

    /** Every step photo of [numbered], in step order: the viewer's pages. */
    fun all(numbered: List<List<StepPhoto>>): List<StepPhoto> = numbered.flatten()

    /**
     * The photos after an edit (#29) changed [oldSteps] into [newSteps]. A step whose text is
     * unchanged keeps its photos wherever it moved to (a reorder, a step removed or added around
     * it); then the steps left over on each side are paired in order, but only when as many were
     * left on both, so an edited step's text keeps its photos. Anything else (a step removed, or
     * steps added and changed at once) leaves the unpaired new steps with none.
     */
    fun afterEdit(oldSteps: List<String>, oldPhotos: List<List<String>>, newSteps: List<String>): List<List<String>> {
        val old = aligned(oldPhotos, oldSteps.size)
        if (old.isEmpty()) return emptyList()
        val taken = BooleanArray(oldSteps.size)
        val result = MutableList<List<String>?>(newSteps.size) { null }
        newSteps.forEachIndexed { i, step ->
            val match = oldSteps.indices.firstOrNull { !taken[it] && oldSteps[it] == step }
            if (match != null) {
                taken[match] = true
                result[i] = old[match]
            }
        }
        val leftOld = oldSteps.indices.filter { !taken[it] }
        val leftNew = newSteps.indices.filter { result[it] == null }
        if (leftOld.size == leftNew.size) leftNew.zip(leftOld).forEach { (n, o) -> result[n] = old[o] }
        return aligned(result.map { it.orEmpty() }, newSteps.size)
    }

    /** [photos] as stored: a JSON array of arrays of links, or null when there are none. */
    fun encode(photos: List<List<String>>): String? =
        if (photos.all { it.isEmpty() }) null else JSONArray(photos.map { JSONArray(it) }).toString()

    /** What [encode] wrote; anything unreadable (or null) is no photos, never a crash. Each link
     *  is checked to be a web image again ([WebImageUrl]), so a stored or imported row can't name
     *  a file on the device. */
    fun decode(json: String?): List<List<String>> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val outer = JSONArray(json)
            val steps = List(outer.length()) { i ->
                val inner = outer.optJSONArray(i)
                if (inner == null) emptyList() else List(inner.length()) { inner.optString(it) }.mapNotNull(WebImageUrl::of).distinct().take(MAX_PER_STEP)
            }
            if (steps.all { it.isEmpty() }) emptyList() else steps
        } catch (e: Exception) {
            emptyList()
        }
    }
}
