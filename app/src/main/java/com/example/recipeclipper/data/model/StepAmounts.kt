package com.example.recipeclipper.data.model

/**
 * Ingredient amounts inside steps (#101): "Add the carrots" reads "Add ⟦2⟧ carrots", the amount
 * taken from the ingredient line as the reading view renders it (scaled, then converted), so
 * it follows the servings stepper and the unit menu. Deterministic and pure; no AI.
 *
 * A mention gets an amount only when all of these hold, else it stays as written:
 * - It names exactly one ingredient line, by [IngredientName.matches] (so "rice flour" never
 *   takes "flour"'s amount), and no line without a name ("salt and pepper") uses the word too.
 * - It is that line's first mention in the step.
 * - The line has an amount the scaler reads and isn't used in parts ("divided", "plus more").
 * - The step doesn't already say how much ("half the butter", "1 cup of the flour", "the
 *   remaining sugar") and the mention isn't part of a longer name ("the flour mixture").
 * The words for each language are in `shared/tables/<lang>/steps.json`; Japanese is a no-op.
 */
object StepAmounts {

    /** A run of a step's text; [amount] marks one inserted from an ingredient line. */
    data class Part(val text: String, val amount: Boolean = false)

    private class Words(words: LanguageWords) {
        private val set = { key: String -> words.strings("steps", key).map { it.lowercase() }.toSet() }
        val articles = set("articles")
        val partWords = set("partWords")
        val before = set("before")
        val after = set("after")
        val anyWordAfter = words.table("steps").optBoolean("anyWordAfter", false)
        val splitWords = set("splitWords")
        val plurals: List<Pair<String, String>> = words.table("steps").optJSONArray("plurals")
            ?.let { a -> List(a.length()) { a.getJSONArray(it).getString(0) to a.getJSONArray(it).getString(1) } }
            ?: emptyList()
        // "l'huile": an article or part word elided onto the next word.
        val elisions = (articles + partWords).filter { it.endsWith("'") }.sortedByDescending { it.length }
        // Words that may sit between an article and the mention ("the melted butter").
        val modifiers = (
            words.strings("names", "matchModifiers") + words.strings("names", "leadingWords") +
                words.strings("names", "trailingWords") + IngredientDensities.trailingModifiers(words)
            ).flatMap { it.lowercase().split(' ') }.toSet()
    }

    private fun words(words: LanguageWords): Words = words.compiled(Words::class) { Words(it) }

    private val TOKEN = Regex("""[\p{L}\p{M}\p{N}]+(?:['’][\p{L}\p{M}\p{N}]+)*""")
    private val NUMBER = Regex("""\p{N}""")

    /** A word in a step or line: its range, lowercased with ’ as ', and without apostrophes. */
    private class Token(val start: Int, val end: Int, val lower: String) {
        val bare: String = lower.replace("'", "")
        val number: Boolean = NUMBER.containsMatchIn(lower)
    }

