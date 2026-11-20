package com.example.recipeclipper.data.model

import kotlin.math.max
import kotlin.math.min

/** The step that has to be started ahead of time (#397): its index, and the sentence that says so. */
data class AheadStep(val step: Int, val quote: String)

/**
 * Finds the step of a recipe that has to be started the evening before (#397): a marinade, a soak,
 * a thaw, a rise "overnight". Pure; iOS's `AheadSteps` is the same, pinned by the differential
 * corpus's `Ahead` rows. The words are `shared/tables/<language>/ahead.json`, in the recipe's own
 * language (#14): [LanguageWords] null is a language with no table, which detects nothing.
 *
 * Matched phrases only, never guessed. A sentence qualifies by
 * - an `always` phrase ("overnight", "the day before", "make ahead"), or
 * - a `verb` ("marinate", "soak", "chill", "rest", "rise"…) together with at least two hours, the
 *   hours right after the verb or just before it ("Marinate at least 8 hours", "For 4 hours, chill").
 *   "Rest for 5 minutes" has no hours, and "up to 2 hours" is a limit, not a wait.
 * A match that overlaps a `skip` phrase ("soak up the sauce", "no need to marinate") is dropped.
 * The first step, in step order, whose sentence qualifies is the answer; its [AheadStep.quote]
 * is that sentence, trimmed.
 */
object AheadSteps {

    /** The longest quote: a longer sentence is cut at a word and ends in an ellipsis. */
    const val QUOTE_MAX = 100

    // How far the hours may sit from their verb: after it, or just before it.
    private const val AFTER_MAX = 60
    private const val BEFORE_MAX = 40

    // The least hours that count as ahead of time.
    private const val MIN_HOURS = 2.0

    // Kotlin makes IGNORE_CASE Unicode-aware on the JVM, as ICU is on iOS.
    private val options = setOf(RegexOption.IGNORE_CASE)

    private class Rules(private val words: LanguageWords) {
        private val front = if (words.spaced) """(?<!\p{L})""" else ""
        private val back = if (words.spaced) """(?!\p{L})""" else ""

        private fun alternation(key: String) = SharedTables.alternation(words.strings("ahead", key))

        private fun whole(key: String) = Regex(front + alternation(key) + back, options)

        val always = whole("always")
        val verbs = whole("verbs")
        val skip = whole("skip")

        // Words right before an amount that make it a limit ("up to 2 hours").
        val upTo = Regex(front + alternation("upTo") + """\s*\z""", options)

        // A number (2 or more) or a count word, a range's first end deciding, then an hours word.
        val hours = Regex(
            (if (words.spaced) """(?<![\p{L}\d/.,])""" else """(?<![\d/.,])""") +
                """(?:(\d+(?:[.,]\d+)?)|(${alternation("counts")}))""" +
                """(?:\s*(?:[-–—]|${words.rangeWords})\s*\d+(?:[.,]\d+)?)?\s*\+?\s*""" +
                alternation("hours") + back,
            options
        )

        fun readable(text: String) = words.readable(text)
    }

    private fun rules(words: LanguageWords): Rules = words.compiled(Rules::class) { Rules(it) }

    /**
     * The first step with an ahead-of-time sentence, or null: none, or [words] null (a language
     * the app has no words for).
     */
    fun find(steps: List<String>, words: LanguageWords?): AheadStep? {
        val rules = words?.let(::rules) ?: return null
        steps.forEachIndexed { index, step ->
            for (sentence in sentences(step)) {
                if (qualifies(rules.readable(sentence), rules)) return AheadStep(index, quote(sentence))
            }
        }
        return null
    }

    private fun qualifies(sentence: String, rules: Rules): Boolean {
        val skips = rules.skip.findAll(sentence).map { it.range }.toList()
        fun blocked(first: Int, last: Int) = skips.any { it.first <= last && first <= it.last }

        if (rules.always.findAll(sentence).any { !blocked(it.range.first, it.range.last) }) return true

        val hours = hourRanges(sentence, rules)
        return rules.verbs.findAll(sentence).any { verb ->
            hours.any { wait ->
                val after = wait.first > verb.range.last && wait.first - verb.range.last - 1 <= AFTER_MAX
                val before = wait.last < verb.range.first && verb.range.first - wait.last - 1 <= BEFORE_MAX
                (after || before) &&
                    !blocked(min(verb.range.first, wait.first), max(verb.range.last, wait.last))
            }
        }
    }

    /** Where [sentence] names two hours or more, leaving out a limit ("up to 12 hours"). */
    private fun hourRanges(sentence: String, rules: Rules): List<IntRange> =
        rules.hours.findAll(sentence).filter { match ->
            val figure = match.groupValues[1]
            val enough = if (figure.isEmpty()) true else (figure.replace(',', '.').toDoubleOrNull() ?: 0.0) >= MIN_HOURS
            val before = sentence.substring(max(0, match.range.first - 30), match.range.first)
            enough && !rules.upTo.containsMatchIn(before)
        }.map { it.range }.toList()

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C' || c == ' '

    // A lower-case Latin letter: what follows an abbreviation's period ("8 Std. im Kühlschrank"),
    // never a new sentence.
    private fun isLower(c: Char) = c in 'a'..'z' || (c in 'à'..'ÿ' && c != '÷')

    /**
     * [text] cut into sentences: after `.`, `!` or `?` followed by a space and not a lower-case
     * letter (so "1.5 hours" and "8 Std. im" stay whole) or the end, and after `。`, `！` or `？`
     * wherever they stand.
     */
    internal fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val end = when {
                c == '。' || c == '！' || c == '？' -> i + 1
                (c == '.' || c == '!' || c == '?') && endsSentence(text, i + 1) -> i + 1
                else -> -1
            }
            if (end < 0) {
                i++
                continue
            }
            out.add(text.substring(start, end))
            i = end
            while (i < text.length && isSpace(text[i])) i++
            start = i
        }
        if (start < text.length) out.add(text.substring(start))
        return out.filter { s -> s.any { !isSpace(it) } }
    }

    // Whether a sentence ends before [next]: at the end, or at a space and then no lower-case letter.
    private fun endsSentence(text: String, next: Int): Boolean {
        if (next == text.length) return true
        if (!isSpace(text[next])) return false
        var i = next
        while (i < text.length && isSpace(text[i])) i++
        return i == text.length || !isLower(text[i])
    }

    /** [sentence] on one line, and no longer than [QUOTE_MAX]: cut at a word, with an ellipsis. */
    internal fun quote(sentence: String): String {
        val text = sentence.trim().split(Regex("""[ \t\n\r\u000B\u000C ]+""")).joinToString(" ")
        if (text.length <= QUOTE_MAX) return text
        val cut = text.take(QUOTE_MAX)
        val space = cut.lastIndexOf(' ')
        val head = if (space >= QUOTE_MAX / 2) cut.substring(0, space) else cut
        return head.trimEnd(',', ';', ':', ' ', '-', '–') + "…"
    }
}
