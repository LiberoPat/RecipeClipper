package com.example.recipeclipper.data.model

/** Where a selection on the page can go. The photo is picked by tapping an image instead. */
enum class ClipField { NAME, INGREDIENTS, STEPS, PHOTO }

/**
 * A recipe being clipped by hand from a page with no recipe data (#37). Immutable and pure:
 * every change returns a new draft, so undo is simply "the draft before the last change".
 *
 * Assigning to a field **replaces** what it held (the owner's decision), for every field.
 * [marks] records, per field, the id of the highlight the page drew for that assignment, so
 * the page can show exactly the marks this draft holds after an undo or a clear. Mark ids come
 * from [nextMark], so they never repeat within one draft.
 *
 * [serves] and [totalTime] are only ever typed by the user in Review; they are never read from
 * the page.
 */
data class ClipDraft(
    val sourceUrl: String,
    val name: String = "",
    val ingredients: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val photo: String? = null,
    val serves: String = "",
    val totalTime: String = "",
    val marks: Map<ClipField, String> = emptyMap(),
    val nextMark: Int = 1
) {

    /** True when nothing has been assigned or typed: not worth keeping as a draft. */
    val isEmpty: Boolean
        get() = name.isBlank() && ingredients.none { it.isNotBlank() } &&
            steps.none { it.isNotBlank() } && photo == null && serves.isBlank() && totalTime.isBlank()

    /** The parser's own rule: a name, plus ingredients or steps. */
    val canFinish: Boolean
        get() = name.isNotBlank() &&
            (ingredients.any { it.isNotBlank() } || steps.any { it.isNotBlank() })

    /** Lines a field holds, as counted on the toolbar: 1 for a name or a photo. */
    fun count(field: ClipField): Int = when (field) {
        ClipField.NAME -> if (name.isBlank()) 0 else 1
        ClipField.INGREDIENTS -> ingredients.count { it.isNotBlank() }
        ClipField.STEPS -> steps.count { it.isNotBlank() }
        ClipField.PHOTO -> if (photo == null) 0 else 1
    }

    /** The id the next assignment's page mark will use. */
    val pendingMarkId: String get() = "m$nextMark"

    /**
     * Puts the selected [text] into [field], replacing what was there, and records the page
     * mark under [pendingMarkId]. A selection with no text in it changes nothing. For
     * [ClipField.PHOTO], [text] is the image's address.
     */
    fun assign(field: ClipField, text: String): ClipDraft {
        val assigned = when (field) {
            ClipField.NAME -> ClipSelection.name(text).takeIf { it.isNotEmpty() }?.let { copy(name = it) }
            ClipField.INGREDIENTS -> ClipSelection.lines(text).takeIf { it.isNotEmpty() }?.let { copy(ingredients = it) }
            ClipField.STEPS -> ClipSelection.lines(text).takeIf { it.isNotEmpty() }?.let { copy(steps = it) }
            ClipField.PHOTO -> text.trim().takeIf { it.isNotEmpty() }?.let { copy(photo = it) }
        } ?: return this
        return assigned.copy(marks = marks + (field to pendingMarkId), nextMark = nextMark + 1)
    }

    /** Empties [field] and drops its page mark. */
    fun clear(field: ClipField): ClipDraft {
        val cleared = when (field) {
            ClipField.NAME -> copy(name = "")
            ClipField.INGREDIENTS -> copy(ingredients = emptyList())
            ClipField.STEPS -> copy(steps = emptyList())
            ClipField.PHOTO -> copy(photo = null)
        }
        return cleared.copy(marks = marks - field)
    }

    // --- Review: editing lines by hand. Blank lines are kept while editing, dropped on save.

    fun lines(field: ClipField): List<String> = when (field) {
        ClipField.INGREDIENTS -> ingredients
        ClipField.STEPS -> steps
        else -> throw IllegalArgumentException("$field has no lines")
    }

    private fun withLines(field: ClipField, lines: List<String>): ClipDraft = when (field) {
        ClipField.INGREDIENTS -> copy(ingredients = lines)
        ClipField.STEPS -> copy(steps = lines)
        else -> throw IllegalArgumentException("$field has no lines")
    }

    /** Replaces line [index] of [field]; an index out of range changes nothing. */
    fun editLine(field: ClipField, index: Int, text: String): ClipDraft {
        val current = lines(field)
        if (index !in current.indices) return this
        return withLines(field, current.toMutableList().also { it[index] = text })
    }

    fun removeLine(field: ClipField, index: Int): ClipDraft {
        val current = lines(field)
        if (index !in current.indices) return this
        return withLines(field, current.filterIndexed { i, _ -> i != index })
    }

    /** Adds an empty line at the end, for the user to type into. */
    fun addLine(field: ClipField): ClipDraft = withLines(field, lines(field) + "")

    /**
     * The recipe to save: everything trimmed, blank lines dropped, and a serving count or time
     * only when one was typed. Null until [canFinish].
     */
    fun toRecipe(): Recipe? {
        if (!canFinish) return null
        return Recipe(
            name = name.trim(),
            image = photo,
            ingredients = ingredients.map { it.trim() }.filter { it.isNotEmpty() },
            instructions = steps.map { it.trim() }.filter { it.isNotEmpty() },
            prepTime = null,
            cookTime = null,
            totalTime = totalTime.trim().ifEmpty { null },
            yield = serves.trim().ifEmpty { null },
            sourceUrl = sourceUrl
        )
    }
}
