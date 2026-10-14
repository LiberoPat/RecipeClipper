package com.example.recipeclipper.data.model

/**
 * Ingredient amounts inside steps (#101): "Add the carrots" reads "Add ⟦2⟧ carrots", the amount
 * taken from the ingredient line as the reading view renders it (scaled, then converted), so
 * it follows the servings stepper and the unit menu. Deterministic and pure; no AI.
 *
 * A mention gets an amount only when all of these hold, else it stays as written:
 * - It names exactly one ingredient line, by [IngredientName.matches] (so "rice flour" never
 *   takes "flour"'s amount) or by dropping the line's describing words ("the oil" for "olive
 *   oil", `lineDescriptors`), and no line without a name ("salt and pepper") uses the word too.
 *   Lines that all lend the same amount count as one. A mention naming lines with different
 *   amounts (two butter lines) gets no amount: it becomes a link to those lines (#254).
 * - It is that line's first mention in the step.
 * - The line has an amount the scaler reads and isn't used in parts ("divided", "plus more").
 * - The step doesn't already say how much ("half the butter", "1 cup of the flour", "the
 *   remaining sugar") and the mention isn't part of a longer name ("the flour mixture").
 * The words for each language are in `shared/tables/<lang>/steps.json`; Japanese is a no-op.
 */
object StepAmounts {

    /**
     * A run of a step's text; [amount] marks one inserted from an ingredient line. A run with
     * [lines] is a mention naming several ingredient lines with different amounts (#254): the
     * screen shows it as a link to those lines (indexes into the lines given), never a number.
     */
    data class Part(val text: String, val amount: Boolean = false, val lines: List<Int> = emptyList()) {
        val plain: Boolean get() = !amount && lines.isEmpty()
    }

    private class Words(words: LanguageWords) {
        private val set = { key: String -> words.strings("steps", key).map { it.lowercase() }.toSet() }
        val articles = set("articles")
        val partWords = set("partWords")
        val before = set("before")
        val after = set("after")
        val anyWordAfter = words.table("steps").optBoolean("anyWordAfter", false)
        val splitWords = set("splitWords")
        // "very cold unsalted butter" is still "the butter" (#254); with the names table's
        // modifiers, each word on its own.
        val descriptors = (
            words.strings("steps", "lineDescriptors") + words.strings("names", "matchModifiers") +
                words.strings("names", "leadingWords")
            ).flatMap { it.lowercase().split(' ') }.toSet()
        val plurals = pairs(words, "plurals")
        // Words a line's name may carry after the step's name and still be that ingredient
        // ("el aceite" for "aceite de oliva virgen extra", "la farine" for "farine de blé"),
        // compared like the name: without apostrophes. Empty where modifiers come first.
        val trailing = words.strings("steps", "trailingDescriptors")
            .flatMap { it.lowercase().replace("'", "").replace("’", "").replace('-', ' ').split(' ') }
            .filter { it.isNotEmpty() }.toSet()
        // "al burro" is "a il burro": the amount takes the article's place after the preposition.
        val contractions = pairs(words, "contractions").associate { (word, preposition) -> word.lowercase() to preposition }
        // German compounds that answer their last part ("weizen-mehl": "Weizenmehl" is "das
        // Mehl"), as [first part, last part]; only these, so "Erdnussbutter" is never "die Butter".
        val compoundHeads = words.strings("steps", "compoundHeads")
            .map { it.lowercase().substringBefore('-') to it.lowercase().substringAfter('-') }
        val compoundParts = compoundHeads.map { it.first }.toSet()
        // "l'huile", "all'olio": an article, part word or contraction elided onto the next word.
        val elisions = (articles + partWords + contractions.keys).filter { it.endsWith("'") }.sortedByDescending { it.length }
        // Words that may sit between an article and the mention ("the melted butter").
        val modifiers = (
            words.strings("names", "matchModifiers") + words.strings("names", "leadingWords") +
                words.strings("names", "trailingWords") + IngredientDensities.trailingModifiers(words)
            ).flatMap { it.lowercase().split(' ') }.toSet()
        // Where modifiers follow the noun, the words that may follow a mention and still leave
        // it the whole name: its state ("la mantequilla derretida", "le beurre fondu").
        val states = if (trailing.isEmpty()) {
            emptySet()
        } else {
            (words.strings("names", "trailingWords") + IngredientDensities.trailingModifiers(words))
                .flatMap { it.lowercase().split(' ') }.toSet()
        }
    }

