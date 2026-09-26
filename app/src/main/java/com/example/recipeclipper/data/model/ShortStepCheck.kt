package com.example.recipeclipper.data.model

/**
 * Chef mode's gate (#100): whether a short version of a step, written by the on-device model,
 * may be shown in place of the step as written. Pure, and the same on iOS (the differential
 * corpus's `Short` rows pin it).
 *
 * The model writes words; code owns every number. A short version passes only if it is shorter
 * than the step, every number in it (fractions, decimal commas and each end of a range, as
 * written) appears in the step, it states exactly the step's times and temperatures ("350°F
 * (180°C)" may keep either half), and it keeps the words a cook acts on and adds none
 * ([keepsWords], #129). Anything else, and any recipe language the app has no words for, shows
 * the step as written: a doubtful short step is rejected, never shown.
 */
object ShortStepCheck {

    /**
     * [short], tidied, when it may stand for [original]; null to show [original] as written.
     * [ingredients] are the recipe's lines, whose names the short step must keep.
     */
    fun accept(original: String, short: String?, words: LanguageWords?, ingredients: List<String> = emptyList()): String? {
        if (words == null || short == null) return null
        val candidate = tidy(short)
        if (candidate.isEmpty() || candidate.length >= tidy(original).length) return null
        if (!numbers(original).containsAll(numbers(candidate))) return null
        if (!keepsTimes(original, candidate, words)) return null
        if (!keepsWords(original, candidate, words, ingredients)) return null
        return candidate
    }

    /**
     * True when [short] states exactly [original]'s times and temperatures: none changed, none
     * added, none dropped ("350°F (180°C)" may keep either half).
     */
    fun keepsTimes(original: String, short: String, words: LanguageWords): Boolean {
        if (StepTimers.durations(original, words).toSet() != StepTimers.durations(short, words).toSet()) return false
        val stated = TemperatureConverter.temperatures(original, words)
        val kept = TemperatureConverter.temperatures(short, words).flatten().toSet()
        return stated.flatten().containsAll(kept) && stated.all { keys -> keys.any { it in kept } }
    }

    /**
     * True when [short] drops nothing a cook acts on and adds nothing (#129), by the words of
     * shared/tables/<language>/chef.json. [original] words that must stay: an ingredient named
     * in [ingredients] (the words of its [IngredientName], or of the line when it has none), an
     * action, a piece of equipment, a qualifier ("not", "if", "until") and a time word ("a few
     * minutes"). [short] may add only function words. A word counts as the same through an
     * ending ("whisking", "whisk"), an abbreviation ("temp") or its unit ("min", "minutes").
     * A language written without spaces compares characters: every kanji and katakana of
     * [short] is in [original], and each table entry and ingredient name is in both or neither.
     */
    fun keepsWords(original: String, short: String, words: LanguageWords, ingredients: List<String> = emptyList()): Boolean {
        val w = words.compiled(Words::class) { Words(it) }
        if (!words.spaced) return keepsCharacters(original, short, words, w, ingredients)
        val said = tokens(original)
        val kept = tokens(short)
        val saidForms = said.flatMapTo(HashSet()) { forms(it, words, w) }
        val keptForms = kept.flatMapTo(HashSet()) { forms(it, words, w) }
        for (token in kept) {
            if (token.length < 2 || token in w.functionWords) continue
            if (forms(token, words, w).none { it in saidForms }) return false
        }
        val named = ingredientForms(ingredients, words, w)
        for ((i, token) in said.withIndex()) {
            if (token.length < 2) continue
            val f = forms(token, words, w)
            // "the rest of the flour": a noun, not the action.
            val action = f.any { it in w.actions } && said.getOrNull(i - 1)?.let { it in w.notAfter } != true
            val needed = action || f.any { it in w.kept || it in named || it.startsWith(TIME) }
            if (needed && f.none { it in keptForms }) return false
        }
        return true
    }

    /** A step this short ("Serve warm.") is left as written, never sent to the model. */
    fun worthShortening(step: String): Boolean = tidy(step).length >= MIN_LENGTH

    const val MIN_LENGTH = 40

    /** One line: trimmed, whitespace collapsed, a leading bullet and wrapping quotes removed. */
    fun tidy(text: String): String {
        var s = text.trim().replace(SPACES, " ")
        s = s.replace(BULLET, "")
        if (s.length >= 2 && s.first() in QUOTES && s.last() in QUOTES) s = s.substring(1, s.length - 1).trim()
        return s
    }

    /** Every number in [text], as written: "1,5", "1 1/2", "1½", "½"; a range's ends separately. */
    fun numbers(text: String): Set<String> {
        val plain = buildString {
            for (c in text) {
                append(
                    when (c) {
                        in '０'..'９' -> '0' + (c - '０')
                        '／', '⁄' -> '/'
                        else -> c
                    }
                )
            }
        }
        return NUMBER.findAll(plain).map { it.value.replace(SPACES, " ").replace(SPACED_FRACTION, "$1$2") }.toSet()
    }

