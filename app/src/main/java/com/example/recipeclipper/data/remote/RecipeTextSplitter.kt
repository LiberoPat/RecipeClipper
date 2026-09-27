package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.Durations
import org.jsoup.Jsoup

/** What [RecipeTextSplitter] found in a block of text. The name comes from elsewhere
 *  (a Reddit post's title), so it isn't here. */
data class SplitRecipe(
    val ingredients: List<String>,
    val instructions: List<String>,
    val yield: String?,
    val prepTime: String?,
    val cookTime: String?,
    val totalTime: String?
)

/**
 * Turns free text that is laid out as a recipe (a Reddit post body, or a comment with the
 * recipe or a transcription of a recipe card) into ingredients and steps.
 *
 * **It never guesses.** Text splits only when it carries its own structure: an ingredients
 * block and steps, each with lines in it. Without both, the answer is null: a paragraph of
 * prose that happens to mention flour is not a recipe.
 *
 *  - **Ingredients** start at a line that is nothing but an ingredients header ("Ingredients",
 *    "**Ingredients:**", "## You'll need", "INGREDIENTS (serves 4)"). A header set apart as one
 *    (bold, a Markdown heading, a trailing colon or capitals) may also be a few words ending in
 *    the keyword ("**Dry ingredients**", "Ingredient amounts:") or a misspelling of it
 *    ("Ingredeints:"). With no header, the lines just above the steps are the ingredients when
 *    at least two of them are amounts ("2 cups flour", "Salt – 1/2 tsp") and every one reads
 *    like an ingredient (an amount, a list item or a short line): the note or story above them
 *    stops the block.
 *  - **Steps** start at an instructions header ("Directions", "Method", "**Cooking steps:**"),
 *    or, with no header, where a numbered list starts at 1 or at "Step 1" (after ingredients, or
 *    below an ingredients block as above).
 *  - Sections may repeat (a sauce, then a dough) and come in either order. Lines under a notes
 *    header ("Notes", "Tips", "Equipment", "Edit:") are dropped, as is everything before the
 *    first section (the story), except a labelled yield ("Serves 4") or time ("Prep time:
 *    10 min"), which are also read inside the ingredients.
 *  - A bold line or heading inside a section that isn't an amount is a group's name, written
 *    with a colon ("**Sauce**" is "Sauce:"), as the app writes every group heading. A header
 *    with a group's word in it ("Dry ingredients") becomes one too.
 *
 * Reddit bodies are Markdown, so each line loses its list marker, heading hashes, emphasis,
 * link syntax and backslash escapes (the Reddit editor escapes a typed "1." as "1\."), after
 * going through the same `stripHtml` as every other extracted string. Steps are one per line,
 * as a plain-string `recipeInstructions` is.
 *
 * Pure: text in, data out. The iOS port (`RecipeTextSplitter.swift`) follows it line for line.
 */
object RecipeTextSplitter {

    enum class Section { INGREDIENTS, INSTRUCTIONS, END }

    /**
     * One line of Markdown as plain text ([text]), with what its markup said about it:
     * [strong] when it was wholly bold or a heading, [italic] when wholly in italics, [listed]
     * when it came after a list marker, [number] for a numbered item ("3." or "Step 3"), and
     * [step] when that number came from a "Step 3" label. A bare "Step 3" has empty [text].
     */
    class Line(
        val text: String,
        val strong: Boolean = false,
        val italic: Boolean = false,
        val listed: Boolean = false,
        val number: Int? = null,
        val step: Boolean = false,
    ) {
        /** Set apart the way a header is: bold, a heading, italics, a trailing colon or capitals. */
        val headerLike: Boolean
            get() = strong || italic || text.endsWith(":") || isCapitals(text)
    }

    /** A header: the [section] it opens, and the group name ("Dry ingredients:") it adds, if any. */
    class Header(val section: Section, val label: String? = null)