    private fun pairs(words: LanguageWords, key: String): List<Pair<String, String>> =
        words.table("steps").optJSONArray(key)
            ?.let { a -> List(a.length()) { a.getJSONArray(it).getString(0) to a.getJSONArray(it).getString(1) } }
            ?: emptyList()

    private fun words(words: LanguageWords): Words = words.compiled(Words::class) { Words(it) }

    private val TOKEN = Regex("""[\p{L}\p{M}\p{N}]+(?:['’][\p{L}\p{M}\p{N}]+)*""")
    private val NUMBER = Regex("""\p{N}""")

    /** A word in a step or line: its range, lowercased with ’ as ', and without apostrophes. */
    private class Token(val start: Int, val end: Int, val lower: String) {
        val bare: String = lower.replace("'", "")
        val number: Boolean = NUMBER.containsMatchIn(lower)
    }

    /**
     * One ingredient line: its name, its last word as a step's word, its amount as rendered
     * (null: never inserted), and (German) the name with its last word split after a listed
     * first part ("weizen mehl"), else null.
     */
    private class Source(
        val name: String?,
        val head: String?,
        val amount: String?,
        val words: List<String>,
        val heading: Boolean,
        val split: String? = null,
    )

    /**
     * [steps] as parts, with amounts from [lines] (the ingredient lines as currently rendered)
     * inserted where the rules above allow. [words] are the recipe's language's; null, or a
     * language without spaces, leaves every step as written.
     */
    fun annotate(steps: List<String>, lines: List<String>, words: LanguageWords?): List<List<Part>> {
        if (words == null || !words.spaced) return steps.map { listOf(Part(it)) }
        val w = words(words)
        val sources = lines.map { source(it, words, w) }
        return steps.map { annotate(it, sources, words, w) }
    }

    /** [parts] as one string, each amount in ⟦ ⟧: how the differential corpus pins them. */
    fun marked(parts: List<Part>): String = parts.joinToString("") {
        when {
            it.amount -> "⟦${it.text}⟧"
            it.lines.isNotEmpty() -> "⟪${it.text}|${it.lines.joinToString(",")}⟫"
            else -> it.text
        }
    }

    private fun tokens(text: String, w: Words): List<Token> {
        val out = mutableListOf<Token>()
        for (m in TOKEN.findAll(text)) {
            val lower = m.value.lowercase().replace('’', '\'')
            val elision = w.elisions.firstOrNull { lower.startsWith(it) && lower.length > it.length }
            if (elision != null) {
                out += Token(m.range.first, m.range.first + elision.length, elision)
                out += Token(m.range.first + elision.length, m.range.last + 1, lower.substring(elision.length))
            } else {
                out += Token(m.range.first, m.range.last + 1, lower)
            }
        }
        return out
    }

    private fun source(line: String, words: LanguageWords, w: Words): Source {
        val tokens = tokens(line, w)
        val bare = tokens.map { it.bare }
        val heading = line.trim().endsWith(":")
        val full = IngredientName.of(line, words) ?: return Source(null, null, null, bare, heading)
        val lead = IngredientScaler.patterns(words).leading.find(line)
        val span = span(line, full, tokens, lead?.let { it.range.last + 1 } ?: 0, words)
            ?: return Source(full, full.substringAfterLast(' '), null, bare, heading, split(full, w))
        val (j, k) = span
        // "3 c. à s. d'huile d'olive": the elided "d'" belongs to the amount, as "de" does.
        val first = if (j < k && tokens[j].lower in w.elisions) j + 1 else j
        val name = if (first == j) full else IngredientDensities.headPhrase(line.substring(tokens[first].start, tokens[k].end), words)
        val amount = if (lead == null) null else amount(line, tokens, first, k, words, w)
        return Source(name, tokens[k].bare, amount, bare, heading, split(name, w))
    }

