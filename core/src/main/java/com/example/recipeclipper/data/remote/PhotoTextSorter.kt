package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.IngredientScaler
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.FractionLookAlike
import com.example.recipeclipper.data.model.MeasureUnit
import com.example.recipeclipper.data.model.SharedTables
import org.json.JSONObject

/**
 * One line of text the device read from a photo (#198), in reading order, with the
 * recogniser's [confidence] (0 to 1) when it gives one: Vision always does; ML Kit gives it per
 * line and per word, and null means it said nothing. [page] is the picture it was read from (0
 * for the first) and [box] where on it (#286), so the review can show the line beside its strip
 * of the photo; null when the reader didn't say.
 */
data class PhotoLine(
    val text: String,
    val confidence: Float? = null,
    val page: Int? = null,
    val box: PhotoBox? = null
)

/**
 * Where a line was read on its picture (#286): a rectangle in fractions of the picture's width
 * and height, 0 to 1, measured from its top-left corner, in the picture the right way up (as the
 * recogniser saw it and the review shows it).
 */
data class PhotoBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /**
     * The strip the review shows for this box (#286) on a [width] by [height] picture: the box
     * with a little room around it, [PAD] of the line's height above and below and as many
     * pixels either side, clamped to the picture.
     */
    fun padded(width: Int, height: Int): PhotoBox {
        val dy = (bottom - top) * PAD
        val dx = if (width > 0) dy * height / width else 0f
        return PhotoBox(
            (left - dx).coerceAtLeast(0f), (top - dy).coerceAtLeast(0f),
            (right + dx).coerceAtMost(1f), (bottom + dy).coerceAtMost(1f)
        )
    }

    companion object {
        /** The room around a line's strip, in its height (#286). */
        const val PAD = 0.35f

        /**
         * A box from a rectangle in [width] by [height] pixels, top-left origin (ML Kit's
         * `boundingBox` in the bitmap it read), clamped to the picture; null if nothing of it is
         * on the picture.
         */
        fun fromPixels(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): PhotoBox? {
            if (width <= 0 || height <= 0) return null
            val l = (left.toFloat() / width).coerceIn(0f, 1f)
            val t = (top.toFloat() / height).coerceIn(0f, 1f)
            val r = (right.toFloat() / width).coerceIn(0f, 1f)
            val b = (bottom.toFloat() / height).coerceIn(0f, 1f)
            return if (r > l && b > t) PhotoBox(l, t, r, b) else null
        }
    }
}

/**
 * Why a line read from a photo is listed for the cook to check (#198). Flagged only: nothing is
 * corrected or suggested.
 */
enum class CheckReason {
    /** The recogniser was unsure of the line ([PhotoTextSorter.LOW_CONFIDENCE]). */
    UNSURE,

    /** Its amount is shaped like a misreading ("11/2", "l/2", "1 cupraisins", "1 oup sugar"). */
    MISREAD,

    /** Its amount holds a fraction glyph's look-alike, or glyphs run together, which the fraction
     *  chips replace (#245, #276): always with [MISREAD]. */
    FRACTION,

    /** Its amount is more of its unit than a recipe asks for ("34 tsp salt", #249, #281). */
    LARGE
}

/**
 * One line listed to check (#198, #283): [raw] as it is in the editor's boxes, which a fraction
 * chip changes; [shown] as the list shows it, without list markup ("▢", "•", "3)"); and why.
 * [page] and [box] are where it was read (#286: its [PhotoLine]'s), null when the reader didn't
 * say.
 */
data class LineFlag(
    val raw: String,
    val shown: String,
    val reasons: Set<CheckReason>,
    val page: Int? = null,
    val box: PhotoBox? = null
) {
    /** Listed only because its amount looks too large: the photo was read clearly there, so it
     *  gets a note of its own rather than the heading (#281). */
    val largeOnly: Boolean get() = reasons == setOf(CheckReason.LARGE)

    /** Its amount holds a look-alike the fraction chips replace (#245). */
    val chips: Boolean get() = CheckReason.FRACTION in reasons
}

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
    /** The lines to check, each once, in the order shown, with why ([PhotoTextSorter.check]). */
    val flags: List<LineFlag> = emptyList(),
    /** The language the lines' words clearly say (#208), which they were read in; null when
     *  nothing was clear and English was used. */
    val language: String? = null
) {
    /** Nothing at all was read. */
    val isEmpty: Boolean get() = ingredients.isEmpty() && instructions.isEmpty()

    /** The [flags]' lines, as they are in the boxes. */
    val uncertain: List<String> get() = flags.map { it.raw }

    /** The [flags]' lines listed only because their amount looks too large ([LineFlag.largeOnly]). */
    val large: List<String> get() = flags.filter { it.largeOnly }.map { it.raw }
}