    private val INGREDIENT_HEADERS = setOf(
        "ingredients", "ingredient", "ingredient list", "ingredients list",
        "you will need", "you'll need", "what you need", "what you'll need", "what you will need",
        "things you'll need", "things you will need",
    )
    private val INSTRUCTION_HEADERS = setOf(
        "instructions", "directions", "method", "steps", "preparation", "procedure",
        "how to make", "how to make it",
    )
    private val END_HEADERS = setOf(
        "notes", "note", "recipe notes", "tips", "tip", "nutrition", "nutrition facts",
        "source", "sources", "equipment",
    )

    /** The last word of a header set apart as one ("**Dry ingredients**", "COOKING STEPS"). */
    private val KEYWORDS = mapOf(
        "ingredients" to Section.INGREDIENTS, "ingredient" to Section.INGREDIENTS,
        "instructions" to Section.INSTRUCTIONS, "instruction" to Section.INSTRUCTIONS,
        "directions" to Section.INSTRUCTIONS, "direction" to Section.INSTRUCTIONS,
        "method" to Section.INSTRUCTIONS, "steps" to Section.INSTRUCTIONS,
        "preparation" to Section.INSTRUCTIONS, "procedure" to Section.INSTRUCTIONS,
        "notes" to Section.END, "note" to Section.END, "tips" to Section.END, "tip" to Section.END,
        "equipment" to Section.END,
    )

    /** Words before the keyword that name no group ("Cooking steps", "The ingredients",
     *  "Method / Directions"), so the header adds no group heading. */
    private val PLAIN_WORDS = setOf(
        "the", "main", "all", "full", "basic", "recipe", "cooking", "baking", "needed", "list",
        "amounts", "amount", "required", "used", "easy", "simple", "quick", "your", "my", "our",
        "step", "by", "step-by-step", "and", "&", "+", "-", "–",
    ) + KEYWORDS.keys

    /** A line that starts with one of these is a step or a sentence, never a header
     *  ("**Mix the dry ingredients**"). */
    private val VERBS = setOf(
        "add", "assemble", "bake", "beat", "blend", "check", "chop", "combine", "cook", "cream",
        "cut", "follow", "fold", "gather", "get", "heat", "measure", "melt", "mix", "make", "place",
        "pour", "prep", "prepare", "put", "read", "repeat", "season", "serve", "set", "sift",
        "stir", "toss", "use", "weigh", "whisk",
    )

    /** One-word headers a typo away from the real word ("Ingredeints", "Intructions"). */
    private val MISSPELT = listOf(
        "ingredients" to Section.INGREDIENTS,
        "instructions" to Section.INSTRUCTIONS,
        "directions" to Section.INSTRUCTIONS,
    )

    /** "Ingredients for the cake:" is a header only with its colon; without one the line is
     *  more likely a sentence ("Ingredients for this are cheap"). */
    private val FOR_HEADER = Regex("^(ingredients|instructions|directions|method)\\s+for\\s+.+:$")

    /** "Edit:", "EDIT 2:", "Update:" and the like start a trailing note, never a step. */
    private val EDIT_LINE = Regex("^(?:edit|update|eta)\\b[^:]{0,12}:", RegexOption.IGNORE_CASE)

    /** "(serves 4)", "<for 3~4 people>", "[metric]" at the end of a header. */
    private val TRAILING_PARENTHETICAL = Regex("\\s*[(<\\[][^)>\\]]*[)>\\]]$")
    private val WORD_BREAK = Regex("[\\s/]+")

