package com.example.recipeclipper.data.model

import com.example.recipeclipper.data.remote.CardHeadings
import com.example.recipeclipper.data.remote.CheckReason
import com.example.recipeclipper.data.remote.JsonLdRecipeParser
import com.example.recipeclipper.data.remote.MealPlanImport
import com.example.recipeclipper.data.remote.PhotoBox
import com.example.recipeclipper.data.remote.PhotoLine
import com.example.recipeclipper.data.remote.PhotoTextSorter
import com.example.recipeclipper.data.remote.RecipeTextSplitter
import com.example.recipeclipper.data.remote.SharedText
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
 * `Fresh("line")` rows (#396) pin [Perishables.find]: an ingredient line as the perishables table reads
 * it (the recipe's language's, optionally `, lang: "de"`), as the ingredient's name, its group's key and its
 * keeps hint ("1-2"), each nil when the line isn't a perishable; write only the line.
 *
 * `Entry("text")` rows (#302) pin [PantryEntry.split]: what was typed into "Add to the pantry",
 * as the item's name and its quantity (nil: kept whole); write only the text (optionally
 * `, lang: "de"`).
 * `Need("quantity", "name", [lines])` rows (#302) pin What I need's amounts
 * ([PantryMatch.weekNeeds]): the lines, each its own recipe, against one in-stock pantry item
 * with that quantity and name, as the Have row's pantry amount and the week's total (nil: none);
 * write only those (optionally `, lang: "de"`).
 *
 * `UseIt("name", [lines])` rows (#305) pin "Use it up" ([UseItUp.groups]): the lines, each its
 * own saved recipe, against one in-stock pantry item of that name expiring today, as the indices
 * of the recipes that use it; write only those (optionally `, lang: "de"`).
 *
 * `Mins("time")` rows (#378) pin [Durations.minutes]: a stored prep, cook or total time as the
 * minutes it says, or nil (unreadable, zero or too large); write only the time (optionally
 * `, lang: "de"`). `Quick(total, prep, cook)` rows pin [RecipeQuickFilters] ("30 min or less"): a
 * recipe's total, prep and cook times (a string or nil each; optionally `, lang: "de"`), as the
 * minutes read ([RecipeQuickFilters.minutes]) and whether it is 30 min or less; write only the three times.
 * `Cover([pantry], [lines])` rows pin [RecipeQuickFilters.coverage] ("Uses what's in my pantry"):
 * pantry items by name ("*salt" a staple, "-butter" out of stock) against one recipe's lines
 * (optionally `, lang: "de"`), as how many nameable lines the pantry has, how many there are, and
 * whether that is half or more (nil, nil, false: the language has no tables); write only those two lists.
 *
 * `Tonight([types], [meals])` rows (#309) pin the Tonight widget ([WidgetContent.tonight]): meal
 * types written "id sortOrder name" and planned meals written "dayOffset typeId t|n text" (a
 * recipe's title or a note; today is offset 0), as each shown meal's type name, then its title;
 * write only those. `ToBuy([unticked], [ticked])` rows pin the grocery widget
 * ([WidgetContent.groceries]): a list holding those lines, as the lines left to buy (optionally
 * `, lang: "de"`). `Fitted(count, rows)` rows (#329) pin [WidgetContent.fit]: that many items in
 * a widget with room for that many rows, as how many are shown, how many more, and whether the
 * count sits beside the one item; write only the two numbers.
 *
 * `Paged(pageHeight, [M(space, [lines], keep), ...])` rows (#318) pin [RecipePrint.paginate]: blocks
 * measured for paper (each its space above, its lines' heights and whether it keeps with the
 * next), as the pieces placed, `P(block, page, first, end)`; write only the height and the blocks.
 *
 * `Ics("text")` rows (#52) pin [MealPlanIcs.contentLine]: the text as an .ics SUMMARY line,
 * escaped and folded at 75 octets; write only the text.
 *
 * `Img("address")` rows (#235) pin [WebImageUrl.of]: a photo's address as a page gives it, as
 * the app keeps it (`https`), or nil (no image); write only the address.
 *
 * `Parse("block")` rows (#266) pin [JsonLdRecipeParser.parse]'s text: one ld+json block as the
 * recipe's name (nil: no recipe), ingredients, steps and yield; write only the block.
 *
 * `Dur("time")` rows (#179) pin [Durations.format]: a prep, cook or total time
 * as a site (or the tour's sample) writes it, as the app shows it, or nil (hidden); write only
 * the time (optionally `, lang: "de"`; a language with no tables reads ISO alone).
 *
 * `Ahead([steps])` rows (#397) pin [AheadSteps.find]: a recipe's steps as the index of the step to
 * start ahead of time and its quoted sentence (nil, nil: none); write only the steps (optionally
 * `, lang: "de"`; a language with no tables finds nothing).
 *
 * `Step("step", [lines])` rows (#101) pin [StepAmounts.annotate]: the step with each amount in
 * ⟦ ⟧, once against the lines as given and once against them doubled in Metric; write only the
 * step and the lines (optionally `, lang: "fr"`).
 *
 * `Named("step", [lines])` rows (#286) pin [StepAmounts.named]: the lines the step names, as
 * indexes; write only the step and the lines (optionally `, lang: "fr"`).
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
 * `Verify("page", "name", [ingredients], [steps])` rows (#128) pin [PageRecipeCheck.verify]: the
 * ingredients and steps it keeps, one recipe's only (nil, nil: no recipe); write only those four.
 *
 * `Runaway("reply")` rows (#273) pin [RunawayReply]: what [RunawayReply.scan] reads of a model's
 * JSON reply, perhaps cut off (the name, ingredients, steps, and yield and times), and whether it
 * has run away; write only the reply.
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
 * `Shared("text")` rows (#306) pin [SharedText]: text shared into the app, as where it goes
 * ([SharedText.kind]), whether it sorted, its language, its name (#350), the editor's ingredients and steps, its
 * yield, prep, cook and total times, and the lines left over ([SharedText.read]); write only
 * the text.
 *
 * `PlanText("text")` rows (#352) pin [MealPlanImport.read]: shared text as a week of recipes, then
 * whether the answer was cut off, each `Meal("name", epochDay or nil, [ingredients], [steps])` in
 * order (and, when it named them, the `"meal"` and the link of a saved recipe, #392, #367), or `[]`
 * when it holds no plan; write only the text.
 * `CutOffText("text")` rows (#352) pin [SharedText.isCutOff] (an AI's answer that stopped inside
 * its recipe JSON) and whether [SharedText.read] read a whole recipe from it; write only the text.
 * `Based("text")` rows (#354) pin [SharedText.Reading.basedOn]: text shared into the app, then the
 * recipe its JSON's `isBasedOn` names (nil for none); write only the text.
 *
 * `Photo(["line", ...])` rows (#208) pin [PhotoTextSorter.sort]: a photo's lines as the language
 * they were read in, whether they sorted, the ingredients, steps, the lines to check one by one
 * ([PhotoReading.flags]: the line in the box, as listed, and why) and those listed only as large;
 * write only the lines. `Check("line")` rows (#283) pin [PhotoTextSorter.check], the per-line
 * check the sorter runs, on an ingredient line (optionally `, lang: "de"`). `PhotoAt([At("line",
 * 0, [left, top, right, bottom]), ...])` rows (#286) pin where each listed line was read: its
 * page and box, through split, repeated and unsorted lines; write only the lines (`nil` for no
 * page or box, and a confidence after the box if wanted).
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
    // A pantry-entry row (#302): the text typed into "Add to the pantry", optionally its language.
    private val entryRow = Regex("""^(\s*)Entry\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A perishables row (#396): an ingredient line, optionally its language.
    private val freshRow = Regex("""^(\s*)Fresh\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A What I need amounts row (#302): a pantry item's quantity and name, the week's lines, optionally their language.
    private val needRow = Regex(
        """^(\s*)Need\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?"""
    )
    // A Use it up row (#305): a pantry item's name, the saved recipes' lines, optionally their language.
    private val useItRow = Regex(
        """^(\s*)UseIt\("((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?"""
    )
    // A minutes row (#378): a stored time, optionally its language.
    private val minutesRow = Regex("""^(\s*)Mins\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A quick-filter row (#378): a recipe's total, prep and cook times (nil or a string each), optionally its language.
    private val quickRow = Regex(
        """^(\s*)Quick\((nil|"(?:[^"\\]|\\.)*"), (nil|"(?:[^"\\]|\\.)*"), (nil|"(?:[^"\\]|\\.)*")(?:, lang: "([a-z]+)")?"""
    )
    // A pantry-coverage row (#378): the pantry's names, one recipe's lines, optionally their language.
    private val coverRow = Regex(
        """^(\s*)Cover\(\[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*], \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?"""
    )
    // A Tonight widget row (#309): the meal types, then the planned meals.
    private val tonightRow =
        Regex("""^(\s*)Tonight\(\[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*], \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*]""")
    // A skip-tonight row (#398): the choice, the skipped meal, today and the last day, then the plan.
    private val skipRow = Regex("""^(\s*)Skip\("((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*]""")
    // A grocery widget row (#309): the list's unticked lines, its ticked ones, optionally their language.
    private val toBuyRow = Regex(
        """^(\s*)ToBuy\(\[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*], \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?"""
    )
    // A widget-fit row (#329): how many items, and how many rows there is room for.
    private val fittedRow = Regex("""^(\s*)Fitted\((\d+), (\d+)""")
    // A page-break row (#318): the page height, then each block as M(space, [line heights], keep).
    private val pagedRow = Regex("""^(\s*)Paged\((\d+(?:\.\d+)?), \[((?:\s*M\([^)]*\),?)*)\s*]""")
    private val measuredEntry = Regex("""M\((\d+(?:\.\d+)?), \[([^\]]*)], (true|false)\)""")
    // A calendar-file row (#52): one summary's text.
    // A step row (#101): one step, the ingredient lines, optionally their language.
    private val stepRow = Regex("""^(\s*)Step\("((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?""")
    // A step-lines row (#286): one step, the ingredient lines, optionally their language.
    private val namedRow = Regex("""^(\s*)Named\("((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?""")
    private val icsRow = Regex("""^(\s*)Ics\("((?:[^"\\]|\\.)*)"""")
    // A photo-address row (#235): an image's address as a page gives it.
    private val imageRow = Regex("""^(\s*)Img\("((?:[^"\\]|\\.)*)"""")
    // A JSON-LD text row (#266): one ld+json block.
    private val parseRow = Regex("""^(\s*)Parse\("((?:[^"\\]|\\.)*)"""")
    // A duration row (#179): a prep, cook or total time, optionally its language.
    private val durationRow = Regex("""^(\s*)Dur\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // An ahead-of-time row (#397): a recipe's steps, optionally their language.
    private val aheadRow = Regex("""^(\s*)Ahead\(\[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*](?:, lang: "([a-z]+)")?""")
    // A Chef mode row (#100): a step, a short version of it, optionally the recipe's ingredient
    // lines (#129) and their language.
    private val shortRow = Regex("""^(\s*)Short\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)"(?:, lines: \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*])?(?:, lang: "([a-z]+)")?""")
    // A page-pick row (#103): the kind, the page's text, what the model picked from it.
    private val pickRow = Regex("""^(\s*)Pick\(\.(name|ingredient|step|other), "((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)"""")
    // A whole-pick row (#128): the page's text, then the picked name, ingredients and steps.
    private val verifyRow =
        Regex("""^(\s*)Verify\("((?:[^"\\]|\\.)*)", "((?:[^"\\]|\\.)*)", \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*], \[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*]""")
    // A runaway row (#273): a model's JSON reply, perhaps cut off.
    private val runawayRow = Regex("""^(\s*)Runaway\("((?:[^"\\]|\\.)*)"""")
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
    // A shared-text row (#306): text shared into the app.
    private val sharedRow = Regex("""^(\s*)Shared\("((?:[^"\\]|\\.)*)"""")
    // A meal-plan row (#352): text shared in as a week of recipes.
    private val planTextRow = Regex("""^(\s*)PlanText\("((?:[^"\\]++|\\.)*+)"""")
    // A shared-text version row (#354): text shared into the app, then its JSON's isBasedOn.
    private val cutOffRow = Regex("""^(\s*)CutOffText\("((?:[^"\\]++|\\.)*+)"""")
    private val basedRow = Regex("""^(\s*)Based\("((?:[^"\\]|\\.)*)"""")
    // A photo row (#208): the lines read from a photo.
    private val photoRow = Regex("""^(\s*)Photo\(\[((?:\s*"(?:[^"\\]|\\.)*",?)*)\s*]""")
    // A placed-photo row (#286): the lines read, each with its page and box and optionally its
    // confidence, as `At("line", 0, [left, top, right, bottom])` (nil for no page or box).
    private val photoAtRow = Regex("""^(\s*)PhotoAt\(\[((?:\s*At\((?:[^()"]|"(?:[^"\\]|\\.)*")*\),?)*)\s*]""")
    private val atLine = Regex("""At\("((?:[^"\\]|\\.)*)", (nil|\d+), (nil|\[[^\]]*])(?:, ([\d.]+))?\)""")
    // A per-line check row (#198, #208, #283): a line read from a photo, optionally its language.
    private val checkRow = Regex("""^(\s*)Check\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
    // A fraction row (#245): a line read from a photo, optionally its language.
    private val fractionRow = Regex("""^(\s*)Frac\("((?:[^"\\]|\\.)*)"(?:, lang: "([a-z]+)")?""")
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
        assumeTrue("no iOS project beside core/", swiftFile.exists())
        val original = swiftFile.readText()
        val regenerated = original.lines().joinToString("\n") { regenerate(it) }
        outFile.parentFile?.mkdirs()
        outFile.writeText(regenerated)
        assertEquals(
            "The corpus is stale. Copy core/${outFile.path} over ios/RecipeClipperTests/Model/" +
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
        parseRow.find(line)?.let { m ->
            val block = unescape(m.groupValues[2])
            val r = JsonLdRecipeParser.parse(listOf(block), "https://src/parse")
            val parsed = r?.let { "${q(it.name)}, ${list(it.ingredients)}, ${list(it.instructions)}, ${it.yield?.let(::q) ?: "nil"}" }
                ?: "nil, [], [], nil"
            return m.groupValues[1] + "Parse(${q(block)}, $parsed),"
        }
        groceryRow.find(line)?.let { g -> return groceryRow(g) }
        pantryRow.find(line)?.let { p -> return pantryRow(p) }
        useUpRow.find(line)?.let { m -> return useUpRow(m) }
        entryRow.find(line)?.let { m -> return entryRow(m) }
        freshRow.find(line)?.let { m -> return freshRow(m) }
        needRow.find(line)?.let { m -> return needRow(m) }
        useItRow.find(line)?.let { m -> return useItRow(m) }
        minutesRow.find(line)?.let { m -> return minutesRow(m) }
        quickRow.find(line)?.let { m -> return quickRow(m) }
        coverRow.find(line)?.let { m -> return coverRow(m) }
        tonightRow.find(line)?.let { m -> return tonightRow(m) }
        skipRow.find(line)?.let { m -> return skipRow(m) }
        toBuyRow.find(line)?.let { m -> return toBuyRow(m) }
        fittedRow.find(line)?.let { m -> return fittedRow(m) }
        pagedRow.find(line)?.let { m -> return pagedRow(m) }
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
        runawayRow.find(line)?.let { r ->
            val reply = unescape(r.groupValues[2])
            val s = RunawayReply.scan(reply)
            val name = s.name?.let(::q) ?: "nil"
            val short = optionalList(listOf(s.yield, s.prepTime, s.cookTime, s.totalTime))
            return r.groupValues[1] +
                "Runaway(${q(reply)}, $name, ${list(s.ingredients)}, ${list(s.steps)}, $short, ${RunawayReply.isRunaway(reply)}),"
        }
        verifyRow.find(line)?.let { v ->
            val (page, name) = unescape(v.groupValues[2]) to unescape(v.groupValues[3])
            val (ingredients, steps) = listOf(v.groupValues[4], v.groupValues[5])
                .map { g -> literal.findAll(g).map { unescape(it.groupValues[1]) }.toList() }
            val kept = PageRecipeCheck.verify(page, PageSelection(name, ingredients, steps))
            val result = kept?.let { "${list(it.ingredients)}, ${list(it.steps)}" } ?: "nil, nil"
            return v.groupValues[1] + "Verify(${q(page)}, ${q(name)}, ${list(ingredients)}, ${list(steps)}, $result),"
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
        namedRow.find(line)?.let { m ->
            val step = unescape(m.groupValues[2])
            val lines = literal.findAll(m.groupValues[3]).map { unescape(it.groupValues[1]) }.toList()
            val language = m.groupValues[4].ifEmpty { null }
            val words = if (language == null) LanguageWords.ENGLISH else LanguageWords.forTag(language)!!
            val lang = if (language == null) "" else ", lang: ${q(language)}"
            return m.groupValues[1] + "Named(${q(step)}, ${list(lines)}$lang, ${StepAmounts.named(step, lines, words)}),"
        }
        durationRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            val language = m.groupValues[3].ifEmpty { "en" }
            val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(language)}"
            val shown = Durations.format(text, LanguageWords.forTag(language))?.let { q(it) } ?: "nil"
            return m.groupValues[1] + "Dur(${q(text)}$lang, $shown),"
        }
        aheadRow.find(line)?.let { m ->
            val steps = literal.findAll(m.groupValues[2]).map { unescape(it.groupValues[1]) }.toList()
            val language = m.groupValues[3].ifEmpty { "en" }
            val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(language)}"
            val found = AheadSteps.find(steps, LanguageWords.forTag(language))
            val shown = found?.let { "${it.step}, ${q(it.quote)}" } ?: "nil, nil"
            return m.groupValues[1] + "Ahead(${list(steps)}$lang, $shown),"
        }
        splitRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            val language = RecipeTextSplitter.languageOf(text)?.let { q(it) } ?: "nil"
            val split = RecipeTextSplitter.detectAndSplit(text)
            val (ingredients, steps) = split?.let { list(it.ingredients) to list(it.instructions) } ?: ("nil" to "nil")
            val extras = split?.let { optionalList(listOf(it.yield, it.prepTime, it.cookTime, it.totalTime)) } ?: "[]"
            return m.groupValues[1] + "Split(${q(text)}, $language, $ingredients, $steps, $extras),"
        }
        sharedRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            val reading = SharedText.read(text)
            val kind = "." + SharedText.kind(text).name.lowercase()
            val language = reading.recipe.language?.let { q(it) } ?: "nil"
            val r = reading.recipe
            val extras = optionalList(listOf(r.yield, r.prepTime, r.cookTime, r.totalTime))
            return m.groupValues[1] + "Shared(${q(text)}, $kind, ${reading.sorted}, $language, ${q(reading.name)}, " +
                "${list(r.ingredients)}, ${list(r.instructions)}, $extras, ${list(reading.leftovers)}),"
        }
        planTextRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            val plan = MealPlanImport.read(text)
            val meals = plan?.items.orEmpty().joinToString(", ", "[", "]") {
                // The meal it named and the link it named a saved recipe by (#392, #367): only when it has either.
                val named = if (it.meal != null || it.link != null) ", ${it.meal?.let(::q) ?: "nil"}, ${it.link?.let(::q) ?: "nil"}" else ""
                "Meal(${q(it.recipe.name)}, ${it.day?.toString() ?: "nil"}, ${list(it.recipe.ingredients)}, ${list(it.recipe.instructions)}$named)"
            }
            return m.groupValues[1] + "PlanText(${q(text)}, ${plan?.cutOff == true}, $meals),"
        }
        cutOffRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            return m.groupValues[1] + "CutOffText(${q(text)}, ${SharedText.isCutOff(text)}, ${SharedText.read(text).fromChat}),"
        }
        basedRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            return m.groupValues[1] + "Based(${q(text)}, ${SharedText.read(text).basedOn?.let(::q) ?: "nil"}),"
        }
        photoRow.find(line)?.let { m ->
            val lines = literal.findAll(m.groupValues[2]).map { unescape(it.groupValues[1]) }.toList()
            val reading = PhotoTextSorter.sort(lines.map { PhotoLine(it) })
            val language = reading.language?.let { q(it) } ?: "nil"
            val flags = reading.flags.joinToString(", ", "[", "]") {
                "LineFlag(raw: ${q(it.raw)}, shown: ${q(it.shown)}, reasons: ${reasons(it.reasons)})"
            }
            return m.groupValues[1] + "Photo(${list(lines)}, $language, ${reading.sorted}, " +
                "${list(reading.ingredients)}, ${list(reading.instructions)}, $flags, ${list(reading.large)}),"
        }
        photoAtRow.find(line)?.let { m ->
            val lines = atLine.findAll(m.groupValues[2]).map { a ->
                val box = a.groupValues[3].takeIf { it != "nil" }
                    ?.trim('[', ']')?.split(",")?.map { it.trim().toFloat() }
                    ?.let { (l, t, r, b) -> PhotoBox(l, t, r, b) }
                PhotoLine(unescape(a.groupValues[1]), a.groupValues[4].ifEmpty { null }?.toFloat(), a.groupValues[2].toIntOrNull(), box)
            }.toList()
            val at = lines.joinToString(", ", "[", "]") { l ->
                "At(${q(l.text)}, ${l.page ?: "nil"}, ${l.box?.let { "[${it.left}, ${it.top}, ${it.right}, ${it.bottom}]" } ?: "nil"}" +
                    "${l.confidence?.let { ", $it" } ?: ""})"
            }
            val flags = PhotoTextSorter.sort(lines).flags.joinToString(", ", "[", "]") {
                "LineFlag(raw: ${q(it.raw)}, shown: ${q(it.shown)}, reasons: ${reasons(it.reasons)}, page: ${it.page ?: "nil"}, " +
                    "box: ${it.box?.let { b -> "PhotoBox(left: ${b.left}, top: ${b.top}, right: ${b.right}, bottom: ${b.bottom})" } ?: "nil"})"
            }
            return m.groupValues[1] + "PhotoAt($at, $flags),"
        }
        checkRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            val language = m.groupValues[3].ifEmpty { "en" }
            val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(language)}"
            return m.groupValues[1] + "Check(${q(text)}$lang, ${reasons(PhotoTextSorter.check(text, LanguageWords.forTag(language)!!))}),"
        }
        fractionRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            val language = m.groupValues[3].ifEmpty { "en" }
            val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(language)}"
            val fixed = PhotoTextSorter.replaceFraction(text, "½", LanguageWords.forTag(language)!!)
            return m.groupValues[1] + "Frac(${q(text)}$lang, ${fixed?.let { q(it) } ?: "nil"}),"
        }
        icsRow.find(line)?.let { m ->
            val text = unescape(m.groupValues[2])
            return m.groupValues[1] + "Ics(${q(text)}, ${q(MealPlanIcs.contentLine("SUMMARY", text))}),"
        }
        imageRow.find(line)?.let { m ->
            val address = unescape(m.groupValues[2])
            return m.groupValues[1] + "Img(${q(address)}, ${WebImageUrl.of(address)?.let { q(it) } ?: "nil"}),"
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

    /** `Fresh(line, lang:, name, group, keeps)` (#396): [Perishables.find] of the line, each nil for none. */
    private fun freshRow(m: MatchResult): String {
        val text = unescape(m.groupValues[2])
        val language = m.groupValues[3].ifEmpty { "en" }
        val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(language)}"
        val found = Perishables.find(text, LanguageWords.forTag(language))
        fun opt(value: String?) = value?.let(::q) ?: "nil"
        return m.groupValues[1] + "Fresh(${q(text)}$lang, ${opt(found?.name)}, ${opt(found?.group?.key)}, ${opt(found?.group?.keeps)}),"
    }

    /** `Entry(text, lang:, name, quantity)`: [PantryEntry.split] of what was typed, nil for no quantity. */
    private fun entryRow(m: MatchResult): String {
        val text = unescape(m.groupValues[2])
        val language = m.groupValues[3].ifEmpty { "en" }
        val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(language)}"
        val entry = PantryEntry.split(text, LanguageWords.forTag(language))
        return m.groupValues[1] + "Entry(${q(text)}$lang, ${q(entry.name)}, ${entry.quantity?.let(::q) ?: "nil"}),"
    }

    /**
     * `Need(quantity, name, [lines], lang:, have, total)` (#302): [PantryMatch.weekNeeds] for the
     * lines, each its own recipe, against one in-stock item: the first Have row's pantry amount
     * and the week's total, each nil when there's none.
     */
    private fun needRow(m: MatchResult): String {
        val quantity = unescape(m.groupValues[2])
        val name = unescape(m.groupValues[3])
        val lines = literal.findAll(m.groupValues[4]).map { unescape(it.groupValues[1]) }.toList()
        val language = m.groupValues[5].ifEmpty { "en" }
        val lang = if (m.groupValues[5].isEmpty()) "" else ", lang: ${q(language)}"
        val item = PantryItem(1, name, quantity, language, Aisle.OTHER, inStock = true, alwaysHave = false, purchasedDay = null, expiresDay = null)
        val sources = lines.mapIndexed { i, line -> GrocerySource("s$i", i.toLong(), "r$i", null, language, listOf(line)) }
        val row = PantryMatch.weekNeeds(sources, listOf(item)).have.firstOrNull()
        val have = row?.pantryQuantity?.let { q(it) } ?: "nil"
        val total = row?.weekTotal?.let { q(it) } ?: "nil"
        return m.groupValues[1] + "Need(${q(quantity)}, ${q(name)}, ${list(lines)}$lang, $have, $total),"
    }

    /**
     * `UseIt(name, [lines], lang:, [used])` (#305): [UseItUp.groups] for the lines, each its own
     * saved recipe, against one in-stock item expiring today: the indices of the recipes using it.
     */
    private fun useItRow(m: MatchResult): String {
        val name = unescape(m.groupValues[2])
        val lines = literal.findAll(m.groupValues[3]).map { unescape(it.groupValues[1]) }.toList()
        val language = m.groupValues[4].ifEmpty { "en" }
        val lang = if (m.groupValues[4].isEmpty()) "" else ", lang: ${q(language)}"
        val item = PantryItem(1, name, null, language, Aisle.OTHER, inStock = true, alwaysHave = false, purchasedDay = null, expiresDay = 0)
        val saved = lines.mapIndexed { i, line -> SavedRecipe(i.toLong(), "r$i", language, listOf(line)) }
        val used = UseItUp.groups(listOf(item), saved, 0).firstOrNull()?.recipes.orEmpty().map { it.id }.sorted()
        return m.groupValues[1] + "UseIt(${q(name)}, ${list(lines)}$lang, ${used.joinToString(", ", "[", "]")}),"
    }

    /** `Mins(time, lang:, minutes)` (#378): [Durations.minutes], nil when the time can't be read. */
    private fun minutesRow(m: MatchResult): String {
        val text = unescape(m.groupValues[2])
        val language = m.groupValues[3].ifEmpty { "en" }
        val lang = if (m.groupValues[3].isEmpty()) "" else ", lang: ${q(language)}"
        val minutes = Durations.minutes(text, LanguageWords.forTag(language))?.toString() ?: "nil"
        return m.groupValues[1] + "Mins(${q(text)}$lang, $minutes),"
    }

    /** `Quick(total, prep, cook, lang:, minutes, thirtyOrLess)` (#378): [RecipeQuickFilters] on a recipe with those times. */
    private fun quickRow(m: MatchResult): String {
        val (total, prep, cook) = (2..4).map { g -> optionalLiteral.find(m.groupValues[g])!!.groups[1]?.value?.let(::unescape) }
        val language = m.groupValues[5].ifEmpty { "en" }
        val lang = if (m.groupValues[5].isEmpty()) "" else ", lang: ${q(language)}"
        val recipe = RecipeSummary(1, "r", null, total, 0, false, prepTime = prep, cookTime = cook, language = language)
        val minutes = RecipeQuickFilters.minutes(recipe)?.toString() ?: "nil"
        fun lit(v: String?) = v?.let(::q) ?: "nil"
        return m.groupValues[1] + "Quick(${lit(total)}, ${lit(prep)}, ${lit(cook)}$lang, $minutes, ${RecipeQuickFilters.thirtyOrLess(recipe)}),"
    }

    /**
     * `Cover([pantry], [lines], lang:, have, total, matches)` (#378): [RecipeQuickFilters.coverage] of one
     * recipe's lines by those pantry items ("*name" a staple, "-name" out of stock, in the lines' language).
     */
    private fun coverRow(m: MatchResult): String {
        val (names, lines) = literals(m.groupValues[2]) to literals(m.groupValues[3])
        val language = m.groupValues[4].ifEmpty { "en" }
        val lang = if (m.groupValues[4].isEmpty()) "" else ", lang: ${q(language)}"
        val pantry = names.mapIndexed { i, n ->
            PantryItem(
                i + 1L, n.trimStart('*', '-'), null, language, Aisle.OTHER, inStock = !n.startsWith("-"),
                alwaysHave = n.startsWith("*"), purchasedDay = null, expiresDay = null
            )
        }
        val coverage = RecipeQuickFilters.coverage(SavedRecipe(1, "r", language, lines), pantry)
        val result = coverage?.let { "${it.have}, ${it.total}, ${it.matches}" } ?: "nil, nil, false"
        return m.groupValues[1] + "Cover(${list(names)}, ${list(lines)}$lang, $result),"
    }

    private fun literals(group: String) = literal.findAll(group).map { unescape(it.groupValues[1]) }.toList()

    /**
     * `Tonight([types], [meals], [type, title, …])` (#309): [WidgetContent.tonight] for meal
     * types written "id sortOrder name" and meals written "dayOffset typeId t|n text" (a recipe's
     * title or a note), today being day 100: each shown meal's type name, then its title.
     */
    private fun tonightRow(m: MatchResult): String {
        val (types, meals) = literals(m.groupValues[2]) to literals(m.groupValues[3])
        val mealTypes = types.map { it.split(" ", limit = 3) }
            .map { (id, order, name) -> MealType(id.toLong(), name, null, order.toInt()) }
        val planned = meals.mapIndexed { i, meal ->
            val (day, type, kind, text) = meal.split(" ", limit = 4)
            PlannedMeal(
                id = i + 1L, day = 100 + day.toLong(), mealTypeId = type.toLong(),
                recipeId = if (kind == "t") i + 1L else null, title = text.takeIf { kind == "t" },
                imageUrl = null, servings = null, note = text.takeIf { kind == "n" }
            )
        }
        val shown = WidgetContent.tonight(planned, mealTypes, 100).flatMap { listOf(it.mealType, it.title) }
        return m.groupValues[1] + "Tonight(${list(types)}, ${list(meals)}, ${list(shown)}),"
    }

    /**
     * `Skip("choice id today lastDay", ["id day typeId [n]"], [result])` (#398): [PlanShift.plan]
     * for a plan written "id day typeId" (then `n` for a note), in plan order, as each move
     * `m id from to`, each removal `r id`, each meal past the last day `e id`, or `-` for no plan.
     */
    private fun skipRow(m: MatchResult): String {
        val header = unescape(m.groupValues[2]).split(" ")
        val meals = literals(m.groupValues[3])
        val planned = meals.map { text ->
            val part = text.split(" ")
            PlannedMeal(
                id = part[0].toLong(), day = part[1].toLong(), mealTypeId = part[2].toLong(),
                recipeId = if (part.size > 3) null else part[0].toLong(),
                title = if (part.size > 3) null else "t${part[0]}",
                imageUrl = null, servings = null, note = if (part.size > 3) "note" else null
            )
        }
        val choice = when (header[0]) {
            "push" -> PlanShift.Choice.PUSH_WEEK
            "next" -> PlanShift.Choice.NEXT_FREE_DAY
            else -> PlanShift.Choice.REMOVE
        }
        val result = PlanShift.plan(planned, header[1].toLong(), choice, header[2].toLong(), header[3].toLong())
        val shown = if (result == null) {
            listOf("-")
        } else {
            result.moved.map { "m ${it.mealId} ${it.fromDay} ${it.toDay}" } +
                result.removed.map { "r $it" } + result.pastEnd.map { "e $it" }
        }
        return m.groupValues[1] + "Skip(${q(unescape(m.groupValues[2]))}, ${list(meals)}, ${list(shown)}),"
    }

    /**
     * `ToBuy([unticked], [ticked], lang:, [lines])` (#309): [WidgetContent.groceries] for a list
     * holding the unticked lines, then the ticked ones: the lines the widget lists.
     */
    private fun toBuyRow(m: MatchResult): String {
        val (open, ticked) = literals(m.groupValues[2]) to literals(m.groupValues[3])
        val language = m.groupValues[4].ifEmpty { "en" }
        val lang = if (m.groupValues[4].isEmpty()) "" else ", lang: ${q(language)}"
        val words = LanguageWords.forTag(language)!!
        val items = (open.map { it to false } + ticked.map { it to true }).mapIndexed { i, (text, checked) ->
            GroceryItem(i + 1L, text, language, Aisles.of(text, words), checked, i + 1)
        }
        val lines = WidgetContent.groceries(GroceryCombiner.sections(items))
        return m.groupValues[1] + "ToBuy(${list(open)}, ${list(ticked)}$lang, ${list(lines)}),"
    }

    /** `Fitted(count, rows, shown, more, inline)` (#329): [WidgetContent.fit]. */
    private fun fittedRow(m: MatchResult): String {
        val (count, rows) = m.groupValues[2].toInt() to m.groupValues[3].toInt()
        val fit = WidgetContent.fit(count, rows)
        return m.groupValues[1] + "Fitted($count, $rows, ${fit.shown}, ${fit.more}, ${fit.inline}),"
    }

    /** `Paged(pageHeight, [M(space, [lines], keep)], [P(block, page, first, end)])` (#318): [RecipePrint.paginate]. */
    private fun pagedRow(m: MatchResult): String {
        val blocks = measuredEntry.findAll(m.groupValues[3]).map { e ->
            val lines = e.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toDouble() }
            RecipePrint.Measured(e.groupValues[1].toDouble(), lines, e.groupValues[3].toBoolean())
        }.toList()
        val pieces = RecipePrint.paginate(blocks, m.groupValues[2].toDouble())
        val placed = pieces.joinToString(", ", "[", "]") { "P(${it.block}, ${it.page}, ${it.first}, ${it.end})" }
        return m.groupValues[1] + "Paged(${m.groupValues[2]}, [${m.groupValues[3].trim()}], $placed),"
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

    /** A Swift set literal of `CheckReason`s, in their declared order: `[.misread, .fraction]`. */
    private fun reasons(items: Set<CheckReason>) =
        items.sortedBy { it.ordinal }.joinToString(", ", "[", "]") { "." + it.name.lowercase() }

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