/**
 * Sorts the lines read from a photo (#198) with the same splitter that reads a typed Reddit post
 * ([RecipeTextSplitter]), so headers, lists and numbered steps are recognised the same way.
 *
 * **Nothing is invented or corrected.** Every line is the recogniser's text (only trimmed, and
 * cleaned by the splitter as a typed post's lines are); an amount it misread stays misread, and
 * the lines to check are listed in [PhotoReading.flags] with why: the recogniser was unsure of the
 * line, or its amount is shaped like a misreading or looks too large ([check]). When the splitter
 * finds no recipe the lines are returned unsorted, never guessed at.
 *
 * The lines are read in the language their words say (#208, detected as a page's is, #14), else
 * English: that language's headers, and its unit words for [check].
 *
 * Pure: lines in, data out. The iOS port (`PhotoTextSorter.swift`) follows it line for line.
 */
object PhotoTextSorter {

    /** Below this, a line is marked "check this". Vision's accurate recogniser answers 0.3 for
     *  text it is unsure of and 0.5 or 1 otherwise; ML Kit's scores fall on the same scale. */
    const val LOW_CONFIDENCE = 0.5f

    fun sort(lines: List<PhotoLine>): PhotoReading {
        // A piece of a line keeps the whole line's confidence, page and box (#286).
        val read = lines.flatMap { line -> line.text.split(LINE_BREAK).map { line.copy(text = it.trim()) } }
            .filter { it.text.isNotEmpty() }
        val text = read.joinToString("\n") { it.text }
        val language = RecipeTextSplitter.languageOf(text)
        val words = RecipeTextSplitter.wordsFor(language)
        // Each line cleaned once, as the splitter reads it: the sorting, the checks and the list
        // all use it (#283). A line read again (a card's two photos) is cleaned once.
        val clean = HashMap<String, RecipeTextSplitter.Line>()
        val cleaned = read.map { clean.getOrPut(it.text) { RecipeTextSplitter.line(it.text, words) } }
        val split = if (read.isEmpty()) null else RecipeTextSplitter.splitLines(cleaned, words)
        val listed = Listed(read, cleaned, words)
        if (split == null || (split.ingredients.isEmpty() && split.instructions.isEmpty())) {
            // Unsorted, each line is checked and shown as the splitter cleans it ("▢ 2% cups oats"
            // as "2% cups oats"), and stays in the box as read.
            read.indices.forEach { listed.add(read[it].text, cleaned[it].text, it) }
            return PhotoReading(read.map { it.text }, emptyList(), sorted = false, flags = listed.flags(), language = language)
        }
        (split.ingredients + split.instructions).forEach { listed.add(it.text, it.text, it.source) }
        val recipe = split.recipe
        return PhotoReading(
            ingredients = recipe.ingredients,
            instructions = recipe.instructions,
            yield = recipe.yield,
            prepTime = recipe.prepTime,
            cookTime = recipe.cookTime,
            totalTime = recipe.totalTime,
            sorted = true,
            flags = listed.flags(),
            language = language
        )
    }