    /** The header [line] is, or null for an ordinary line. A numbered item or a step never is. */
    fun header(line: Line): Header? {
        if (line.number != null || line.step) return null
        val text = line.text
        if (EDIT_LINE.containsMatchIn(text)) return Header(Section.END)
        val lower = text.lowercase().trim().replace('’', '\'')
        if (FOR_HEADER.matches(lower)) {
            return Header(if (lower.startsWith("ingredients")) Section.INGREDIENTS else Section.INSTRUCTIONS)
        }
        val base = bare(lower)
        when (base) {
            in INGREDIENT_HEADERS -> return Header(Section.INGREDIENTS)
            in INSTRUCTION_HEADERS -> return Header(Section.INSTRUCTIONS)
            in END_HEADERS -> return Header(Section.END)
        }
        if (!line.headerLike) return null
        val words = base.split(WORD_BREAK).filter { it.isNotEmpty() }
        if (words.size in 2..4) {
            val modifiers = words.dropLast(1)
            val section = KEYWORDS[words.last()]
            if (section != null && modifiers.none { it in VERBS } &&
                modifiers.all { w -> w.all { it.isLetter() || it in "'&+-–" } }
            ) {
                val plain = section == Section.END || modifiers.all { it in PLAIN_WORDS }
                return Header(section, if (plain) null else text.trimEnd(':', ' ') + ":")
            }
            // "Ingredients needed", "Ingredient amounts"
            if ((words[0] == "ingredients" || words[0] == "ingredient") && words.drop(1).all { it in PLAIN_WORDS }) {
                return Header(Section.INGREDIENTS)
            }
        }
        if (words.size == 1 && base.length >= 8) {
            MISSPELT.firstOrNull { editDistance(base, it.first) <= 2 }?.let { return Header(it.second) }
        }
        return null
    }

    /** The section a line of Markdown opens, or null for an ordinary line. */
    fun section(raw: String): Section? = header(line(raw))?.section

    /** A lowercased header without what may surround it: a leading emoji or symbol, a trailing
     *  colon, dash, emoji or parenthetical ("ingredients (serves 4)"). */
    private fun bare(lower: String): String {
        var s = lower.trimStart { !it.isLetterOrDigit() }
        s = s.trimEnd(':', ' ', '-', '–', '—')
        s = TRAILING_PARENTHETICAL.replace(s, "")
        return s.trimEnd { !it.isLetterOrDigit() }
    }

    private fun isCapitals(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 3 && letters.none { it.isLowerCase() }
    }

