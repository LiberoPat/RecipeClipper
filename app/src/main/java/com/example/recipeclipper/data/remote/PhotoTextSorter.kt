package com.example.recipeclipper.data.remote

/**
 * One line of text the device read from a photo (#198), in reading order, with the
 * recogniser's [confidence] (0 to 1) when it gives one: Vision always does; ML Kit gives it per
 * line and per word, and null means it said nothing.
 */
data class PhotoLine(val text: String, val confidence: Float? = null)

/**
 * What [PhotoTextSorter.sort] made of a photo's lines: the recipe editor's content, before the
 * cook checks it. When [sorted] is false the splitter found no recipe, and [ingredients] holds
 * every line read, as read, for the cook to sort by hand.
 */
data class PhotoReading(
    val ingredients: List<String>,
    val instructions: List<String>,
    val yield: String? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null,
    val sorted: Boolean,
    /** The lines shown that came from text the recogniser was unsure of: "check this". */
    val uncertain: List<String> = emptyList()
) {
    /** Nothing at all was read. */
    val isEmpty: Boolean get() = ingredients.isEmpty() && instructions.isEmpty()
}

/**
 * Sorts the lines read from a photo (#198) with the same splitter that reads a typed Reddit post
 * ([RecipeTextSplitter]), so headers, lists and numbered steps are recognised the same way.
 *
 * **Nothing is invented or corrected.** Every line is the recogniser's text (only trimmed, and
 * cleaned by the splitter as a typed post's lines are); an amount it misread stays misread, and
 * the lines it was unsure of are named in [PhotoReading.uncertain] for the cook to check against
 * the photo. When the splitter finds no recipe the lines are returned unsorted, never guessed at.
 *
 * Pure: lines in, data out. The iOS port (`PhotoTextSorter.swift`) follows it line for line.
 */
object PhotoTextSorter {

    /** Below this, a line is marked "check this". Vision's accurate recogniser answers 0.3 for
     *  text it is unsure of and 0.5 or 1 otherwise; ML Kit's scores fall on the same scale. */
    const val LOW_CONFIDENCE = 0.5f

    fun sort(lines: List<PhotoLine>): PhotoReading {
        val read = lines.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
        val unsure = read.filter { (it.confidence ?: 1f) < LOW_CONFIDENCE }.map { it.text }
        val split = if (read.isEmpty()) null else RecipeTextSplitter.split(read.joinToString("\n") { it.text })
        if (split == null || (split.ingredients.isEmpty() && split.instructions.isEmpty())) {
            val all = read.map { it.text }
            return PhotoReading(all, emptyList(), sorted = false, uncertain = marked(all, unsure))
        }
        return PhotoReading(
            ingredients = split.ingredients,
            instructions = split.instructions,
            yield = split.yield,
            prepTime = split.prepTime,
            cookTime = split.cookTime,
            totalTime = split.totalTime,
            sorted = true,
            uncertain = marked(split.ingredients + split.instructions, unsure)
        )
    }

    /**
     * The [shown] lines that hold, or are held in, a line the recogniser was unsure of. The
     * splitter only takes markup off a line ("1." or "•"), so a line shown still contains the
     * text it came from. A piece shorter than four characters ("1", "Mix") is matched only as
     * a whole line, so it can't mark every line it happens to appear in.
     */
    internal fun marked(shown: List<String>, unsure: List<String>): List<String> {
        if (unsure.isEmpty()) return emptyList()
        return shown.filter { line ->
            unsure.any { u ->
                line == u || (u.length >= MIN_PART && line.contains(u)) || (line.length >= MIN_PART && u.contains(line))
            }
        }.distinct()
    }

    private const val MIN_PART = 4
}
