package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.Durations
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.SharedTables
import org.jsoup.Jsoup

/** What [RecipeTextSplitter] found in a block of text. The name comes from elsewhere
 *  (a Reddit post's title), so it isn't here. [language] is the language the text was read in
 *  when its words said so (#208); null when nothing was clear and English was used. */
data class SplitRecipe(
    val ingredients: List<String>,
    val instructions: List<String>,
    val yield: String?,
    val prepTime: String?,
    val cookTime: String?,
    val totalTime: String?,
    val language: String? = null
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
 * **Every word is the text's own language's** (#208): `shared/tables/<language>/splitter.json`,
 * through [LanguageWords], English by default. [detectAndSplit] picks the language the way a
 * page's is detected (#14), from the text's words, else English; languages are never merged.
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

    /** One language's splitter words (`splitter.json`), built once per language. */
    private class Words(words: LanguageWords) {
        private val table = words.table("splitter")
        private fun list(key: String) = SharedTables.strings(table.optJSONArray(key))
        private fun list(obj: String, key: String) = SharedTables.strings(table.getJSONObject(obj).optJSONArray(key))
        private fun alt(fragments: List<String>) = SharedTables.alternation(fragments)

        val ingredientHeaders = list("ingredientHeaders").toSet()
        val instructionHeaders = list("instructionHeaders").toSet()
        val endHeaders = list("endHeaders").toSet()

        /** The word that ends a header set apart as one ("**Dry ingredients**", "COOKING STEPS"),
         *  or, where [keywordFirst], starts it ("**Ingredientes secos**"). */
        val keywords: Map<String, Section> = buildMap {
            list("keywords", "ingredients").forEach { putIfAbsent(it, Section.INGREDIENTS) }
            list("keywords", "instructions").forEach { putIfAbsent(it, Section.INSTRUCTIONS) }
            list("keywords", "end").forEach { putIfAbsent(it, Section.END) }
        }
        val ingredientKeywords = list("keywords", "ingredients").toSet()
        val keywordFirst = table.optBoolean("keywordFirst", false)

        /** Words beside the keyword that name no group ("Cooking steps", "The ingredients"). */
        val plainWords = list("plainWords").toSet() + keywords.keys

        /** A header whose other words hold one of these is a step or a sentence
         *  ("**Mix the dry ingredients**"). */
        val verbs = list("verbs").toSet()

        /** One-word headers a typo away from the real word ("Ingredeints", "Intructions"). */
        val misspelt: List<Pair<String, Section>> =
            list("misspelt", "ingredients").map { it to Section.INGREDIENTS } +
                list("misspelt", "instructions").map { it to Section.INSTRUCTIONS }

        private val servingWords = SharedTables.strings(words.table("yield").optJSONArray("serving"))
        private val approx = alt(list("approx"))
        private val count = """(?:$approx)?\d+(?:\s*[-–]\s*\d+)?"""

        /** "Ingredients for the cake:" is a header only with its colon; without one the line is
         *  more likely a sentence ("Ingredients for this are cheap"). */
        val forHeader = Regex(
            "^(?:(${alt(list("forHeader", "ingredients"))})|${alt(list("forHeader", "instructions"))})" +
                "\\s+${alt(list("forHeader", "joiners"))}\\s+.+:$"
        )

        /** "Ingredients for 4 servings", "Zutaten für 4 Personen", "Ingredienti per 4 persone":
         *  a header without a colon, since the serving words say what it is. */
        val servesHeader = Regex(
            "^${alt(list("forHeader", "ingredients"))}\\s+${alt(list("forHeader", "joiners"))}\\s+$count\\s+${alt(servingWords)}$",
            RegexOption.IGNORE_CASE
        )

        /** "Edit:", "EDIT 2:", "Update:" and the like start a trailing note, never a step. */
        val editLine = Regex("^${alt(list("editWords"))}\\b[^:]{0,12}:", RegexOption.IGNORE_CASE)

        val stepLabel = Regex("^${alt(list("stepLabels"))}\\s*(\\d{1,2})\\s*(?:[:.)\\-–—]\\s*|$)", RegexOption.IGNORE_CASE)

        /** "Serves 4" and "Makes 12 cookies" are kept whole; "Servings: 4" and "Yield: 12
         *  cookies" lose their label, as a schema.org `recipeYield` would read. */
        val yieldLine = Regex(
            "^(${alt(list("yield", "whole") + list("yield", "labels"))})\\b\\s*:?\\s*(\\S.*)$",
            RegexOption.IGNORE_CASE
        )
        val yieldWhole = Regex("^${alt(list("yield", "whole"))}$", RegexOption.IGNORE_CASE)

        /** "For 4 people", "Pour 6 personnes": kept whole, as "Serves 4" is. */
        val yieldFor = Regex(
            "^${alt(list("yield", "for"))}\\s+$count\\s+${alt(servingWords)}\\.?$", RegexOption.IGNORE_CASE
        )

        /** "Prep time: 10 min": group 1 prep, 2 cook, 3 total, 4 the time. */
        val time = Regex(
            "^(?:(${alt(list("times", "prep"))})|(${alt(list("times", "cook"))})|(${alt(list("times", "total"))}))" +
                "\\s*:\\s*(\\S.*)$",
            RegexOption.IGNORE_CASE
        )

        /** An amount: a line that starts with a number or fraction, or has one before a unit
         *  ("Salt – 1/2 tsp", "Mint: 2 handfuls"), or has one of the language's own shapes. */
        val amount = Regex(
            "^(?:$approx|~\\s*)?[0-9$FRACTIONS]|[0-9$FRACTIONS]\\s*${alt(list("amountUnits"))}\\.?(?![A-Za-z])" +
                list("amountPatterns").joinToString("") { "|$it" },
            RegexOption.IGNORE_CASE
        )

        /** A unit word run into the next ("cupraisins", "2 ELZucker"): taken whole, so a plural
         *  the scaler doesn't read ("cuillères") isn't cut back to a unit and a glued letter. */
        val glued = Regex(
            "^\\s*(?!${alt(list("gluedExceptions"))})(?>${alt(list("gluedUnits"))})\\.?\\p{L}",
            RegexOption.IGNORE_CASE
        )
    }

    private fun words(words: LanguageWords): Words = words.compiled(Words::class) { Words(it) }

    /** The language [text]'s words clearly say (#14's detection), or null. */
    fun languageOf(text: String): String? = LanguageWords.detect(text)

    /** The words to read text in [language] with: its own, else English. */
    fun wordsFor(language: String?): LanguageWords = LanguageWords.forTag(language) ?: LanguageWords.ENGLISH

    /**
     * [split] in the language [text]'s words say (with [context], such as the post's title,
     * read for detection too), else English (#208). The result carries that language.
     */
    fun detectAndSplit(text: String, context: String = ""): SplitRecipe? {
        val language = languageOf(if (context.isEmpty()) text else "$context\n$text")
        return split(text, wordsFor(language))?.copy(language = language)
    }

    /** Whether [rest], a line after its amount, starts with one of [words]' units run into the next
     *  word ("cupraisins"), for [PhotoTextSorter.suspect]. */
    internal fun gluedUnit(rest: String, words: LanguageWords): Boolean = words(words).glued.containsMatchIn(rest)

    /** "(serves 4)", "<for 3~4 people>", "[metric]", "（2人分）" at the end of a header. */
    private val TRAILING_PARENTHETICAL = Regex("\\s*[(<\\[（【][^)>\\]）】]*[)>\\]）】]$")
    private val WORD_BREAK = Regex("[\\s/]+")

    /** The header [line] is, or null for an ordinary line. A numbered item or a step never is. */
    fun header(line: Line, words: LanguageWords = LanguageWords.ENGLISH): Header? {
        if (line.number != null || line.step) return null
        val w = words(words)
        val text = line.text
        if (w.editLine.containsMatchIn(text)) return Header(Section.END)
        val lower = text.lowercase().trim().replace('’', '\'')
        w.forHeader.matchEntire(lower)?.let { m ->
            return Header(if (m.groupValues[1].isNotEmpty()) Section.INGREDIENTS else Section.INSTRUCTIONS)
        }
        val base = bare(lower)
        when (base) {
            in w.ingredientHeaders -> return Header(Section.INGREDIENTS)
            in w.instructionHeaders -> return Header(Section.INSTRUCTIONS)
            in w.endHeaders -> return Header(Section.END)
        }
        if (w.servesHeader.matches(base)) return Header(Section.INGREDIENTS)
        if (!line.headerLike) return null
        val parts = base.split(WORD_BREAK).filter { it.isNotEmpty() }
        if (parts.size in 2..4) {
            keywordHeader(text, w.keywords[parts.last()], parts.dropLast(1), w)?.let { return it }
            if (w.keywordFirst) keywordHeader(text, w.keywords[parts.first()], parts.drop(1), w)?.let { return it }
            // "Ingredients needed", "Ingredient amounts"
            if (parts[0] in w.ingredientKeywords && parts.drop(1).all { it in w.plainWords }) {
                return Header(Section.INGREDIENTS)
            }
        }
        if (parts.size == 1 && base.length >= 8) {
            w.misspelt.firstOrNull { editDistance(base, it.first) <= 2 }?.let { return Header(it.second) }
        }
        return null
    }

    /** A header of a keyword and the words beside it ([modifiers]), which name a group unless
     *  they are all plain words; null when there's no keyword, or a verb makes it a step. */
    private fun keywordHeader(text: String, section: Section?, modifiers: List<String>, w: Words): Header? {
        if (section == null || modifiers.any { it in w.verbs } ||
            !modifiers.all { word -> word.all { it.isLetter() || it in "'&+-–" } }
        ) return null
        val plain = section == Section.END || modifiers.all { it in w.plainWords }
        return Header(section, if (plain) null else text.trimEnd(':', ' ') + ":")
    }

    /** The section a line of Markdown opens, or null for an ordinary line. */
    fun section(raw: String, words: LanguageWords = LanguageWords.ENGLISH): Section? =
        header(line(raw, words), words)?.section

    /** A lowercased header without what may surround it: a leading emoji or symbol, a trailing
     *  colon, dash, emoji or parenthetical ("ingredients (serves 4)"). */
    private fun bare(lower: String): String {
        var s = lower.trimStart { !it.isLetterOrDigit() }
        s = s.trimEnd(':', ' ', '-', '–', '—')
        s = TRAILING_PARENTHETICAL.replace(s, "")
        return s.trimEnd { !it.isLetterOrDigit() }
    }

    /** Capitals: three letters or more, none lowercase and some uppercase (a script without
     *  case, such as Japanese, is never in capitals). */
    private fun isCapitals(text: String): Boolean {
        val letters = text.filter { it.isLetter() }
        return letters.length >= 3 && letters.none { it.isLowerCase() } && letters.any { it.isUpperCase() }
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
    private val CHECKBOX = Regex("^\\[[ xX]?]\\s+")
    private val LINK = Regex("\\[([^\\]]+)]\\((?:[^()]|\\([^)]*\\))*\\)")
    private val EMPHASIS = Regex("\\*\\*|__|~~")
    /** Italics inside a line ("Glaze *(for grilling)*"); a lone "*" (a footnote) stays. */
    private val ITALIC = Regex("(?<![A-Za-z0-9_*])\\*(?![\\s*])([^*]+?)(?<!\\s)\\*(?![A-Za-z0-9_*])")
    private val ESCAPE = Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!>~^|])")

    /**
     * One line of Markdown as plain text: HTML stripped and entities decoded first (Jsoup's
     * `text()`, which also trims and collapses whitespace), backslash escapes undone, then the
     * quote, heading, rule, list marker ("-", "*", "•", "▢", "1.", "2)", "Step 3:", in [words]'
     * language: "Schritt 3:"), link and emphasis syntax removed. A number followed by a dot and
     * no space ("1.5 cups") is a quantity, not a list marker.
     */
    fun line(raw: String, words: LanguageWords = LanguageWords.ENGLISH): Line {
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
        words(words).stepLabel.find(s)?.let {
            s = s.substring(it.range.last + 1).trim(); step = true; number = it.groupValues[1].toInt()
        }
        return Line(s, strong = heading || bold, italic = italic, listed = listed, number = number, step = step)
    }

    /** [line]'s text alone, in English. */
    fun cleanLine(raw: String): String = line(raw).text

    /** Every line of [text], cleaned, blank ones included. */
    fun lines(text: String, words: LanguageWords = LanguageWords.ENGLISH): List<Line> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n').map { line(it, words) }

    /** Fractions a recipe writes as one character. */
    private const val FRACTIONS = "½⅓⅔¼¾⅛⅜⅝⅞"

    fun isAmount(text: String, words: LanguageWords = LanguageWords.ENGLISH): Boolean =
        words(words).amount.containsMatchIn(text)

    /** The longest line that reads like an ingredient without an amount or list marker. */
    private const val SHORT_LINE = 40

    /** What ends a sentence, so a line ending in one doesn't read like an ingredient. */
    private const val SENTENCE_END = ".!?。！？"

    /** An ingredients block with no header needs at least this many amounts. */
    const val MIN_AMOUNTS = 2

    /** A line with a bare address in it is a pointer elsewhere ("More on my blog:
     *  https://…"), never an ingredient or a step. */
    private fun isPointer(text: String): Boolean = text.contains("http://") || text.contains("https://")

    private fun readsLikeIngredient(line: Line, words: LanguageWords): Boolean =
        isAmount(line.text, words) || line.listed ||
            (line.text.length <= SHORT_LINE && line.text.last() !in SENTENCE_END)

    /** A bold line or heading inside a section that isn't an amount names a group ("Sauce:"),
     *  as does a bold step title ("**Step 2: Make the sauce**"); a numbered item never does. */
    private fun written(line: Line, words: LanguageWords): String {
        val t = line.text
        val group = line.strong && (line.number == null || line.step) && !t.endsWith(":") && t.length <= 50 &&
            t.last() !in SENTENCE_END && !isAmount(t, words)
        return if (group) "$t:" else t
    }

    /** [text] split with [words]' language only (English by default); see [detectAndSplit]. */
    fun split(text: String, words: LanguageWords = LanguageWords.ENGLISH): SplitRecipe? {
        val w = words(words)
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
            w.yieldLine.matchEntire(line)?.let { m ->
                if (yield == null) {
                    yield = if (w.yieldWhole.matches(m.groupValues[1])) line else m.groupValues[2].trim()
                }
                return true
            }
            if (w.yieldFor.matches(line)) {
                if (yield == null) yield = line
                return true
            }
            w.time.matchEntire(line)?.let { m ->
                val value = Durations.format(m.groupValues[4], words)
                when {
                    m.groupValues[1].isNotEmpty() -> if (prep == null) prep = value
                    m.groupValues[3].isNotEmpty() -> if (total == null) total = value
                    else -> if (cook == null) cook = value
                }
                return true
            }
            return false
        }

        /** The ingredients with no header: the lines just above the steps, read upwards until
         *  one doesn't read like an ingredient. */
        fun ingredientsAbove(): Boolean {
            val block = before.asReversed().takeWhile { readsLikeIngredient(it, words) }.asReversed()
                .dropWhile { !isAmount(it.text, words) && !it.listed }
            if (block.count { isAmount(it.text, words) } < MIN_AMOUNTS) return false
            block.mapTo(ingredients) { written(it, words) }
            before.clear()
            return true
        }

        for (line in lines(text, words)) {
            if (line.text.isEmpty() && !line.step) continue
            val header = header(line, words)
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
            if (line.number == 1 && !isAmount(line.text, words)) {
                if (state == Section.INGREDIENTS && ingredients.any { !it.endsWith(":") } ||
                    state == null && ingredientsAbove()
                ) {
                    state = Section.INSTRUCTIONS
                }
            }
            if (line.text.isEmpty() || isPointer(line.text)) continue // a bare "Step 2", or a link
            when (state) {
                Section.INGREDIENTS -> if (!labelled(line.text)) ingredients.add(written(line, words))
                Section.INSTRUCTIONS -> instructions.add(written(line, words))
                Section.END -> Unit
                null -> if (!labelled(line.text)) before.add(line)
            }
        }
        if (ingredients.none { !it.endsWith(":") } || instructions.none { !it.endsWith(":") }) return null
        return SplitRecipe(ingredients, instructions, yield, prep, cook, total)
    }
}