    // The line's words [j, k] that make its name, the first at or after [from], or null.
    private fun span(line: String, name: String, tokens: List<Token>, from: Int, words: LanguageWords): Pair<Int, Int>? {
        val size = name.split(' ').size
        for (j in tokens.indices) {
            if (tokens[j].start < from) continue
            for (k in j until minOf(tokens.size, j + size + 2)) {
                if (IngredientDensities.headPhrase(line.substring(tokens[j].start, tokens[k].end), words) == name) return j to k
            }
        }
        return null
    }

    // The rendered line's text before its name, words [j, k] ("250 g", "2 large", "200 g de",
    // "3 c. à s. d'"), or null.
    private fun amount(line: String, tokens: List<Token>, j: Int, k: Int, words: LanguageWords, w: Words): String? {
        // Only a line the scaler reads, so a line that stays as written never lends a number.
        if (IngredientScaler.scale(line, 2.0, words) == line) return null
        val amount = line.substring(0, tokens[j].start).trim()
        if (amount.isEmpty() || amount.any { it == ',' || it == ';' }) return null
        // "2 cups flour, divided", "1 tsp salt, plus more to taste": used in parts.
        if (tokens.drop(k + 1).any { it.bare in w.splitWords }) return null
        return amount
    }

    // "bio gemüsebrühe" as "bio gemüse brühe" when "gemüse-brühe" is listed; else null.
    private fun split(name: String, w: Words): String? {
        val last = name.substringAfterLast(' ')
        val (part, _) = w.compoundHeads.firstOrNull { (part, rest) ->
            last.startsWith(part) && sameWord(last.substring(part.length), rest, w)
        } ?: return null
        return name.dropLast(last.length) + part + " " + last.substring(part.length)
    }

    /** True when [a] and [b] are one word, singular or plural ("carrot", "carrots"). */
    private fun sameWord(a: String, b: String, w: Words): Boolean =
        a == b || w.plurals.any { (one, many) -> plural(a, one, many) == b || plural(b, one, many) == a }

    private fun plural(word: String, one: String, many: String): String? =
        if (word.endsWith(one)) word.dropLast(one.length) + many else null

    // Words that end a run leftwards: they say how much, or are the article.
    private fun stops(t: Token, w: Words) = t.number || t.lower in w.partWords || t.lower in w.articles

    // Only spaces, or a hyphen ("all-purpose"), between two words of one name.
    private fun joined(step: String, a: Token, b: Token) = step.substring(a.end, b.start).let { it.isBlank() || it == "-" }

    /**
     * Words from [start] that name line [line]: [strict]ly by [IngredientName.matches], or past
     * its describing words; [exact] when they are the line's whole name.
     */
    private class Run(val line: Int, val start: Int, val strict: Boolean, val exact: Boolean = false)

    /** The longest run of words ending at [h] that names [s]'s ingredient, or null. */
    private fun run(step: String, tokens: List<Token>, h: Int, i: Int, s: Source, words: LanguageWords, w: Words): Run? {
        val head = s.head ?: return null
        if (!sameWord(tokens[h].bare, head, w)) {
            return trailingRun(step, tokens, h, i, s.name!!, words, w) ?: s.split?.let { compoundRun(step, tokens, h, i, it, words, w) }
        }
        var best: Run? = null
        var start = h
        while (start >= 0 && h - start < 6) {
            if (start < h && !joined(step, tokens[start], tokens[start + 1])) break
            // A run may hold "de" ("farinha de trigo") but never starts on a word that says how
            // much or on the article: "a carrot" is "carrot" after "a".
            if (!stops(tokens[start], w)) {
                val phrase = step.substring(tokens[start].start, tokens[h].start) + head
                if (IngredientName.matches(phrase, s.name!!, words)) {
                    val exact = IngredientName.key(IngredientDensities.headPhrase(phrase, words), words) ==
                        IngredientName.key(IngredientDensities.headPhrase(s.name, words), words)
                    best = Run(i, start, true, exact)
                } else if (endsWith(s.name, phrase, words) { front -> front.all { it in w.descriptors } }) {
                    best = Run(i, start, false)
                }
            }
            start--
        }
        return best
    }