    /** The lines to list, gathered line by line: each box line ([LineFlag.raw]) once, in the
     *  order first shown, with the reasons of every copy of it, and the page and box of the
     *  first (#286). The splitter keeps each read line whole (it never joins two), so an
     *  ingredient or step was read where its own line was. */
    private class Listed(
        val read: List<PhotoLine>, val cleaned: List<RecipeTextSplitter.Line>, val words: LanguageWords
    ) {
        private val shown = LinkedHashMap<String, String>()
        private val where = HashMap<String, PhotoLine>()
        private val reasons = HashMap<String, MutableSet<CheckReason>>()
        private val checked = HashMap<Pair<String, Boolean>, Set<CheckReason>>()

        /** [raw], a box's line, from read line [source], checked and shown as [text]. Its
         *  confidence and "Step 3" label are its own line's, never another's that quotes it. */
        fun add(raw: String, text: String, source: Int) {
            val show = text.ifEmpty { raw }
            val labelled = cleaned[source].step
            // A line read again (a card's two photos) is checked once.
            val found = checked.getOrPut(text to labelled) { check(text, words, labelled) }.toMutableSet()
            // A chip replaces the look-alike in the line as shown, so the box's line must hold it.
            if (show !in raw) found -= CheckReason.FRACTION
            if ((read[source].confidence ?: 1f) < LOW_CONFIDENCE) found += CheckReason.UNSURE
            shown.putIfAbsent(raw, show)
            where.putIfAbsent(raw, read[source])
            reasons.getOrPut(raw) { mutableSetOf() } += found
        }

        fun flags(): List<LineFlag> = shown.mapNotNull { (raw, show) ->
            reasons.getValue(raw).takeIf { it.isNotEmpty() }?.let { found ->
                val at = where.getValue(raw)
                LineFlag(raw, show, found.sortedBy { it.ordinal }.toSet(), at.page, at.box)
            }
        }
    }

    /**
     * Why [line], as the splitter cleaned it, is to be checked against the photo however sure the
     * recogniser was (#198); empty if it isn't. The text is never changed. [CheckReason.MISREAD]:
     * - an improper fraction over 2 to 8, "11/2" or "31/3": most likely "1 1/2" or "3 1/3" with
     *   its space lost (and the scaler would read 5½);
     * - a digit beside a letter that looks like one: "l/2", "O.5", "1/Z", "1O", "35o°F";
     * - a unit glued to the word after it, where the scaler reads no unit: "1 cupraisins", in
     *   [words]' language ("2 ELZucker", "1 tazaharina"); never in one written without spaces;
     * - a fraction glyph read as something else, or run into a digit or another glyph ("3¾4",
     *   "½⅓", never written) (#245, #276): see [fractionSlot], which is also
     *   [CheckReason.FRACTION], the chips;
     * - a near-miss of a unit where the unit goes, "1 oup sugar" or "2 tbps butter" (#276): the
     *   language's splitter.json `unitLookAlikes`, a short list, so "oz", "g" or "ml" never trip.
     *
     * [CheckReason.LARGE]: an amount too big for its unit, "34 tsp salt" or "23 cups oats" (#249),
     * judged by [implausible] only on a line that starts with an amount and its unit, as an
     * ingredient line does. So it runs on the steps too, where a section after the method lands
     * ("34 tsp vanilla" under a glaze, #283), and a sentence ("Stir in 34 tsp salt.") is never
     * judged; never on a line that had a "Step 3" label ([labelled]) or that holds "=" (a
     * conversion, "48 tsp = 1 cup").
     */
    internal fun check(line: String, words: LanguageWords = LanguageWords.ENGLISH, labelled: Boolean = false): Set<CheckReason> {
        val lead = Lead(line, words)
        val chips = fractionSlot(line, words) != null
        return buildSet {
            if (chips || misread(lead, words)) add(CheckReason.MISREAD)
            if (chips) add(CheckReason.FRACTION)
            if (!labelled && '=' !in line && implausible(lead, words)) add(CheckReason.LARGE)
        }
    }

    /** Every misread shape [check] looks for but the fraction look-alikes ([fractionSlot]). */
    private fun misread(lead: Lead, words: LanguageWords): Boolean {
        val line = lead.line
        val rest = lead.rest
        return IMPROPER.findAll(line).any { m ->
            // The numerator without leading zeros: two digits or more is always above 2 to 8.
            val n = m.groupValues[1].trimStart('0')
            val d = m.groupValues[2].toInt()
            d in 2..8 && (n.length > 1 || (n.isNotEmpty() && n.toInt() > d))
        } || LOOK_ALIKE.containsMatchIn(line) ||
            (rest != null && (gluedUnit(rest, lead, words) || RecipeTextSplitter.unitLookAlike(rest, words)))
    }

    /** [line]'s leading amount as [IngredientScaler] reads it ([match]), and the text after it. */
    private class Lead(val line: String, words: LanguageWords) {
        val p = IngredientScaler.patterns(words)
        val match = p.leading.find(line)
        val rest = match?.let { line.substring(it.range.last + 1) }
    }