    /** Levenshtein distance, for the misspelt headers: short words, so the plain table. */
    internal fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private val BLOCKQUOTE = Regex("^(?:>\\s*)+")
    private val HEADING = Regex("^#{1,6}(?!\\d)\\s*")
    private val RULE = Regex("^(?:[-*_=]\\s*){3,}$")
    /** "-", "*", "+" and dashes need a space after them ("-5°C" and "*Ingredients*" aren't
     *  list items); a bullet glyph ("•", "・", the "▢" of a copied recipe card) doesn't. */
    private val BULLET = Regex("^(?:[-*+–—]\\s+|[•·・▪◦▢□☐○●►✓✔]\\s*)")
    private val NUMBERED = Regex("^\\(?(\\d{1,2})[.)]\\s+")
    private val STEP_LABEL = Regex("^step\\s*(\\d{1,2})\\s*(?:[:.)\\-–—]\\s*|$)", RegexOption.IGNORE_CASE)
    private val CHECKBOX = Regex("^\\[[ xX]?]\\s+")
    private val LINK = Regex("\\[([^\\]]+)]\\((?:[^()]|\\([^)]*\\))*\\)")
    private val EMPHASIS = Regex("\\*\\*|__|~~")
    /** Italics inside a line ("Glaze *(for grilling)*"); a lone "*" (a footnote) stays. */
    private val ITALIC = Regex("(?<![A-Za-z0-9_*])\\*(?![\\s*])([^*]+?)(?<!\\s)\\*(?![A-Za-z0-9_*])")
    private val ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>~^|])")

    /**
     * One line of Markdown as plain text: HTML stripped and entities decoded first (Jsoup's
     * `text()`, which also trims and collapses whitespace), backslash escapes undone, then the
     * quote, heading, rule, list marker ("-", "*", "•", "▢", "1.", "2)", "Step 3:"), link and
     * emphasis syntax removed. A number followed by a dot and no space ("1.5 cups") is a
     * quantity, not a list marker.
     */
    fun line(raw: String): Line {
        var s = Jsoup.parse(raw).text()
        s = ESCAPE.replace(s) { it.groupValues[1] }.trimEnd('\\').trim()
        s = BLOCKQUOTE.replace(s, "")
        val heading = HEADING.containsMatchIn(s)
        s = HEADING.replace(s, "")
        if (RULE.matches(s)) return Line("")
        var listed = false
        var number: Int? = null
        BULLET.find(s)?.let { s = s.substring(it.range.last + 1); listed = true }
        CHECKBOX.find(s)?.let { s = s.substring(it.range.last + 1); listed = true }
        NUMBERED.find(s)?.let { s = s.substring(it.range.last + 1); listed = true; number = it.groupValues[1].toInt() }

        // "**Ingredients**", "**Ingredient amounts**:", "*optional*": wholly bold or italic.
        val core = s.trimEnd(':').trim()
        val bold = core.length > 4 && (core.startsWith("**") && core.endsWith("**") || core.startsWith("__") && core.endsWith("__"))
        s = LINK.replace(s) { it.groupValues[1] }
        s = EMPHASIS.replace(s, "").trim()
        var italic = false
        if (!bold && s.length > 2 && (s.first() == '*' || s.first() == '_') && s.last() == s.first()) {
            s = s.substring(1, s.length - 1).trim()
            italic = true
        }
        s = ITALIC.replace(s) { it.groupValues[1] }
        var step = false
        STEP_LABEL.find(s)?.let { s = s.substring(it.range.last + 1).trim(); step = true; number = it.groupValues[1].toInt() }
        return Line(s, strong = heading || bold, italic = italic, listed = listed, number = number, step = step)
    }

    /** [line]'s text alone. */
    fun cleanLine(raw: String): String = line(raw).text

    /** Every line of [text], cleaned, blank ones included. */
    fun lines(text: String): List<Line> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map(::line)

    /** Fractions a recipe writes as one character. */
    private const val FRACTIONS = "½⅓⅔¼¾⅛⅜⅝⅞"
    private const val UNITS = "cups?|c|tsps?|teaspoons?|tbsps?|tbs|tablespoons?|fl\\.?\\s*oz|oz|ounces?|lbs?|pounds?|" +
        "g|grams?|kg|kilos?|kilograms?|ml|millilit(?:er|re)s?|l|lit(?:er|re)s?|quarts?|qts?|pints?|gallons?|" +
        "pinch(?:es)?|dash(?:es)?|cloves?|cans?|jars?|sticks?|handfuls?|bunch(?:es)?|sprigs?|slices?|pieces?|" +
        "packages?|packets?|pkgs?|heads?|stalks?|leaves|inch(?:es)?|cm"

    /** An amount: a line that starts with a number or fraction, or has one before a unit
     *  ("Salt – 1/2 tsp", "Mint: 2 handfuls"). */
    private val AMOUNT = Regex(
        "^(?:about\\s+|approx\\.?\\s+|~\\s*)?[0-9$FRACTIONS]|[0-9$FRACTIONS]\\s*(?:$UNITS)\\.?(?![A-Za-z])",
        RegexOption.IGNORE_CASE
    )

    fun isAmount(text: String): Boolean = AMOUNT.containsMatchIn(text)

    /** The longest line that reads like an ingredient without an amount or list marker. */
    private const val SHORT_LINE = 40

    /** An ingredients block with no header needs at least this many amounts. */
    const val MIN_AMOUNTS = 2

    /** A line with a bare address in it is a pointer elsewhere ("More on my blog:
     *  https://…"), never an ingredient or a step. */
    private fun isPointer(text: String): Boolean = text.contains("http://") || text.contains("https://")

    private fun readsLikeIngredient(line: Line): Boolean =
        isAmount(line.text) || line.listed ||
            (line.text.length <= SHORT_LINE && line.text.last() !in ".!?")

    /** A bold line or heading inside a section that isn't an amount names a group ("Sauce:"),
     *  as does a bold step title ("**Step 2: Make the sauce**"); a numbered item never does. */
    private fun written(line: Line): String {
        val t = line.text
        val group = line.strong && (line.number == null || line.step) && !t.endsWith(":") && t.length <= 50 &&
            t.last() !in ".!?" && !isAmount(t)
        return if (group) "$t:" else t
    }

    /** "Serves 4" and "Makes 12 cookies" are kept whole; "Servings: 4" and "Yield: 12
     *  cookies" lose their label, as a schema.org `recipeYield` would read. */
    private val YIELD = Regex(
        "^(serves|makes|servings|yields?|portions)\\b\\s*:?\\s*(\\S.*)$",
        RegexOption.IGNORE_CASE
    )
    private val TIME = Regex(
        "^(prep(?:aration)?(?:\\s+time)?|cook(?:ing)?(?:\\s+time)?|bake(?:\\s+time)?|baking\\s+time|total(?:\\s+time)?)\\s*:\\s*(\\S.*)$",
        RegexOption.IGNORE_CASE
    )

    fun split(text: String): SplitRecipe? {
        val ingredients = mutableListOf<String>()
        val instructions = mutableListOf<String>()
        var yield: String? = null
        var prep: String? = null
        var cook: String? = null
        var total: String? = null
        var state: Section? = null
        // The lines before any section, for an ingredients block with no header.
        val before = mutableListOf<Line>()

        /** A labelled yield or time, taken; false for any other line. */
        fun labelled(line: String): Boolean {
            YIELD.matchEntire(line)?.let { m ->
                if (yield == null) {
                    val label = m.groupValues[1].lowercase()
                    yield = if (label == "serves" || label == "makes") line else m.groupValues[2].trim()
                }
                return true
            }
            TIME.matchEntire(line)?.let { m ->
                val label = m.groupValues[1].lowercase()
                val value = Durations.format(m.groupValues[2])
                when {
                    label.startsWith("prep") -> if (prep == null) prep = value
                    label.startsWith("total") -> if (total == null) total = value
                    else -> if (cook == null) cook = value
                }
                return true
            }
            return false
        }

        /** The ingredients with no header: the lines just above the steps, read upwards until
         *  one doesn't read like an ingredient. */
        fun ingredientsAbove(): Boolean {
            val block = before.asReversed().takeWhile(::readsLikeIngredient).asReversed()
                .dropWhile { !isAmount(it.text) && !it.listed }
            if (block.count { isAmount(it.text) } < MIN_AMOUNTS) return false
            block.mapTo(ingredients, ::written)
            before.clear()
            return true
        }

        for (line in lines(text)) {
            if (line.text.isEmpty() && !line.step) continue
            val header = header(line)
            if (header != null) {
                if (header.section == Section.INSTRUCTIONS && ingredients.isEmpty()) ingredientsAbove()
                state = header.section
                before.clear()
                when (header.section) {
                    Section.INGREDIENTS -> header.label?.let(ingredients::add)
                    Section.INSTRUCTIONS -> header.label?.let(instructions::add)
                    Section.END -> Unit
                }
                continue
            }
            // A numbered list starting at 1 (or "Step 1") with no header over it is the steps.
            if (line.number == 1 && !isAmount(line.text)) {
                if (state == Section.INGREDIENTS && ingredients.any { !it.endsWith(":") } ||
                    state == null && ingredientsAbove()
                ) {
                    state = Section.INSTRUCTIONS
                }
            }
            if (line.text.isEmpty() || isPointer(line.text)) continue // a bare "Step 2", or a link
            when (state) {
                Section.INGREDIENTS -> if (!labelled(line.text)) ingredients.add(written(line))
                Section.INSTRUCTIONS -> instructions.add(written(line))
                Section.END -> Unit
                null -> if (!labelled(line.text)) before.add(line)
            }
        }
        if (ingredients.none { !it.endsWith(":") } || instructions.none { !it.endsWith(":") }) return null
        return SplitRecipe(ingredients, instructions, yield, prep, cook, total)
    }
}