    /**
     * Where describing words follow the noun: the longest run of words ending at [h] whose words
     * start [name], the rest of it only `trailingDescriptors` ("el aceite" for "aceite de oliva
     * virgen extra", never for "aceite de sésamo"), and the mention its whole name.
     */
    private fun trailingRun(step: String, tokens: List<Token>, h: Int, i: Int, name: String, words: LanguageWords, w: Words): Run? {
        if (w.trailing.isEmpty() || !endsName(step, tokens, h, w)) return null
        val n = keyWords(name, words)
        return runFrom(step, tokens, h, w) { start ->
            val p = keyWords(step.substring(tokens[start].start, tokens[h].end), words)
            startsWith(n, p, w) && n.drop(p.size).all { it in w.trailing }
        }?.let { Run(i, it, false) }
    }

    /**
     * German compounds (`compoundHeads`): "das Mehl" for "Weizenmehl", the words before only
     * describing words. A compound that isn't listed ("Erdnussbutter") is another ingredient.
     */
    private fun compoundRun(step: String, tokens: List<Token>, h: Int, i: Int, split: String, words: LanguageWords, w: Words): Run? {
        val tail = split.substringAfterLast(' ')
        if (!sameWord(tokens[h].bare, tail, w)) return null
        return runFrom(step, tokens, h, w) { start ->
            val phrase = step.substring(tokens[start].start, tokens[h].start) + tail
            endsWith(split, phrase, words) { front -> front.all { it in w.descriptors || it in w.compoundParts } }
        }?.let { Run(i, it, false) }
    }

    // The first word of the longest run of joined words ending at [h] that [names], or null.
    private fun runFrom(step: String, tokens: List<Token>, h: Int, w: Words, names: (Int) -> Boolean): Int? {
        var best: Int? = null
        var start = h
        while (start >= 0 && h - start < 6) {
            if (start < h && !joined(step, tokens[start], tokens[start + 1])) break
            if (!stops(tokens[start], w) && names(start)) best = start
            start--
        }
        return best
    }

    private fun keyWords(text: String, words: LanguageWords): List<String> =
        IngredientName.key(IngredientDensities.headPhrase(text, words), words).split(' ')

    // True when [name]'s words start with [phrase]'s, and it has more.
    private fun startsWith(name: List<String>, phrase: List<String>, w: Words): Boolean =
        phrase.size < name.size && phrase.indices.all { sameWord(phrase[it], name[it], w) }

    // True when [word] ends [compound] after a first part of at least three letters ("erdnussbutter").
    private fun compoundEnds(compound: String, word: String, w: Words): Boolean =
        (3..compound.length - 3).any { sameWord(compound.substring(it), word, w) }

    /** True when [name] ends with [phrase]'s words and [front] accepts the words before them ("olive" oil). */
    private fun endsWith(name: String, phrase: String, words: LanguageWords, front: (List<String>) -> Boolean): Boolean {
        val n = IngredientName.key(IngredientDensities.headPhrase(name, words), words).split(' ')
        val p = IngredientName.key(IngredientDensities.headPhrase(phrase, words), words).split(' ')
        return p.size < n.size && n.takeLast(p.size) == p && front(n.dropLast(p.size))
    }