    /**
     * True when [line]'s leading amount is more of its unit than a recipe asks for
     * (`shared/tables/plausible-amounts.json`, #249): a fraction glyph read as digits, "34 tsp
     * salt" for ¾ or "23 cups oats" for 2 ⅔. The amount and unit are [IngredientScaler]'s
     * leading ones, so only a line that starts with an amount and its unit is judged. An amount
     * written exactly as a fraction's digits ("12" for ½: the table's `fractionShapes`) is held
     * to its unit's lower `fractionShapedMaximums` bar where there is one, so "12 tbsp vinegar"
     * is flagged and "16 tablespoons butter" isn't (#281). Either end of a range is held to the
     * unit's own maximum, never that lower bar ("12–14 lb turkey" is fine, "30–40 lb" isn't:
     * the owner's call, #283).
     *
     * Unlike the scaler, which reads only the first number, a plain whole number then another
     * before the unit ("2 23 cups oats", ML Kit's 2 ⅔; "1 12 cups" for 1½) is judged by the
     * second alone; after a range, a fraction or a decimal, the second number isn't taken. Flagged
     * only: nothing is suggested or changed. A bare count ("12 eggs"), a unit the table doesn't
     * list, a temperature ("180 C"; never a fraction's digits, "1 58 C sugar" is 1 ⅝ cups, #283)
     * and a language whose amounts follow the name are never checked.
     */
    internal fun implausible(line: String, words: LanguageWords = LanguageWords.ENGLISH): Boolean =
        implausible(Lead(line, words), words)

    private fun implausible(lead: Lead, words: LanguageWords): Boolean {
        val p = lead.p
        if (p.amountAfterName) return false
        val match = lead.match ?: return false
        var rest = lead.rest ?: return false
        var amounts = listOf(match.groupValues[2], match.groupValues[4]).filter { it.isNotEmpty() }
        val range = amounts.size > 1
        if (amounts.size == 1 && amounts[0].all { it in '0'..'9' }) SECOND_NUMBER.find(rest)?.let {
            amounts = listOf(it.groupValues[1])
            rest = rest.substring(it.range.last + 1)
        }
        val unit = p.unitAtStart.find(rest)?.let { MeasureUnit.fromText(it.groupValues[1], words) } ?: return false
        val table = PLAUSIBLE
        if (amounts.none { it in table.shapes } && p.temperature(amounts, rest)) return false
        val max = table.maximums[unit] ?: return false
        val shapedMax = if (range) max else table.fractionShaped[unit] ?: max
        return amounts.any { (p.parse(it) ?: 0.0) > if (it in table.shapes) shapedMax else max }
    }

    /** Read at the first size check, not when the object loads: the editor reads [FRACTIONS]. */
    private val PLAUSIBLE: PlausibleAmounts by lazy { PlausibleAmounts.from(SharedTables.read("plausible-amounts")) }

    // "2 23 cups": a second whole number straight after the leading one.
    private val SECOND_NUMBER = Regex("""^\s+(\d+)(?![\d/⁄.,])""")

    // A recogniser's line holds no break, but a caller's may: split as the splitter's lines are.
    private val LINE_BREAK = Regex("\r\n|\r|\n")

    /** The fractions offered to replace a look-alike (#245), in the order shown. */
    val FRACTIONS: List<String> get() = FractionLookAlike.FRACTIONS

    /**
     * What a fraction chip replaces in [line] (#245, #276): its amount's look-alike
     * ([FractionLookAlike.slot]), else a glyph run into a digit or another glyph
     * ([FractionLookAlike.gluedRun]). Null if neither.
     */
    fun fractionSlot(line: String, words: LanguageWords = LanguageWords.ENGLISH): IntRange? =
        FractionLookAlike.slot(line, IngredientScaler.patterns(words).unitAtStart) ?: FractionLookAlike.gluedRun(line)