    /** One ingredient line: its name, its amount as rendered (null: never inserted). */
    private class Source(val name: String?, val head: String?, val amount: String?, val words: List<String>, val heading: Boolean)

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
    fun marked(parts: List<Part>): String = parts.joinToString("") { if (it.amount) "⟦${it.text}⟧" else it.text }

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
        val name = IngredientName.of(line, words) ?: return Source(null, null, null, bare, heading)
        return Source(name, name.substringAfterLast(' '), amount(line, name, tokens, words, w), bare, heading)
    }

    // The rendered line's text before its name ("250 g", "2 large", "200 g de"), or null.
    private fun amount(line: String, name: String, tokens: List<Token>, words: LanguageWords, w: Words): String? {
        // Only a line the scaler reads, so a line that stays as written never lends a number.
        if (IngredientScaler.scale(line, 2.0, words) == line) return null
        val lead = IngredientScaler.patterns(words).leading.find(line) ?: return null
        val size = name.split(' ').size
        for (j in tokens.indices) {
            if (tokens[j].start <= lead.range.last) continue
            for (k in j until minOf(tokens.size, j + size + 2)) {
                if (IngredientDensities.headPhrase(line.substring(tokens[j].start, tokens[k].end), words) != name) continue
                val amount = line.substring(0, tokens[j].start).trim()
                if (amount.isEmpty() || amount.any { it == ',' || it == ';' }) return null
                // "2 cups flour, divided", "1 tsp salt, plus more to taste": used in parts.
                if (tokens.drop(k + 1).any { it.bare in w.splitWords }) return null
                return amount
            }
        }
        return null
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

    /** The earliest token where a run of words ending at [h] names [s]'s ingredient, or -1. */
    private fun runStart(step: String, tokens: List<Token>, h: Int, s: Source, words: LanguageWords, w: Words): Int {
        val head = s.head ?: return -1
        if (!sameWord(tokens[h].bare, head, w)) return -1
        var best = -1
        var start = h
        while (start >= 0 && h - start < 6) {
            if (start < h && !joined(step, tokens[start], tokens[start + 1])) break
            // A run may hold "de" ("farinha de trigo") but never starts on a word that says how
            // much or on the article: "a carrot" is "carrot" after "a".
            val phrase = step.substring(tokens[start].start, tokens[h].start) + head
            if (!stops(tokens[start], w) && IngredientName.matches(phrase, s.name!!, words)) best = start
            start--
        }
        return best
    }

    /** Replace step[start, end) with [amount], then a space if [space]; the mention ends at [mentionEnd]. */
    private class Insertion(val start: Int, val end: Int, val amount: String, val space: Boolean, val mentionEnd: Int)

    private fun annotate(step: String, sources: List<Source>, words: LanguageWords, w: Words): List<Part> {
        val tokens = tokens(step, w)
        val seen = mutableSetOf<Int>()
        val insertions = mutableListOf<Insertion>()
        for (h in tokens.indices) {
            val runs = sources.indices.mapNotNull { i ->
                runStart(step, tokens, h, sources[i], words, w).takeIf { it >= 0 }?.let { i to it }
            }
            if (runs.isEmpty()) continue
            // The longest run names the mention: "brown sugar" is never "sugar".
            val start = runs.minOf { it.second }
            val named = runs.filter { it.second == start }.map { it.first }
            // Only a line's first mention in the step, whether or not it gets an amount.
            if (named.all { it in seen }) continue
            seen += named
            if (named.size != 1) continue
            // "salt and pepper" has no name but uses the word: the mention could be either line.
            if (sources.any { it.name == null && !it.heading && it.words.any { word -> sameWord(word, tokens[h].bare, w) } }) continue
            val amount = sources[named[0]].amount ?: continue
            insertion(step, tokens, start, h, amount, w)?.let { insertions += it }
        }
        return parts(step, insertions)
    }

    private const val ENDS = ",.;:!?)&–—"
    private const val LIST_OR_CLAUSE = ",;:(.!?&"

    // The mention ends the name: the step ends, or punctuation or an allowed word follows.
    private fun endsName(step: String, tokens: List<Token>, h: Int, w: Words): Boolean {
        val next = tokens.getOrNull(h + 1)
        val gap = step.substring(tokens[h].end, next?.start ?: step.length).trim()
        if (gap.isNotEmpty()) return gap[0] in ENDS
        return next == null || w.anyWordAfter || next.lower in w.after
    }

    // The token starts its step or sentence, so it is taken as the verb ("Melt butter").
    private fun clauseFirst(step: String, tokens: List<Token>, i: Int): Boolean =
        i == 0 || step.substring(tokens[i - 1].end, tokens[i].start).any { it in ".!?;:" }

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
        if (p.lower in w.articles) {
            val q = tokens.getOrNull(m - 2)
            if (q != null && step.substring(q.end, p.start).isBlank() && (q.number || q.lower in w.partWords)) return null
            // The amount takes the article's place: "the carrots" → "2 carrots", "l'huile" → "… huile".
            val elided = gap.isEmpty()
            return Insertion(p.start, if (apostrophe) at else p.end, amount, elided && !apostrophe, tokens[h].end)
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
            out += Part(ins.amount, amount = true)
            val rest = if (ins.space) " " else ""
            cursor = ins.end
            if (rest.isNotEmpty()) out += Part(rest)
            covered = ins.mentionEnd
        }
        if (cursor < step.length) out += Part(step.substring(cursor))
        // Adjacent plain runs as one.
        return out.fold(mutableListOf()) { acc, part ->
            val last = acc.lastOrNull()
            if (last != null && !last.amount && !part.amount) acc[acc.lastIndex] = Part(last.text + part.text) else acc += part
            acc
        }
    }
}
