package com.example.recipeclipper.data.model

import com.example.recipeclipper.data.remote.CardHeadings
import com.example.recipeclipper.data.remote.PhotoLine
import com.example.recipeclipper.data.remote.PhotoTextSorter
import com.example.recipeclipper.data.remote.RecipeTextSplitter
import com.example.recipeclipper.data.remote.SiteRules
import com.example.recipeclipper.data.remote.WprmIngredients
import com.example.recipeclipper.ui.recipe.RecipeRenderer
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
 * `core/build/differential-corpus/DifferentialCorpusTests.swift`. A row read with another
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
 * `UseUp("quantity", "name", [lines])` rows (#147) pin [PantryUseUp.rows]: the ticked lines against
 * one in-stock pantry item with that quantity and name, as not listed, asked, used up or the new
 * quantity; write only those (optionally `, lang: "de"`).
 *
 * `Ics("text")` rows (#52) pin [MealPlanIcs.contentLine]: the text as an .ics SUMMARY line,
 * escaped and folded at 75 octets; write only the text.
 *
 * `Dur("time")` rows (#179) pin [Durations.format]: a prep, cook or total time
 * as a site (or the tour's sample) writes it, as the app shows it, or nil (hidden); write only
 * the time (optionally `, lang: "de"`; a language with no tables reads ISO alone).
 *
 * `Step("step", [lines])` rows (#101) pin [StepAmounts.annotate]: the step with each amount in
 * ⟦ ⟧, once against the lines as given and once against them doubled in Metric; write only the
 * step and the lines (optionally `, lang: "fr"`).
 *
 * `Trail("line")` rows (#99) pin [GroceryDecisions.split]: the line's core and the trailing text
 * the model may be asked about, or nil; write only the line (optionally `, lang: "fr"`).
 *
 * `NameCut("line", "name")` rows (#99) pin [GroceryDecisions.nameQuestion] (whether the model is
 * asked the line's name), [GroceryDecisions.nameSplit] (the core and trailing text once it
 * answers with that name, or nil) and [GroceryDecisions.cutAisle] (the aisle key once that
 * trailing text is also junk, or nil, #158); write only the line (optionally `, lang: "fr"`)
 * and the name.
 *
 * `Short("step", "short")` rows (#100) pin [ShortStepCheck.accept]: the short version, tidied, or
 * nil; write only the step and the short version (optionally `, lines: [...]`, the recipe's
 * ingredient lines (#129), and `, lang: "de"`).
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
 * `Render("yield", target, [lines], [steps])` rows (#169) pin [RecipeRenderer.content]: a recipe
 * with that yield (or nil), chosen servings (or nil), ingredients and steps, rendered in Metric
 * and Celsius with amounts in steps on, as the servings, ingredients, steps, timers, amounts,
 * short steps and their amounts; write only those (optionally `, shorts: [nil, "short"]`, Chef
 * mode's saved short steps, `, lang: "de"`, and the model's answers (#174): `, trailing:
 * ["(dfsafs -": "junk"]` about trailing texts and `, names: ["2 onions dfsafs": "onions"]` about
 * lines' names).
 *
 * `Split("text")` rows (#208) pin [RecipeTextSplitter.detectAndSplit]: free text (a Reddit post,
 * `\n` between lines) as the language its words say (or nil: English), then the ingredients and
 * steps (nil: no recipe) and the yield, prep, cook and total times; write only the text.
 *
 * `Photo(["line", ...])` rows (#208) pin [PhotoTextSorter.sort]: a photo's lines as the language
 * they were read in, whether they sorted, the ingredients, steps and lines to check; write only
 * the lines. `Sus("line")` rows pin [PhotoTextSorter.suspect] (optionally `, lang: "de"`).
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
    // A use-up row (#147): a pantry item's quantity and name, the ticked lines, optionally their language.
    private val useUpRow = Regex(
        """^(\s*)UseUp\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?"""
    )
    // A calendar-file row (#52): one summary's text.
    // A step row (#101): one step, the ingredient lines, optionally their language.
    private val stepRow = Regex("""^(\s*)Step\("((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?""")
    private val icsRow = Regex("""^(\s*)Ics\("((?:[^"\\]|\\.)*)"""")
    // A duration row (#179): a prep, cook or total time, optionally its language.
    private val durationRow = Regex("""^(\s*)Dur\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A Chef mode row (#100): a step, a short version of it, optionally the recipe's ingredient
    // lines (#129) and their language.
    private val shortRow = Regex("""^(\s*)Short\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)"(?:, lines: \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*])?(?:, lang: "([a-z]+)")?""")
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
    // A splitter row (#208): free text, a Reddit post's or a photo's.
    private val splitRow = Regex("""^(\s*)Split\("((?:[^"\\]|\\.)*)"""")
    // A photo row (#208): the lines read from a photo.
    private val photoRow = Regex("""^(\s*)Photo\(\[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*]""")
    // A suspect-amount row (#198, #208): a line read from a photo, optionally its language.
    private val suspectRow = Regex("""^(\s*)Sus\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A trailing-text row (#99): a grocery line, optionally its language.
    private val trailRow = Regex("""^(\s*)Trail\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A name-cut row (#99): a grocery line, optionally its language, the model's name for it.
    private val nameCutRow = Regex("""^(\s*)NameCut\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?, "((?:[^"\\]|\\.)*)"""")
    // A rendering row (#169): a yield or nil, chosen servings or nil, the lines, the steps,
    // optionally Chef mode's short steps (a string or nil each), their language, and the model's
    // answers (#174): trailing texts and lines' names, each a Swift dictionary of strings.
    private val renderRow = Regex(
        """^(\s*)Render\((nil|"(?:[^"\\]|\\.)*"), (nil|\d+), \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*], """ +
            """\[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, shorts: \[((?:\s*(?:nil|"(?:[^"\\]|\\.)*"),?)*)\s*])?(?:, lang: "([a-z]+)")?""" +
            """(?:, trailing: \[((?:\s*"(?:[^"\\]|\\.)*": "(?:[^"\\]|\\.)*",?)*)\s*])?""" +
            """(?:, names: \[((?:\s*"(?:[^"\\]|\\.)*": "(?:[^"\\]|\\.)*",?)*)\s*])?"""
    )
    private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
    private val literalPair = Regex(""""((?:[^"\\]|\\.)*)": "((?:[^"\\]|\\.)*)"""")
    private val optionalLiteral = Regex("""nil|"((?:[^"\\]|\\.)*)"""")

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
        renderRow.find(line)?.let { m -> return renderRow(m) }
        groceryRow.find(line)?.let { g -> return groceryRow(g) }
        pantryRow.find(line)?.let { p -> return pantryRow(p) }
        useUpRow.find(line)?.let { m -> return useUpRow(m) }
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
        nameCutRow.find(line)?.let { m ->
            val (text, name) = unescape(m.groupValues[2]) to unescape(m.groupValues[4])
            val words = LanguageWords.forTag(m.groupValues[3].ifEmpty { "en" })!!
            val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(m.groupValues[3])}"
            val ask = GroceryDecisions.nameQuestion(text, words) != null
            val split = GroceryDecisions.nameSplit(text, name, words)
            val (core, trailing) = split?.let { q(it.core) to q(it.trailing) } ?: ("nil" to "nil")
            val named = mapOf(DecisionQuestion.ingredientName(text, words.language) to name)
            val junk = GroceryDecisions.split(text, words, Decisions(named))
                ?.let { named + (DecisionQuestion.trailingText(it.trailing, words.language) to "junk") } ?: named
            val aisle = GroceryDecisions.cutAisle(text, words, Decisions(junk))?.let { q(it.key) } ?: "nil"
            return m.groupValues[1] + "NameCut(${q(text)}$lang, ${q(name)}, $ask, $core, $trailing, $aisle),"
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
            val lines = literal.findAll(s.groupValues[4]).map { unescape(it.groupValues[1]) }.toList()
            val language = s.groupValues[5].ifEmpty { null }
            val words = if (language == null) LanguageWords.ENGLISH else LanguageWords.forTag(language)!!
            val given = if (lines.isEmpty()) "" else ", lines: ${list(lines)}"
            val lang = if (language == null) "" else ", lang: ${q(language)}"
            val accepted = ShortStepCheck.accept(step, short, words, lines)?.let { q(it) } ?: "nil"
            return s.groupValues[1] + "Short(${q(step)}, ${q(short)}$given$lang, $accepted),"
        }
        stepRow.find(line)?.let { m -> return stepRow(m) }
        durationRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            val language = m.groupValues[3].ifEmpty { "en" }
            val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(language)}"
            val shown = Durations.format(text, LanguageWords.forTag(language))?.let { q(it) } ?: "nil"
            return m.groupValues[1] + "Dur(${q(text)}$lang, $shown),"
        }
        splitRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            val language = RecipeTextSplitter.languageOf(text)?.let { q(it) } ?: "nil"
            val split = RecipeTextSplitter.detectAndSplit(text)
            val (ingredients, steps) = split?.let { list(it.ingredients) to list(it.instructions) } ?: ("nil" to "nil")
            val extras = split?.let { optionalList(listOf(it.yield, it.prepTime, it.cookTime, it.totalTime)) } ?: "[]"
            return m.groupValues[1] + "Split(${q(text)}, $language, $ingredients, $steps, $extras),"
        }
        photoRow.find(line)?.let { m ->
            val lines = literal.findAll(m.groupValues[2]).map { unescape(it.groupValues[1]) }.toList()
            val reading = PhotoTextSorter.sort(lines.map { PhotoLine(it) })
            val language = reading.language?.let { q(it) } ?: "nil"
            return m.groupValues[1] + "Photo(${list(lines)}, $language, ${reading.sorted}, " +
                "${list(reading.ingredients)}, ${list(reading.instructions)}, ${list(reading.uncertain)}),"
        }
        suspectRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            val language = m.groupValues[3].ifEmpty { "en" }
            val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(language)}"
            return m.groupValues[1] + "Sus(${q(text)}$lang, ${PhotoTextSorter.suspect(text, LanguageWords.forTag(language)!!)}),"
        }
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

    /**
     * `UseUp(quantity, name, [lines], lang:, result)`: [PantryUseUp.rows] against one in-stock
     * item: `.none` (not listed), `.ask`, `.usedUp` or `.subtract("new quantity")`.
     */
    private fun useUpRow(m: MatchResult): String {
        val quantity = unescape(m.groupValues[2])
        val name = unescape(m.groupValues[3])
        val lines = literal.findAll(m.groupValues[4]).map { unescape(it.groupValues[1]) }.toList()
        val language = m.groupValues[5].ifEmpty { "en" }
        val lang = if (m.groupValues[5].isEmpty()) "" else ", lang: ${q(language)}"
        val item = PantryItem(1, name, quantity, language, Aisle.OTHER, inStock = true, alwaysHave = false, purchasedDay = null, expiresDay = null)
        val result = when (val change = PantryUseUp.rows(lines, language, listOf(item)).singleOrNull()?.change) {
            null -> ".none"
            UseUpChange.Ask -> ".ask"
            is UseUpChange.Subtract -> change.after?.let { ".subtract(${q(it)})" } ?: ".usedUp"
        }
        return m.groupValues[1] + "UseUp(${q(quantity)}, ${q(name)}, ${list(lines)}$lang, $result),"
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

    /**
     * `Render(yield, target, [lines], [steps], shorts:, lang:, [base, target] or nil, [ingredients],
     * [steps], [timers], [amounts], [short steps], [short amounts] or nil)`: [RecipeRenderer.content]
     * in Metric and Celsius with amounts in steps on, each step's amounts marked with ⟦ ⟧.
     */
    private fun renderRow(m: MatchResult): String {
        val yield = m.groupValues[2].takeIf { it != "nil" }?.let { unescape(it.substring(1, it.length - 1)) }
        val target = m.groupValues[3].toIntOrNull()
        val (lines, steps) = listOf(4, 5).map { g -> literal.findAll(m.groupValues[g]).map { unescape(it.groupValues[1]) }.toList() }
        val shorts = optionalLiteral.findAll(m.groupValues[6])
            .map { if (it.value == "nil") null else unescape(it.groupValues[1]) }.toList()
        val language = m.groupValues[7].ifEmpty { "en" }
        val (trailing, names) = listOf(8, 9).map { g ->
            literalPair.findAll(m.groupValues[g]).map { unescape(it.groupValues[1]) to unescape(it.groupValues[2]) }.toList()
        }
        val recipe = Recipe(
            name = "Render", image = null, ingredients = lines, instructions = steps, prepTime = null, cookTime = null,
            totalTime = null, yield = yield, sourceUrl = "https://example.com/r", language = language, servingsTarget = target
        )
        val decisions = Decisions(
            (trailing.map { (text, answer) -> DecisionQuestion.trailingText(text, language) to answer } +
                names.map { (line, name) -> DecisionQuestion.ingredientName(line, language) to name }).toMap()
        )
        val settings = RecipeRenderer.Settings(
            unitSystem = UnitSystem.METRIC, temperatureUnit = TemperatureUnit.CELSIUS, amountsInSteps = true,
            decisions = decisions
        )
        val shown = RecipeRenderer.content(recipe, settings, shorts)
        val given = (if (shorts.isEmpty()) "" else ", shorts: ${optionalList(shorts)}") +
            (if (m.groupValues[7].isEmpty()) "" else ", lang: ${q(language)}") +
            (if (trailing.isEmpty()) "" else ", trailing: ${pairs(trailing)}") +
            (if (names.isEmpty()) "" else ", names: ${pairs(names)}")
        val servings = shown.servings?.let { "[${it.base}, ${it.target}]" } ?: "nil"
        val timers = shown.stepTimerSeconds.joinToString(", ", "[", "]") { it?.toString() ?: "nil" }
        val amounts = shown.stepAmounts?.let { a -> list(a.map(StepAmounts::marked)) } ?: "nil"
        val shortAmounts = shown.shortStepAmounts?.let { a -> list(a.map(StepAmounts::marked)) } ?: "nil"
        return m.groupValues[1] + "Render(${yield?.let(::q) ?: "nil"}, ${target ?: "nil"}, ${list(lines)}, ${list(steps)}$given, " +
            "$servings, ${list(shown.ingredients)}, ${list(shown.instructions)}, $timers, $amounts, " +
            "${optionalList(shown.shortInstructions)}, $shortAmounts),"
    }

    private fun optionalList(items: List<String?>) = items.joinToString(", ", "[", "]") { it?.let(::q) ?: "nil" }

    /** A Swift dictionary literal of strings, in the given order. */
    private fun pairs(items: List<Pair<String, String>>) = items.joinToString(", ", "[", "]") { (k, v) -> "${q(k)}: ${q(v)}" }

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
