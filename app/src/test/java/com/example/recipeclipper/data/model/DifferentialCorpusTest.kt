package com.example.recipeclipper.data.model

import com.example.recipeclipper.data.remote.CardHeadings
import com.example.recipeclipper.data.remote.SiteRules
import com.example.recipeclipper.data.remote.WprmIngredients
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Keeps iOS's `DifferentialCorpusTests.swift` generated from this Kotlin, and regenerates it.
 *
 * Every `Ing(...)` and `Ins(...)` row in the Swift file is recomputed here from its input
 * string alone, and the whole file is rewritten with the results to
 * `app/build/differential-corpus/DifferentialCorpusTests.swift`. A row read with another
 * language's words (#15) names it after the input, `Ing("2 EL Zucker", lang: "de"),`; a row
 * without one is English. The test fails when that
 * differs from the committed file: a change to the scaler, converters or timers that forgot
 * to regenerate. To regenerate, or to add a row, write just the input (`Ing("1,5 kg flour"),`
 * or `Ins("Bake 1,5 hours."),`) in the Swift file, run this test, and copy the generated file
 * over the Swift one. Never type an expected value by hand.
 *
 * `Groc([...])` rows (#50) work the same way: write only the lines (`Groc(["2 eggs", "3 eggs"]),`,
 * optionally `, lang: "de"`), and the combined line and each line's aisle are filled in.
 *
 * `Pant("line", "pantry name")` rows (#51) pin [PantryMatch.covered]: the line against one in-stock
 * pantry item of that name, in the same language; write only those two strings.
 *
 * `Ics("text")` rows (#52) pin [MealPlanIcs.contentLine]: the text as an .ics SUMMARY line,
 * escaped and folded at 75 octets; write only the text.
 *
 * `Step("step", [lines])` rows (#101) pin [StepAmounts.annotate]: the step with each amount in
 * ⟦ ⟧, once against the lines as given and once against them doubled in Metric; write only the
 * step and the lines (optionally `, lang: "fr"`).
 *
 * `Trail("line")` rows (#99) pin [GroceryDecisions.split]: the line's core and the trailing text
 * the model may be asked about, or nil; write only the line (optionally `, lang: "fr"`).
 *
 * `Pick(.kind, "page", "picked")` rows (#103) pin [PageRecipeCheck.find]: the page's own text
 * for what the model picked, or nil; write only the kind (name, ingredient, step, other), the
 * page text and the pick.
 *
 * `Wprm("markup", [lines])` rows (#118) pin [WprmIngredients.refine]: JSON-LD's lines refined by
 * a WP Recipe Maker card's markup; write only the markup (single-quoted attributes) and the lines.
 * `Heads("markup", [lines])` rows (#119) pin [CardHeadings.refine] the same way, for a Tasty Recipes
 * or Mediavine Create card.
 *
 * `Site("host", "markup", [lines], [steps])` rows (#120) pin [SiteRules]: JSON-LD's lines and steps
 * refined by the host's rules in `site-rules.json` (then any site's cards) on that markup; write
 * only the host (no "www."), the markup (single-quoted attributes), the lines and the steps.
 *
 * Only these sections are generated here, plus the Swift test's `systems` list and the
 * header comment naming it, both written from [systems] below. The other sections of the
 * Swift file (stripHtml, yields, URLs, formatting, clocks, JSON-LD) are left exactly as they are.
 */
class DifferentialCorpusTest {

    private val swiftFile = File("../ios/RecipeClipperTests/Model/DifferentialCorpusTests.swift")
    private val outFile = File("build/differential-corpus/DifferentialCorpusTests.swift")

    // Must match the Swift test's `factors`. Its `systems` is written from this list.
    private val factors = listOf(0.5, 1.5, 2.0, 1.0 / 3.0)
    private val systems = listOf(
        UnitSystem.OUNCES to false, UnitSystem.OUNCES to true,
        UnitSystem.METRIC to false, UnitSystem.METRIC to true
    )

    private val row = Regex("""^(\s*)(Ing|Ins)\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")

    // A grocery row (#50): the lines to add up, then optionally their language.
    private val groceryRow = Regex("""^(\s*)Groc\(\[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?""")
    // A pantry row (#51): a recipe line, a pantry item's name, optionally their language.
    private val pantryRow = Regex("""^(\s*)Pant\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A calendar-file row (#52): one summary's text.
    // A step row (#101): one step, the ingredient lines, optionally their language.
    private val stepRow = Regex("""^(\s*)Step\("((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?""")
    private val icsRow = Regex("""^(\s*)Ics\("((?:[^"\\]|\\.)*)"""")
    // A Chef mode row (#100): a step, a short version of it, optionally their language.
    private val shortRow = Regex("""^(\s*)Short\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A page-pick row (#103): the kind, the page's text, what the model picked from it.
    private val pickRow = Regex("""^(\s*)Pick\(\.(name|ingredient|step|other), "((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)"""")
    // A count-bracket row (#104): an ingredient line, optionally its language.
    private val countRow = Regex("""^(\s*)Count\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A close-names row (#104): two ingredient names, optionally their language.
    private val closeRow = Regex("""^(\s*)Close\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A recipe-card row: a WP Recipe Maker (#118) or Tasty/Create (#119) card's markup, then JSON-LD's lines.
    private val cardRow = Regex("""^(\s*)(Wprm|Heads)\("((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*]""")
    // A site-rule row (#120): a host, a page's markup, then JSON-LD's lines and steps.
    private val siteRow = Regex(
        """^(\s*)Site\("([a-z0-9.-]+)", "((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*], \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*]"""
    )
    // A trailing-text row (#99): a grocery line, optionally its language.
    private val trailRow = Regex("""^(\s*)Trail\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")

    // The header comment's "// [ounces, ounces+liquids, ...], then".
    private val systemsComment = Regex("""^// \[[a-z+, ]*], then$""")

    // The body of the Swift `systems` array: "(.ounces, false), (.ounces, true), ...,".
    private val systemsArray = Regex("""^(\s*)(\(\.\w+, (?:true|false)\),\s*)+$""")

    @Test fun `the iOS differential corpus matches the Kotlin`() {
        assumeTrue("no iOS project beside app/", swiftFile.exists())
        val original = swiftFile.readText()
        val regenerated = original.lines().joinToString("\n") { regenerate(it) }
        outFile.parentFile?.mkdirs()
        outFile.writeText(regenerated)
        assertEquals(
            "The corpus is stale. Copy app/${outFile.path} over ios/RecipeClipperTests/Model/" +
                    "DifferentialCorpusTests.swift (it was just generated from the Kotlin).",
            original, regenerated
        )
    }

    private fun regenerate(line: String): String {
        if (systemsComment.matches(line)) {
            return systems.joinToString(", ", "// [", "], then") { (system, liquids) ->
                system.name.lowercase() + if (liquids) "+liquids" else ""
            }
        }
        systemsArray.find(line)?.let { a ->
            return a.groupValues[1] + systems.joinToString(" ") { (system, liquids) ->
                "(.${swiftCase(system)}, $liquids),"
            }
        }
        groceryRow.find(line)?.let { g -> return groceryRow(g) }
        pantryRow.find(line)?.let { p -> return pantryRow(p) }
        countRow.find(line)?.let { m -> return countRow(m) }
        cardRow.find(line)?.let { m ->
            val (kind, html) = m.groupValues[2] to unescape(m.groupValues[3])
            val lines = literal.findAll(m.groupValues[4]).map { unescape(it.groupValues[1]) }.toList()
            val page = Jsoup.parse(html)
            val refined = if (kind == "Wprm") WprmIngredients.refine(page, lines) else CardHeadings.refine(page, lines)
            return m.groupValues[1] + "$kind(${q(html)}, ${list(lines)}, ${list(refined)}),"
        }
        siteRow.find(line)?.let { m ->
            val (host, html) = m.groupValues[2] to unescape(m.groupValues[3])
            val (lines, steps) = listOf(4, 5).map { g -> literal.findAll(m.groupValues[g]).map { unescape(it.groupValues[1]) }.toList() }
            val url = "https://www.$host/r"
            val refined = SiteRules.ingredients(Jsoup.parse(html), url, lines)
            return m.groupValues[1] +
                "Site(${q(host)}, ${q(html)}, ${list(lines)}, ${list(steps)}, ${list(refined)}, ${list(SiteRules.steps(url, steps))}),"
        }
        closeRow.find(line)?.let { m ->
            val (a, b) = unescape(m.groupValues[2]) to unescape(m.groupValues[3])
            val language = m.groupValues[4].ifEmpty { "en" }
            val lang = if (m.groupValues[4].isEmpty()) "" else ", lang: ${q(language)}"
            val close = DecisionCandidates.close(a, b, LanguageWords.forTag(language)!!)
            return m.groupValues[1] + "Close(${q(a)}, ${q(b)}$lang, $close),"
        }
        trailRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            val language = m.groupValues[3].ifEmpty { "en" }
            val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(language)}"
            val split = GroceryDecisions.split(text, LanguageWords.forTag(language)!!)
            val (core, trailing) = split?.let { q(it.core) to q(it.trailing) } ?: ("nil" to "nil")
            return m.groupValues[1] + "Trail(${q(text)}$lang, $core, $trailing),"
        }
        pickRow.find(line)?.let { p ->
            val (kind, page, picked) = Triple(p.groupValues[2], unescape(p.groupValues[3]), unescape(p.groupValues[4]))
            val found = PageRecipeCheck.find(page, picked, PageRecipeCheck.Kind.valueOf(kind.uppercase()))
            return p.groupValues[1] + "Pick(.$kind, ${q(page)}, ${q(picked)}, ${found?.let { q(it) } ?: "nil"}),"
        }
        shortRow.find(line)?.let { s ->
            val (step, short) = unescape(s.groupValues[2]) to unescape(s.groupValues[3])
            val language = s.groupValues[4].ifEmpty { null }
            val words = if (language == null) LanguageWords.ENGLISH else LanguageWords.forTag(language)!!
            val lang = if (language == null) "" else ", lang: ${q(language)}"
            val accepted = ShortStepCheck.accept(step, short, words)?.let { q(it) } ?: "nil"
            return s.groupValues[1] + "Short(${q(step)}, ${q(short)}$lang, $accepted),"
        }
        stepRow.find(line)?.let { m -> return stepRow(m) }
        icsRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            return m.groupValues[1] + "Ics(${q(text)}, ${q(MealPlanIcs.contentLine("SUMMARY", text))}),"
        }
        val m = row.find(line) ?: return line
        val indent = m.groupValues[1]
        val input = unescape(m.groupValues[3])
        val language = m.groupValues[4].ifEmpty { null }
        val words = if (language == null) LanguageWords.ENGLISH else LanguageWords.forTag(language)!!
        val lang = if (language == null) "" else ", lang: ${q(language)}"
        return indent + when (m.groupValues[2]) {
            "Ing" -> ingredientRow(input, words, lang)
            else -> instructionRow(input, words, lang)
        } + ","
    }

    private fun ingredientRow(line: String, words: LanguageWords, lang: String): String {
        val scaled = factors.map { IngredientScaler.scale(line, it, words) }
        val converted = systems.map { (system, liquids) -> UnitConverter.convert(line, system, liquids, words = words) }
        // As the reading view renders: scale, then convert with the original line's separator.
        val scaledMetric = IngredientRendering.render(listOf(line), 2.0, UnitSystem.METRIC, false, words).single()
        val halfOunces = IngredientRendering.render(listOf(line), 0.5, UnitSystem.OUNCES, true, words).single()
        val name = IngredientName.of(line, words)?.let { q(it) } ?: "nil"
        return "Ing(${q(line)}$lang, ${list(scaled)}, ${list(converted)}, ${q(scaledMetric)}, ${q(halfOunces)}, $name)"
    }

    /** `Groc([lines], lang:, combined or nil, [each line's aisle])`: GroceryCombiner.combine and Aisles.of. */
    private fun groceryRow(m: MatchResult): String {
        val lines = literal.findAll(m.groupValues[2]).map { unescape(it.groupValues[1]) }.toList()
        val language = m.groupValues[3].ifEmpty { null }
        val words = if (language == null) LanguageWords.ENGLISH else LanguageWords.forTag(language)!!
        val lang = if (language == null) "" else ", lang: ${q(language)}"
        val combined = GroceryCombiner.combine(lines, words)?.let { q(it) } ?: "nil"
        val aisles = list(lines.map { Aisles.of(it, words).key })
        return m.groupValues[1] + "Groc(${list(lines)}$lang, $combined, $aisles),"
    }

    /**
     * `Count(line, lang:, asks, [unsure, total, each])`: [IngredientScaler.needsCountDecision], then
     * the line doubled with each answer to the count-bracket question.
     */
    private fun countRow(m: MatchResult): String {
        val line = unescape(m.groupValues[2])
        val language = m.groupValues[3].ifEmpty { null }
        val words = if (language == null) LanguageWords.ENGLISH else LanguageWords.forTag(language)!!
        val lang = if (language == null) "" else ", lang: ${q(language)}"
        val asks = IngredientScaler.needsCountDecision(line, words)
        val doubled = listOf(null, CountBracket.TOTAL, CountBracket.EACH).map { IngredientScaler.scale(line, 2.0, words, it) }
        return m.groupValues[1] + "Count(${q(line)}$lang, $asks, ${list(doubled)}),"
    }

    /** `Pant(line, name, lang:, covered)`: [PantryMatch.covered] against one in-stock item called [name]. */
    private fun pantryRow(m: MatchResult): String {
        val line = unescape(m.groupValues[2])
        val name = unescape(m.groupValues[3])
        val language = m.groupValues[4].ifEmpty { "en" }
        val lang = if (m.groupValues[4].isEmpty()) "" else ", lang: ${q(language)}"
        val item = PantryItem(1, name, null, language, Aisle.OTHER, inStock = true, alwaysHave = false, purchasedDay = null, expiresDay = null)
        val covered = PantryMatch.covered(line, language, listOf(item))
        return m.groupValues[1] + "Pant(${q(line)}, ${q(name)}$lang, $covered),"
    }

    /** `Step(step, [lines], lang:, as given, doubled in Metric)`: [StepAmounts.annotate], marked. */
    private fun stepRow(m: MatchResult): String {
        val step = unescape(m.groupValues[2])
        val lines = literal.findAll(m.groupValues[3]).map { unescape(it.groupValues[1]) }.toList()
        val language = m.groupValues[4].ifEmpty { null }
        val words = if (language == null) LanguageWords.ENGLISH else LanguageWords.forTag(language)!!
        val lang = if (language == null) "" else ", lang: ${q(language)}"
        val asGiven = StepAmounts.marked(StepAmounts.annotate(listOf(step), lines, words).single())
        val doubled = IngredientRendering.render(lines, 2.0, UnitSystem.METRIC, false, words)
        val metric = StepAmounts.marked(StepAmounts.annotate(listOf(step), doubled, words).single())
        return m.groupValues[1] + "Step(${q(step)}, ${list(lines)}$lang, ${q(asGiven)}, ${q(metric)}),"
    }

    private fun instructionRow(line: String, words: LanguageWords, lang: String): String {
        val celsius = TemperatureConverter.convert(line, TemperatureUnit.CELSIUS, words)
        val fahrenheit = TemperatureConverter.convert(line, TemperatureUnit.FAHRENHEIT, words)
        val timer = StepTimers.parse(line, words)?.toString() ?: "nil"
        return "Ins(${q(line)}$lang, ${q(celsius)}, ${q(fahrenheit)}, $timer)"
    }

    /** AS_WRITTEN is `.asWritten` in Swift. */
    private fun swiftCase(system: UnitSystem): String =
        system.name.lowercase().split('_')
            .mapIndexed { i, word -> if (i == 0) word else word.replaceFirstChar(Char::uppercase) }
            .joinToString("")

    private fun list(items: List<String>) = items.joinToString(", ", "[", "]") { q(it) }

    /** A Swift string literal. */
    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\t' -> append("\\t")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                Character.isISOControl(c) || (c != ' ' && Character.isSpaceChar(c)) ||
                        Character.getType(c) == Character.FORMAT.toInt() ->
                    append("\\u{").append(Integer.toHexString(c.code)).append('}')
                else -> append(c)
            }
        }
        append('"')
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') { append(c); i++; continue }
            val next = s[i + 1]
            i += 2
            when (next) {
                't' -> append('\t')
                'n' -> append('\n')
                'r' -> append('\r')
                '0' -> append('\u0000')
                'u' -> {
                    val end = s.indexOf('}', i)
                    appendCodePoint(s.substring(i + 1, end).toInt(16))
                    i = end + 1
                }
                else -> append(next)
            }
        }
    }
}
