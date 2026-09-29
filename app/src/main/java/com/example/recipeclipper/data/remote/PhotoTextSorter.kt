package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.IngredientScaler
import com.example.recipeclipper.data.model.LanguageWords

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
    /** The lines shown to check: text the recogniser was unsure of, or an amount shaped like a misreading. */
    val uncertain: List<String> = emptyList(),
    /** The language the lines' words clearly say (#208), which they were read in; null when
     *  nothing was clear and English was used. */
    val language: String? = null
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
 * the lines it was unsure of, or whose amount is shaped like a misreading ("11/2"), are named in
 * [PhotoReading.uncertain] for the cook to check against
 * the photo. When the splitter finds no recipe the lines are returned unsorted, never guessed at.
 *
 * The lines are read in the language their words say (#208, detected as a page's is, #14), else
 * English: that language's headers, and its unit words for [suspect].
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
        val text = read.joinToString("\n") { it.text }
        val language = RecipeTextSplitter.languageOf(text)
        val words = RecipeTextSplitter.wordsFor(language)
        val split = if (read.isEmpty()) null else RecipeTextSplitter.split(text, words)
        if (split == null || (split.ingredients.isEmpty() && split.instructions.isEmpty())) {
            val all = read.map { it.text }
            return PhotoReading(all, emptyList(), sorted = false, uncertain = toCheck(all, unsure, words), language = language)
        }
        return PhotoReading(
            ingredients = split.ingredients,
            instructions = split.instructions,
            yield = split.yield,
            prepTime = split.prepTime,
            cookTime = split.cookTime,
            totalTime = split.totalTime,
            sorted = true,
            uncertain = toCheck(split.ingredients + split.instructions, unsure, words),
            language = language
        )
    }

    /**
     * The [shown] lines to check: those [marked] from text the recogniser was unsure of, then any
     * other whose amount looks misread ([suspect]), however sure the recogniser was.
     */
    private fun toCheck(shown: List<String>, unsure: List<String>, words: LanguageWords): List<String> {
        val low = marked(shown, unsure).toSet()
        return shown.filter { it in low || suspect(it, words) }.distinct()
    }

    /**
     * True when [line]'s amount has a shape a recogniser gives for a misread one (#198), so the
     * cook checks it against the photo. The text is never changed:
     * - an improper fraction over 2 to 8, "11/2" or "31/3": most likely "1 1/2" or "3 1/3" with
     *   its space lost (and the scaler would read 5½);
     * - a digit beside a letter that looks like one: "l/2", "O.5", "1/Z", "1O", "35o°F";
     * - a unit glued to the word after it, where the scaler reads no unit: "1 cupraisins", in
     *   [words]' language ("2 ELZucker", "1 tazaharina"); never in one written without spaces.
     */
    internal fun suspect(line: String, words: LanguageWords = LanguageWords.ENGLISH): Boolean =
        IMPROPER.findAll(line).any { m ->
            // The numerator without leading zeros: two digits or more is always above 2 to 8.
            val n = m.groupValues[1].trimStart('0')
            val d = m.groupValues[2].toInt()
            d in 2..8 && (n.length > 1 || (n.isNotEmpty() && n.toInt() > d))
        } || LOOK_ALIKE.containsMatchIn(line) || gluedUnit(line, words)

    private fun gluedUnit(line: String, words: LanguageWords): Boolean {
        if (!words.spaced) return false
        val p = IngredientScaler.patterns(words)
        val lead = p.leading.find(line) ?: return false
        val rest = line.substring(lead.range.last + 1)
        return !p.unitAtStart.containsMatchIn(rest) && RecipeTextSplitter.gluedUnit(rest, words)
    }

    // "11/2": digits, a slash, one digit, and no more digits or slashes around it.
    private val IMPROPER = Regex("""(?<![\d/⁄.,])(\d+)[/⁄](\d)(?![\d/⁄])""")

    // A letter read for a digit, or a digit's neighbour read as a letter, beside a fraction's
    // slash or a decimal point: "l/2", "O.5", "l2", "1/Z", "1.O", "1O", "35o°F". A lowercase "o"
    // or "l" straight after a number is not one ("1oz", "1l"), only one standing alone.
    private val LOOK_ALIKE = Regex(
        """(?<![\p{L}\d])[lIOo|][/⁄.,]?\d|\d[/⁄.,][lIOoZS](?!\p{L})|\d[Oo](?!\p{L})"""
    )

    // A unit word run into the next ("cupraisins", "tbspsugar") is the language's splitter.json
    // gluedUnits (RecipeTextSplitter.gluedUnit). Units of one or two letters ("g", "c", "l",
    // "oz") are left out, since "2 green onions" starts with one; "cupcake" is a word of its own.

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
