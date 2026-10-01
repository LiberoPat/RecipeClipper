package com.example.recipeclipper.data.model

/** Where a selection on the page can go. The photo is picked by tapping an image instead. */
enum class ClipField {
    NAME, INGREDIENTS, STEPS, PHOTO;

    /** A name or a photo is replaced by each assignment; ingredients and steps add up (#237). */
    val replaces: Boolean get() = this == NAME || this == PHOTO
}

/**
 * One add to a [ClipDraft], and the page mark drawn for it (#37, #237): [id] names the mark,
 * [lines] are what it put into [field] (a name, or a photo's address, as its one line), so
 * removing it takes out exactly those lines.
 */
data class ClipMark(val id: String, val field: ClipField, val lines: List<String>)

/**
 * A recipe being clipped by hand from a page with no recipe data (#37). Immutable and pure:
 * every change returns a new draft, so undo is simply "the draft before the last change".
 *
 * The name and the photo are **replaced** by each assignment; ingredients and steps are
 * **added** after what the field holds, so separate blocks of a page (a list, then a second
 * list under another heading) add up (the owner's call, #237). [marks] records each add with
 * the id of the highlight the page drew for it, so the page can show exactly the marks this
 * draft holds after an undo, and one add can be taken back on its own ([removeMark]). Mark ids
 * come from [nextMark], so they never repeat within one draft.
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
    val marks: List<ClipMark> = emptyList(),
    val nextMark: Int = 1
) {

    /** True when nothing has been assigned or typed: not worth keeping as a draft. */
    val isEmpty: Boolean
        get() = name.isBlank() && ingredients.none { it.isNotBlank() } &&
            steps.none { it.isNotBlank() } && photo == null && serves.isBlank() && totalTime.isBlank()

    /** Some ingredients or steps. */
    val hasLines: Boolean
        get() = ingredients.any { it.isNotBlank() } || steps.any { it.isNotBlank() }

    /** The parser's own rule: a name, plus ingredients or steps. */
    val canFinish: Boolean
        get() = name.isNotBlank() && hasLines

    /** Lines a field holds, as counted on the toolbar: 1 for a name or a photo. */
    fun count(field: ClipField): Int = when (field) {
        ClipField.NAME -> if (name.isBlank()) 0 else 1
        ClipField.INGREDIENTS -> ingredients.count { it.isNotBlank() }
        ClipField.STEPS -> steps.count { it.isNotBlank() }
        ClipField.PHOTO -> if (photo == null) 0 else 1
    }

    /**
     * The field the cook is pointed to next (#237): the first of name, ingredients, steps and
     * photo still empty, or null when every one holds something.
     */
    val nextField: ClipField?
        get() = ClipField.entries.firstOrNull { count(it) == 0 }

    /** The id the next assignment's page mark will use. */
    val pendingMarkId: String get() = "m$nextMark"

    /**
     * Puts the selected [text] into [field] and records the page mark under [pendingMarkId]: a
     * name or a photo replaces what was there (and its mark); ingredients and steps are added
     * after what the field holds. A selection with no text in it changes nothing. For
     * [ClipField.PHOTO], [text] is the image's address.
     */
    fun assign(field: ClipField, text: String): ClipDraft {
        val lines = when (field) {
            ClipField.NAME -> listOfNotNull(ClipSelection.name(text).takeIf { it.isNotEmpty() })
            ClipField.INGREDIENTS, ClipField.STEPS -> ClipSelection.lines(text)
            ClipField.PHOTO -> listOfNotNull(text.trim().takeIf { it.isNotEmpty() })
        }
        if (lines.isEmpty()) return this
        val assigned = when (field) {
            ClipField.NAME -> copy(name = lines.single())
            ClipField.INGREDIENTS -> copy(ingredients = ingredients + lines)
            ClipField.STEPS -> copy(steps = steps + lines)
            ClipField.PHOTO -> copy(photo = lines.single())
        }
        val kept = if (field.replaces) marks.filter { it.field != field } else marks
        return assigned.copy(marks = kept + ClipMark(pendingMarkId, field, lines), nextMark = nextMark + 1)
    }

    /** The add a page mark stands for, while this draft still holds it. */
    fun mark(id: String): ClipMark? = marks.firstOrNull { it.id == id }

    /**
     * Takes back one add: a name or a photo empties its field; ingredients or steps lose the
     * lines that add put there (for each, the first line still equal to it, so a line edited by
     * hand in Review since then stays). An unknown id changes nothing.
     */
    fun removeMark(id: String): ClipDraft {
        val mark = mark(id) ?: return this
        val removed = when (mark.field) {
            ClipField.NAME, ClipField.PHOTO -> clear(mark.field)
            ClipField.INGREDIENTS, ClipField.STEPS -> {
                val left = lines(mark.field).toMutableList()
                mark.lines.forEach { line -> left.indexOf(line).takeIf { it >= 0 }?.let { left.removeAt(it) } }
                withLines(mark.field, left)
            }
        }
        return removed.copy(marks = marks.filter { it.id != id })
    }

    /** Empties [field] and drops its page marks. */
    fun clear(field: ClipField): ClipDraft {
        val cleared = when (field) {
            ClipField.NAME -> copy(name = "")
            ClipField.INGREDIENTS -> copy(ingredients = emptyList())
            ClipField.STEPS -> copy(steps = emptyList())
            ClipField.PHOTO -> copy(photo = null)
        }
        return cleared.copy(marks = marks.filter { it.field != field })
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
