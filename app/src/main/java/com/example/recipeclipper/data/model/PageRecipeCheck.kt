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
 */
object PageRecipeCheck {

    enum class Kind { NAME, INGREDIENT, STEP, OTHER }

    /** The verified recipe, with the page's own text for every field kept; null if too little is left. */
    fun verify(page: String, picked: PageSelection): PageSelection? {
        val folded = Folded(page)
        val name = picked.name?.let { find(folded, it, Kind.NAME) } ?: return null
        val ingredients = picked.ingredients.mapNotNull { find(folded, it, Kind.INGREDIENT) }
        val steps = picked.steps.mapNotNull { find(folded, it, Kind.STEP) }
        if (ingredients.isEmpty() && steps.isEmpty()) return null
        fun other(s: String?) = s?.let { find(folded, it, Kind.OTHER) }
        return PageSelection(
            name, ingredients, steps, other(picked.yield),
            other(picked.prepTime), other(picked.cookTime), other(picked.totalTime)
        )
    }

    /** The page's own text for [picked], if it is on [page] as a whole span of this [kind]. */
    fun find(page: String, picked: String, kind: Kind): String? = find(Folded(page), picked, kind)

    private fun find(page: Folded, picked: String, kind: Kind): String? {
        val needle = Folded(picked).text
        if (needle.isEmpty()) return null
        if (kind != Kind.OTHER && needle.none { it.isLetter() }) return null
        var from = 0
        while (true) {
            val at = page.text.indexOf(needle, from)
            if (at < 0) return null
            val end = at + needle.length
            if (startsCleanly(page, at, needle, kind) && endsCleanly(page, end, needle)) {
                return page.original(at, end)
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