    private fun keepsCharacters(
        original: String, short: String, words: LanguageWords, w: Words, ingredients: List<String>
    ): Boolean {
        val said = LETTER.findAll(original).map { it.value }.toSet()
        if (LETTER.findAll(short).any { it.value !in said && it.value[0] !in HIRAGANA }) return false
        if (w.entries.any { (it in original) != (it in short) }) return false
        return ingredients.mapNotNull { IngredientName.of(it, words) }.none { it in original && it !in short }
    }

    /** The words of [text], lowercase, with an apostrophe inside one kept ("don't", "l'eau"). */
    private fun tokens(text: String): List<String> =
        WORD.findAll(text).map { it.value.lowercase().replace('’', '\'') }.toList()

    /** Every form [token] matches by: [wordForms], plus its time ("#t60") or unit ("#uCUP"). */
    private fun forms(token: String, words: LanguageWords, w: Words): Set<String> {
        val out = wordForms(token, w.endings, w.abbreviations)
        StepTimers.unitSeconds(token, words)?.let { out += "$TIME$it" }
        if (w.unit.matches(token)) MeasureUnit.fromText(token, words)?.let { out += "#u${it.name}" }
        return out
    }

    /** [token], its long form, the part after an elision ("l'eau": "eau"), and its bases. */
    private fun wordForms(
        token: String, endings: List<Pair<String, String>>, abbreviations: Map<String, String>
    ): MutableSet<String> {
        val out = linkedSetOf(token)
        abbreviations[token]?.let { out += it }
        val apostrophe = token.indexOf('\'')
        if (apostrophe >= 0 && token.length - apostrophe - 1 >= 3) out += token.substring(apostrophe + 1)
        for ((ending, replacement) in endings) {
            if (!token.endsWith(ending)) continue
            val base = token.substring(0, token.length - ending.length)
            if (base.length < 2) continue
            out += base + replacement
            // "stirring", "chopped": the doubled last letter goes too.
            if (replacement.isEmpty() && base.length >= 3 && base[base.length - 1] == base[base.length - 2]) {
                out += base.substring(0, base.length - 1)
            }
        }
        return out
    }

    /** The forms of every word of [lines]' ingredient names that isn't a size, modifier or unit. */
    private fun ingredientForms(lines: List<String>, words: LanguageWords, w: Words): Set<String> {
        val out = HashSet<String>()
        for (line in lines) {
            if (line.isBlank() || line.trim().endsWith(":")) continue
            for (token in tokens(IngredientName.of(line, words) ?: line)) {
                if (token.length < 3 || token in w.functionWords || token in w.notIngredient) continue
                val f = forms(token, words, w)
                if (f.none { it.startsWith("#") }) out += f
            }
        }
        return out
    }

    // One language's chef.json, plus the names table's words that don't name an ingredient.
    private class Words(words: LanguageWords) {
        private val table = words.table("chef")
        private fun pairs(key: String): List<Pair<String, String>> = table.getJSONArray(key).let { a ->
            List(a.length()) { a.getJSONArray(it).getString(0) to a.getJSONArray(it).getString(1) }
        }
        val endings = pairs("endings")
        val abbreviations = pairs("abbreviations").toMap()
        private fun formsOf(key: String): Set<String> =
            words.strings("chef", key).flatMapTo(HashSet()) { wordForms(it, endings, abbreviations) }
        val actions = formsOf("actions")
        val kept = formsOf("equipment") + formsOf("qualifiers")
        val entries = listOf("actions", "equipment", "qualifiers").flatMap { words.strings("chef", it) }
        val notAfter = words.strings("chef", "notAfter").toSet()
        val functionWords = words.strings("chef", "functionWords").toSet()
        val notIngredient = listOf("leadingWords", "trailingWords", "matchModifiers", "cutPhrases", "conjunctions")
            .flatMap { key -> words.strings("names", key).flatMap { it.split(' ') } }.toSet()
        val unit = Regex(UnitPatterns.of(words).plain, RegexOption.IGNORE_CASE)
    }

    private const val TIME = "#t"
    private val WORD = Regex("""\p{L}+(?:['’]\p{L}+)*""")
    private val LETTER = Regex("""\p{L}""")
    private val HIRAGANA = '぀'..'ゟ'
    private const val FRACTIONS = "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅐⅛⅜⅝⅞⅑⅒"
    private val NUMBER = Regex("""\d+(?:[.,]\d+)*(?:\s+\d+/\d+|/\d+|\s*[$FRACTIONS])?|[$FRACTIONS]""")
    private val SPACED_FRACTION = Regex("""(\d) ([$FRACTIONS])""")
    private val SPACES = Regex("""\s+""")
    private val BULLET = Regex("""^[-•*]\s+""")
    private const val QUOTES = "\"'“”„«»「」"
}