    /** Replace step[start, end) with [amount], then a space if [space]; the mention ends at [mentionEnd]. */
    private class Insertion(
        val start: Int,
        val end: Int,
        val amount: String,
        val space: Boolean,
        val mentionEnd: Int,
        // A link to these lines, with the mention as its text, instead of an amount.
        val lines: List<Int> = emptyList(),
        // The preposition a contraction held, written before the amount ("al" → "a ").
        val preposition: String? = null,
    )

    private fun annotate(step: String, sources: List<Source>, words: LanguageWords, w: Words): List<Part> {
        val tokens = tokens(step, w)
        val seen = mutableSetOf<Int>()
        val insertions = mutableListOf<Insertion>()
        // "Sauté onion and mushrooms - Turn the heat down…": a step's title names what the step
        // is about; its words are no mention, so the first mention is the one after it (#254).
        val title = TITLE.find(step)?.let { it.range.last + 1 } ?: 0
        for (h in tokens.indices) {
            if (tokens[h].start < title) continue
            val runs = sources.indices.mapNotNull { i -> run(step, tokens, h, i, sources[i], words, w) }
            if (runs.isEmpty()) continue
            // The longest run names the mention: "brown sugar" is never "sugar".
            val start = runs.minOf { it.start }
            // A line named exactly by the step's words is the one: "the sugar" beside "brown sugar".
            val longest = runs.filter { it.start == start }.let { all -> all.filter { it.exact }.ifEmpty { all } }
            val named = longest.map { it.line }.toMutableList()
            // Named only past describing words ("the oil" for "olive oil"): any line ending in
            // the step's words could be the one ("sesame oil" too); where describing words follow
            // the noun, any line starting with them ("aceite de sésamo"); in German, any compound
            // ending in the word ("Mandelmehl" beside "Weizenmehl").
            if (longest.none { it.strict }) {
                val front = step.substring(tokens[start].start, tokens[h].start)
                val phrase = keyWords(step.substring(tokens[start].start, tokens[h].end), words)
                named += sources.indices.filter { i ->
                    val s = sources[i]
                    i !in named && s.head != null && (
                        (sameWord(tokens[h].bare, s.head, w) && endsWith(s.name!!, front + s.head, words) { true }) ||
                            (w.trailing.isNotEmpty() && startsWith(keyWords(s.name!!, words), phrase, w)) ||
                            (w.compoundHeads.isNotEmpty() && start == h && compoundEnds(s.head, tokens[h].bare, w))
                        )
                }
            }
            // "salt and pepper" has no name but uses the word: the mention could be either line.
            val nameless = sources.indices.filter { i ->
                sources[i].let { s -> s.name == null && !s.heading && s.words.any { sameWord(it, tokens[h].bare, w) } }
            }
            // Only a line's first mention in the step, whether or not it gets an amount.
            if ((named + nameless).all { it in seen }) continue
            seen += named + nameless
            // Lines that lend the same amount ("30 g butter" twice) are one answer.
            val amount = named.map { sources[it].amount }.distinct().singleOrNull()
            if (amount != null && nameless.isEmpty()) {
                insertion(step, tokens, start, h, amount, w)?.let { insertions += it }
            } else if (named.size + nameless.size > 1) {
                // Lines with different amounts, and the step doesn't say which (#254): no
                // number, but a link to the lines, where the same rules would place an amount.
                if (insertion(step, tokens, start, h, "", w) == null) continue
                val from = tokens[start].start
                val to = tokens[h].end
                insertions += Insertion(from, to, step.substring(from, to), false, to, (named + nameless).sorted())
            }
        }
        return parts(step, insertions)
    }

    // A few words, no sentence end, then a spaced dash.
    private val TITLE = Regex("""^[^.!?:;\n]{1,60}?\s[-–—]\s""")
    private val DASHES = setOf("-", "–", "—")

    private const val ENDS = ",.;:!?)&–—"
    private const val LIST_OR_CLAUSE = ",;:(.!?&"

