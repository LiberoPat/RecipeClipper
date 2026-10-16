package com.example.recipeclipper.data.model

import java.text.Normalizer
import java.util.Locale

/**
 * The gate between the on-device model and the screen for a page with no recipe data (#103):
 * the model may only pick text that is on the page, never write any. Pure; the iOS app's
 * `PageRecipeCheck` is the same, pinned by the differential corpus's `Pick` rows.
 *
 * Each picked string is looked for in the text the model was given, after folding both the
 * same way: NFKC ("½" is "1/2"), typographic quotes, dashes and the fraction slash as ASCII,
 * lowercase, and every run of whitespace as one space. What shows is the page's own text for
 * the span found, never the model's. A span must not cut into a word, nor into a number
 * ("2 cups" inside "12 cups", "25 minutes" inside "20-25 minutes"); an ingredient must start its
 * line (after a bullet); a name, ingredient or step must hold a letter. Anything not found
 * that way is dropped, and what is left is a recipe only with a name plus ingredients or steps.
 *
 * One recipe's lines only (#128): a page can hold a second recipe, whose lines are on the page
 * too. The text is cut into cards at each ingredients heading that follows a steps heading
 * ("Ingredients … Directions … Ingredients"), and only the ingredients and steps found in the
 * card holding the most of them are kept (a tie goes to the earlier card).
 *
 * Headings and the method's ingredient lists (#271): a picked line that is a whole heading on the
 * page (it ends in a colon there, as `PageTextReader` writes `<h2>`–`<h6>`) is a group heading
 * when picked as an ingredient, kept with its colon and only directly above an ingredient line,
 * and is never a step ("step 1", "Method"). An ingredient found only below the card's steps
 * heading (a "used in this step" list) is dropped.
 */
object PageRecipeCheck {

    enum class Kind { NAME, INGREDIENT, STEP, OTHER }

    /** The verified recipe, with the page's own text for every field kept; null if too little is left. */
    fun verify(page: String, picked: PageSelection): PageSelection? {
        val folded = Folded(page)
        // A card's title is a heading, whose colon is the reader's mark, not the name's (#271).
        val name = picked.name?.let { find(folded, it, Kind.NAME) }?.removeSuffix(":")?.trimEnd(' ')?.ifEmpty { null } ?: return null
        val foundIngredients = picked.ingredients.map { spans(folded, it, Kind.INGREDIENT) }
        val foundSteps = picked.steps.map { spans(folded, it, Kind.STEP) }
        val (card, method) = folded.cards()
        val counts = IntArray(card.last() + 1)
        for (found in foundIngredients + foundSteps) found.map { card[it.line] }.distinct().forEach { counts[it]++ }
        val recipe = counts.indices.maxByOrNull { counts[it] } ?: 0
        // Where a pick is found more than once, a whole line wins ("Zucchini" is the "Zucchini:"
        // heading, not the start of "Zucchini sweating: 30 minutes"), else the first (#271).
        fun first(found: List<Span>, ok: (Span) -> Boolean) = found.firstOrNull { ok(it) && it.wholeLine } ?: found.firstOrNull(ok)
        val keptIngredients = foundIngredients.mapNotNull { found -> first(found) { card[it.line] == recipe && !method[it.line] } }
        // A group heading only directly above an ingredient line.
        val ingredients = keptIngredients.filterIndexed { i, span ->
            !span.heading || keptIngredients.getOrNull(i + 1)?.heading == false
        }.map { it.text }
        val steps = foundSteps.mapNotNull { found -> first(found) { card[it.line] == recipe } }.filter { !it.heading }.map { it.text }
        if (ingredients.isEmpty() && steps.isEmpty()) return null
        fun other(s: String?) = s?.let { find(folded, it, Kind.OTHER) }
        return PageSelection(
            name, ingredients, steps, other(picked.yield),
            other(picked.prepTime), other(picked.cookTime), other(picked.totalTime)
        )
    }

    /** The page's own text for [picked], if it is on [page] as a whole span of this [kind]. */
    fun find(page: String, picked: String, kind: Kind): String? = find(Folded(page), picked, kind)

    private fun find(page: Folded, picked: String, kind: Kind): String? = spans(page, picked, kind).firstOrNull()?.text

    /**
     * Where [find] finds [picked]: the page's own text, the line it starts on, whether it is that
     * whole line, and whether that line ends in a colon (a heading, #271).
     */
    private class Span(val text: String, val line: Int, val wholeLine: Boolean = false, val heading: Boolean = false)

    /** Every place [picked] is on [page] as a whole span of this [kind], in page order. */
    private fun spans(page: Folded, picked: String, kind: Kind): List<Span> {
        val needle = Folded(picked).text
        if (needle.isEmpty()) return emptyList()
        if (kind != Kind.OTHER && needle.none { it.isLetter() }) return emptyList()
        val found = mutableListOf<Span>()
        var from = 0
        while (true) {
            val at = page.text.indexOf(needle, from)
            if (at < 0) return found
            val end = at + needle.length
            if (startsCleanly(page, at, needle, kind) && endsCleanly(page, end, needle)) {
                // A heading: the whole line, with only its colon after the pick, or in it.
                val text = page.text
                val colonAfter = end < text.length && text[end] == ':' && (end + 1 == text.length || page.lineBreak[end + 1])
                val colonIn = needle.last() == ':' && (end == text.length || page.lineBreak[end])
                val startsLine = kind == Kind.INGREDIENT || at == 0 || page.lineBreak[at - 1]
                val heading = (kind == Kind.INGREDIENT || kind == Kind.STEP) && startsLine && (colonAfter || colonIn)
                val to = if (kind == Kind.INGREDIENT && colonAfter) end + 1 else end
                val wholeLine = startsLine && (colonAfter || end == text.length || page.lineBreak[end])
                found += Span(page.original(at, to), page.lineAt(at), wholeLine, heading)
            }
            from = at + 1
        }
    }