    /**
     * [line] with its [fractionSlot] replaced by [fraction], the cook's choice; null if it has none.
     * A whole number glued before a glyph run is kept and set apart ("1½⅓ cup" → "1 ⅓ cup", #276).
     */
    fun replaceFraction(line: String, fraction: String, words: LanguageWords = LanguageWords.ENGLISH): String? {
        FractionLookAlike.slot(line, IngredientScaler.patterns(words).unitAtStart)?.let { return line.replaceRange(it, fraction) }
        val run = FractionLookAlike.gluedRun(line) ?: return null
        val apart = run.first > 0 && line[run.first - 1] in '0'..'9'
        return line.replaceRange(run, if (apart) " $fraction" else fraction)
    }

    /**
     * [flag] once the cook taps [fraction] (#245): the look-alike replaced in the line as shown,
     * and so in the box's line, which holds it ("▢ 2% cups oats" → "▢ 2⅔ cups oats"); its chips
     * go, and it stays listed. Null if it has no chips.
     */
    fun replaceFraction(flag: LineFlag, fraction: String, words: LanguageWords = LanguageWords.ENGLISH): LineFlag? {
        if (!flag.chips) return null
        val shown = replaceFraction(flag.shown, fraction, words) ?: return null
        val at = flag.raw.indexOf(flag.shown).takeIf { it >= 0 } ?: return null
        return flag.copy(
            raw = flag.raw.replaceRange(at, at + flag.shown.length, shown), shown = shown,
            reasons = flag.reasons - CheckReason.FRACTION
        )
    }

    /**
     * [flag] once the cook has changed its line in the box to [line] (#286): the line as it is now,
     * and as listed (cleaned as [sort] cleans a line, so "▢" and "•" go). It stays listed with its
     * reasons, since nothing is checked again, but its chips stay only while the line still holds
     * a look-alike for them to replace. Its page and box are kept.
     */
    fun edited(flag: LineFlag, line: String, words: LanguageWords = LanguageWords.ENGLISH): LineFlag {
        if (line == flag.raw) return flag
        val shown = RecipeTextSplitter.line(line, words).text.ifEmpty { line }
        val chips = flag.chips && shown in line && fractionSlot(shown, words) != null
        return flag.copy(
            raw = line, shown = shown,
            reasons = if (chips) flag.reasons else flag.reasons - CheckReason.FRACTION
        )
    }

    /** A unit glued to the next word in [rest], after the leading amount, where the scaler reads none. */
    private fun gluedUnit(rest: String, lead: Lead, words: LanguageWords): Boolean =
        words.spaced && !lead.p.unitAtStart.containsMatchIn(rest) && RecipeTextSplitter.gluedUnit(rest, words)

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
}

/**
 * `shared/tables/plausible-amounts.json` (#249, #281), read strictly, the same on both platforms
 * (#283): every maximum a JSON number (not a string, not true or false), finite and above zero,
 * keyed by a [MeasureUnit]; each fraction-shaped bar below its unit's maximum; every fraction
 * shape a JSON string of digits. Anything else throws, as [SharedTables] does for a malformed
 * table.
 */
internal class PlausibleAmounts(
    val maximums: Map<MeasureUnit, Double>,
    val fractionShaped: Map<MeasureUnit, Double>,
    val shapes: Set<String>
) {
    companion object {
        fun from(table: JSONObject): PlausibleAmounts {
            fun units(key: String): Map<MeasureUnit, Double> = table.getJSONObject(key).let { t ->
                t.keys().asSequence().associate { name ->
                    // getDouble would read "10" and "NaN" too: only a JSON number counts.
                    val bar = (t.get(name) as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 }
                        ?: throw IllegalArgumentException("plausible-amounts.json: $key.$name isn't a number above 0")
                    MeasureUnit.valueOf(name) to bar
                }
            }
            val maximums = units("maximums")
            val shaped = units("fractionShapedMaximums")
            shaped.forEach { (unit, bar) ->
                require(bar < (maximums[unit] ?: 0.0)) { "plausible-amounts.json: $unit's fraction-shaped bar isn't below its maximum" }
            }
            // Android's getString would give a number as a string: only a JSON string counts.
            val array = table.getJSONArray("fractionShapes")
            val shapes = List(array.length()) { i ->
                (array.get(i) as? String)?.takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'9' } }
                    ?: throw IllegalArgumentException("plausible-amounts.json: a fraction shape isn't a string of digits")
            }.toSet()
            return PlausibleAmounts(maximums, shaped, shapes)
        }
    }
}