    // The mention ends the name: the step ends, or punctuation or an allowed word follows, there
    // or after words that give its state ("la mantequilla derretida y").
    private fun endsName(step: String, tokens: List<Token>, h: Int, w: Words): Boolean {
        var e = h
        while (true) {
            val next = tokens.getOrNull(e + 1)
            val gap = step.substring(tokens[e].end, next?.start ?: step.length).trim()
            if (if (gap.isNotEmpty()) gap[0] in ENDS else next == null || w.anyWordAfter || next.lower in w.after) return true
            if (next == null || next.bare !in w.states || !joined(step, tokens[e], next)) return false
            e++
        }
    }

    // The token starts its step or sentence, or follows a spaced dash ("Butter - Melt butter"),
    // so it is taken as the verb ("Melt butter").
    private fun clauseFirst(step: String, tokens: List<Token>, i: Int): Boolean {
        if (i == 0) return true
        val gap = step.substring(tokens[i - 1].end, tokens[i].start)
        return gap.any { it in ".!?;:" } || (gap.length >= 3 && gap.first().isWhitespace() && gap.last().isWhitespace() && gap.trim() in DASHES)
    }

    private fun insertion(step: String, tokens: List<Token>, start: Int, h: Int, amount: String, w: Words): Insertion? {
        if (!endsName(step, tokens, h, w)) return null
        // "the melted butter": the amount goes before the words that describe it.
        var m = start
        while (m > 0 && joined(step, tokens[m - 1], tokens[m]) && !stops(tokens[m - 1], w) && tokens[m - 1].bare in w.modifiers) m--
        val apostrophe = amount.endsWith("'") || amount.endsWith("’")
        val at = tokens[m].start
        val insert = Insertion(at, at, amount, !apostrophe, tokens[h].end)
        if (m == 0) return insert
        val p = tokens[m - 1]
        val gap = step.substring(p.end, at)
        // After a comma ("the carrots, celery") or a sentence's end.
        if (gap.isNotBlank()) return insert.takeIf { gap.any { it in LIST_OR_CLAUSE } }
        val contraction = w.contractions[p.lower]
        if (p.lower in w.articles || contraction != null) {
            val q = tokens.getOrNull(m - 2)
            if (q != null && step.substring(q.end, p.start).isBlank() && (q.number || q.lower in w.partWords)) return null
            // The amount takes the article's place: "the carrots" → "2 carrots", "l'huile" → "… huile",
            // "al burro" → "a 100 g di burro".
            val elided = gap.isEmpty()
            val preposition = contraction?.let { if (step[p.start].isUpperCase()) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
            return Insertion(p.start, if (apostrophe) at else p.end, amount, elided && !apostrophe, tokens[h].end, preposition = preposition)
        }
        if (p.number || p.lower in w.partWords) return null
        return insert.takeIf { p.lower in w.before || clauseFirst(step, tokens, m - 1) }
    }

    private fun parts(step: String, insertions: List<Insertion>): List<Part> {
        val out = mutableListOf<Part>()
        var cursor = 0
        var covered = 0
        for (ins in insertions.sortedBy { it.start }) {
            if (ins.start < covered) continue
            if (ins.start > cursor) out += Part(step.substring(cursor, ins.start))
            if (ins.preposition != null) out += Part(ins.preposition + " ")
            out += if (ins.lines.isEmpty()) Part(ins.amount, amount = true) else Part(ins.amount, lines = ins.lines)
            val rest = if (ins.space) " " else ""
            cursor = ins.end
            if (rest.isNotEmpty()) out += Part(rest)
            covered = ins.mentionEnd
        }
        if (cursor < step.length) out += Part(step.substring(cursor))
        // Adjacent plain runs as one.
        return out.fold(mutableListOf()) { acc, part ->
            val last = acc.lastOrNull()
            if (last != null && last.plain && part.plain) acc[acc.lastIndex] = Part(last.text + part.text) else acc += part
            acc
        }
    }
}