    private fun startsCleanly(page: Folded, at: Int, needle: String, kind: Kind): Boolean {
        val text = page.text
        if (kind == Kind.INGREDIENT) {
            // Only a bullet, a checkbox or spaces between the start of its line and it.
            var i = at - 1
            while (i >= 0 && !page.lineBreak[i]) {
                if (text[i].isLetterOrDigit()) return false
                i--
            }
            return true
        }
        if (at == 0 || page.lineBreak[at - 1]) return true
        if (needle[0].isLetterOrDigit() && text[at - 1].isLetterOrDigit()) return false
        if (!needle[0].isDigit()) return true
        var i = at - 1
        while (i >= 0 && text[i] == ' ' && !page.lineBreak[i]) i--
        if (i < 0 || page.lineBreak[i]) return true
        val c = text[i]
        return !(c.isDigit() || c in "/-" || (c in ".," && i > 0 && text[i - 1].isDigit()))
    }

    private fun endsCleanly(page: Folded, end: Int, needle: String): Boolean {
        val text = page.text
        if (end >= text.length || page.lineBreak[end]) return true
        val last = needle[needle.length - 1]
        if (last.isLetterOrDigit() && text[end].isLetterOrDigit()) return false
        if (!last.isDigit()) return true
        var i = end
        while (i < text.length && text[i] == ' ' && !page.lineBreak[i]) i++
        if (i >= text.length || page.lineBreak[i]) return true
        val c = text[i]
        return !(c.isDigit() || c in "/-" || (c in ".," && i + 1 < text.length && text[i + 1].isDigit()))
    }

    /**
     * [source] folded for comparison, with where each folded character came from: [starts] and
     * [ends] are its code point's range in [source], and [lineBreak] marks a space that stands for
     * whitespace holding a line break.
     */
    private class Folded(private val source: String) {
        val text: String
        val lineBreak: BooleanArray
        private val starts: IntArray
        private val ends: IntArray

        init {
            val out = StringBuilder()
            val s = ArrayList<Int>(); val e = ArrayList<Int>(); val breaks = ArrayList<Boolean>()
            var i = 0
            while (i < source.length) {
                val cp = source.codePointAt(i)
                val next = i + Character.charCount(cp)
                val mapped = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
                for (c in mapped) {
                    if (c.isWhitespace()) {
                        if (out.isEmpty()) continue
                        if (out.last() == ' ') {
                            if (c == '\n' || c == '\r') breaks[breaks.size - 1] = true
                            e[e.size - 1] = next
                            continue
                        }
                        out.append(' '); breaks += (c == '\n' || c == '\r')
                    } else {
                        out.append(PUNCTUATION[c] ?: c); breaks += false
                    }
                    s += i; e += next
                }
                i = next
            }
            while (out.isNotEmpty() && out.last() == ' ') {
                out.setLength(out.length - 1); s.removeAt(s.size - 1); e.removeAt(e.size - 1); breaks.removeAt(breaks.size - 1)
            }
            text = out.toString()
            starts = s.toIntArray(); ends = e.toIntArray(); lineBreak = breaks.toBooleanArray()
        }

        /** The source text behind folded [from] until [to], its whitespace runs as one space. */
        fun original(from: Int, to: Int): String =
            source.substring(starts[from], ends[to - 1]).trim().replace(WHITESPACE, " ")

        /** The line folded [at] is on, counting only lines that hold more than whitespace. */
        fun lineAt(at: Int): Int = lines[at]

        private val lines: IntArray by lazy {
            var n = 0
            IntArray(text.length) { i -> n.also { if (lineBreak[i]) n++ } }
        }

        /**
         * Each line's card: a new one starts at an ingredients heading that follows a steps
         * heading, so a second recipe's card further down is apart from the first. And whether
         * the line is in its card's method: below a steps heading that follows the card's
         * ingredients heading (#271).
         */
        fun cards(): Pair<IntArray, BooleanArray> {
            val lines = mutableListOf<String>()
            var start = 0
            for (i in text.indices) if (lineBreak[i]) { lines += text.substring(start, i); start = i + 1 }
            lines += text.substring(start)
            var card = 0
            var steps = false
            var ingredients = false
            val method = BooleanArray(lines.size)
            val cards = IntArray(lines.size) { k ->
                if (RecipeTextWindow.isIngredientsHeading(lines[k])) {
                    if (steps) { card++; steps = false }
                    ingredients = true
                } else if (RecipeTextWindow.isStepsHeading(lines[k])) {
                    steps = true
                }
                method[k] = ingredients && steps
                card
            }
            return cards to method
        }
    }

    private val WHITESPACE = Regex("\\s+")

    private val PUNCTUATION: Map<Char, Char> = buildMap {
        "‘’‚‛′`´".forEach { put(it, '\'') }
        "“”„‟″«»".forEach { put(it, '"') }
        "‐‑‒–—―−".forEach { put(it, '-') }
        "⁄∕".forEach { put(it, '/') }
        put('×', 'x')
    }
}
