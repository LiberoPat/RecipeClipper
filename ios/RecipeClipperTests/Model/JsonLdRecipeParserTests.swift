import XCTest
@testable import RecipeClipper

/// Port of Android's JsonLdRecipeParserTest, plus the parts that were Jsoup's job on
/// Android and are this parser's on iOS (block extraction, HTML stripping), numeric yields,
/// HowToSection nesting, @graph lookup and duration formatting.
final class JsonLdRecipeParserTests: XCTestCase {

    private let sourceUrl = "https://example.com/recipe"

    // MARK: - Ported from Android

    func testEntitiesAreUnescapedTagsStrippedBlankLinesDroppedBrSplitStepsStaySeparate() throws {
        let block = #"""
        {
          "@context": "https://schema.org",
          "@type": "Recipe",
          "name": "Mix &amp; Match <b>Pie</b>",
          "recipeIngredient": ["1 cup sugar &amp; spice", "<i>2 eggs</i>", "<b></b>"],
          "recipeInstructions": "<p>Mix flour &amp; sugar.</p>\n<br>\n<p>Bake for 10 minutes.</p>"
        }
        """#

        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))

        XCTAssertEqual("Mix & Match Pie", recipe.name)
        // The tag-only ingredient strips to an empty string and is dropped.
        XCTAssertEqual(["1 cup sugar & spice", "2 eggs"], recipe.ingredients)
        // Splitting happens on the raw newlines first, so the two <p> steps (with a blank
        // <br>-only line between them) stay separate instead of collapsing into one line.
        XCTAssertEqual(["Mix flour & sugar.", "Bake for 10 minutes."], recipe.instructions)
    }

    func testDeeplyNestedJsonReturnsNilRatherThanCrashingAndANormalBlockAfterItStillParses() throws {
        let deep = String(repeating: #"{"a":"#, count: 20000) + "1" + String(repeating: "}", count: 20000)
        let normal = #"""
        {
          "@type": "Recipe",
          "name": "Simple Recipe",
          "recipeIngredient": ["1 egg"],
          "recipeInstructions": ["Boil it."]
        }
        """#

        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([deep, normal], sourceUrl: sourceUrl))
        XCTAssertEqual("Simple Recipe", recipe.name)
    }

    func testDeeplyNestedJsonAloneReturnsNil() {
        let deep = String(repeating: #"{"a":"#, count: 20000) + "1" + String(repeating: "}", count: 20000)
        XCTAssertNil(JsonLdRecipeParser.parse([deep], sourceUrl: sourceUrl))
    }

    // MARK: - Recipe lookup and fields

    func testARecipeInsideGraphIsFound() throws {
        let block = #"""
        {
          "@context": "https://schema.org",
          "@graph": [
            {"@type": "WebSite", "name": "Example Kitchen"},
            {"@type": "BreadcrumbList", "itemListElement": []},
            {"@type": ["Recipe", "NewsArticle"], "name": "Adobo",
             "recipeIngredient": ["2 lb chicken"], "recipeInstructions": ["Braise."]}
          ]
        }
        """#
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
        XCTAssertEqual("Adobo", recipe.name)
        XCTAssertEqual(["2 lb chicken"], recipe.ingredients)
        XCTAssertEqual(sourceUrl, recipe.sourceUrl)
    }

    func testATopLevelArrayAndANestedObjectAreSearched() throws {
        let block = #"[{"@type": "Organization", "publisher": {"@type": "recipe", "name": "Nested", "recipeIngredient": "1 egg", "recipeInstructions": "Boil."}}]"#
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
        XCTAssertEqual("Nested", recipe.name)
        XCTAssertEqual(["1 egg"], recipe.ingredients)
        XCTAssertEqual(["Boil."], recipe.instructions)
    }

    func testHowToSectionsAreFlattenedInOrder() throws {
        let block = #"""
        {
          "@type": "Recipe", "name": "Layered",
          "recipeIngredient": ["1 cup flour"],
          "recipeInstructions": [
            {"@type": "HowToSection", "name": "Crust", "itemListElement": [
              {"@type": "HowToStep", "text": "Mix the crust."},
              {"@type": "HowToStep", "text": "", "name": "Chill it."}
            ]},
            {"@type": "howtosection", "name": "Filling", "itemListElement": [
              "Whisk the eggs &amp; sugar.",
              {"@type": "HowToSection", "itemListElement": [{"@type": "HowToStep", "text": "<p>Pour.</p>"}]}
            ]},
            {"@type": "HowToStep", "text": "Bake."}
          ]
        }
        """#
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
        XCTAssertEqual(["Mix the crust.", "Chill it.", "Whisk the eggs & sugar.", "Pour.", "Bake."], recipe.instructions)
    }

    func testANumericYieldReadsAsItsDigits() throws {
        let block = #"{"@type": "Recipe", "name": "N", "recipeIngredient": ["x"], "recipeYield": 6}"#
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
        XCTAssertEqual("6", recipe.yield)
        XCTAssertEqual(6, Servings.bareCount(recipe.yield!))
    }

    func testAYieldArrayPrefersTheRangeAndAnObjectYieldReadsItsValue() throws {
        let array = #"{"@type": "Recipe", "name": "N", "recipeIngredient": ["x"], "recipeYield": [4, "4 to 6 servings"]}"#
        XCTAssertEqual("4 to 6 servings", JsonLdRecipeParser.parse([array], sourceUrl: sourceUrl)?.yield)

        let object = #"{"@type": "Recipe", "name": "N", "recipeIngredient": ["x"], "recipeYield": {"value": "8 slices"}}"#
        XCTAssertEqual("8 slices", JsonLdRecipeParser.parse([object], sourceUrl: sourceUrl)?.yield)
    }

    func testANullYieldIsNoYield() throws {
        let block = #"{"@type": "Recipe", "name": "N", "recipeIngredient": ["x"], "recipeYield": null, "prepTime": null}"#
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
        XCTAssertNil(recipe.yield)
        XCTAssertNil(recipe.prepTime)
    }

    func testImagesTimesAndTheIngredientsFallback() throws {
        let block = #"""
        {"@type": "Recipe", "name": "T", "ingredients": ["1 egg", 3, ""],
         "image": [{"@type": "ImageObject", "url": "https://example.com/a.jpg"}, "https://example.com/b.jpg"],
         "prepTime": "PT15M", "cookTime": "PT1H30M", "totalTime": "P1DT2H"}
        """#
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
        XCTAssertEqual(["1 egg"], recipe.ingredients)
        XCTAssertEqual("https://example.com/a.jpg", recipe.image)
        XCTAssertEqual("15m", recipe.prepTime)
        XCTAssertEqual("1h 30m", recipe.cookTime)
        XCTAssertEqual("26h", recipe.totalTime)
    }

    func testARecipeWithNoNameOrNoContentIsSkippedForTheNextBlock() throws {
        let noName = #"{"@type": "Recipe", "name": "  ", "recipeIngredient": ["x"]}"#
        let noContent = #"{"@type": "Recipe", "name": "Empty"}"#
        let good = #"{"@type": "Recipe", "name": "Good", "recipeInstructions": "Stir."}"#
        XCTAssertNil(JsonLdRecipeParser.parse([noName, noContent], sourceUrl: sourceUrl))
        XCTAssertEqual("Good", JsonLdRecipeParser.parse([noName, noContent, "not json", good], sourceUrl: sourceUrl)?.name)
    }

    func testLenientJsonThatAndroidsParserAccepts() throws {
        // A CDATA comment wrapper, a raw newline inside a string, and trailing junk.
        let block = "//<![CDATA[\n{\"@type\": \"Recipe\", \"name\": \"Line\none\", \"recipeIngredient\": [\"x\"]};\n//]]>"
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
        XCTAssertEqual("Line one", recipe.name)
    }

    func testNestingPastTheSearchDepthCapIsNotFollowed() {
        let wrap = 60
        let inner = #"{"@type": "Recipe", "name": "Deep", "recipeIngredient": ["x"]}"#
        let block = String(repeating: "[", count: wrap) + inner + String(repeating: "]", count: wrap)
        XCTAssertNil(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
        let shallow = String(repeating: "[", count: 10) + inner + String(repeating: "]", count: 10)
        XCTAssertEqual("Deep", JsonLdRecipeParser.parse([shallow], sourceUrl: sourceUrl)?.name)
    }

    // MARK: - Durations

    func testDurationFormatting() {
        XCTAssertEqual("1h 30m", JsonLdRecipeParser.formatDuration("PT1H30M"))
        XCTAssertEqual("45m", JsonLdRecipeParser.formatDuration("PT45M"))
        XCTAssertEqual("2h", JsonLdRecipeParser.formatDuration("pt2h"))
        XCTAssertEqual("2m", JsonLdRecipeParser.formatDuration("PT90S"))   // 1.5 min rounds half up
        XCTAssertEqual("25h", JsonLdRecipeParser.formatDuration("P1DT1H"))
        XCTAssertEqual("P", JsonLdRecipeParser.formatDuration("P"))        // no fields at all
        XCTAssertNil(JsonLdRecipeParser.formatDuration("   "))
    }

    func testAnIsoDurationTotallingZeroIsNilSoTheLabelIsHidden() {
        // Delish publishes "cookTime": "PT0S"; it used to show as "COOK PT0S".
        XCTAssertNil(JsonLdRecipeParser.formatDuration("PT0S"))
        XCTAssertNil(JsonLdRecipeParser.formatDuration("P0D"))
        XCTAssertNil(JsonLdRecipeParser.formatDuration("PT0M"))
        XCTAssertNil(JsonLdRecipeParser.formatDuration("PT0H0M"))
    }

    func testEnglishDurationPhrasesAreRenderedLikeIsoOnes() {
        // Condé Nast sites (Bon Appétit, Epicurious) publish these instead of ISO.
        XCTAssertEqual("20m", JsonLdRecipeParser.formatDuration("20 minutes"))
        XCTAssertEqual("1h", JsonLdRecipeParser.formatDuration("1 hour"))
        XCTAssertEqual("1h 30m", JsonLdRecipeParser.formatDuration("1 hour 30 minutes"))
        XCTAssertEqual("1h 5m", JsonLdRecipeParser.formatDuration("1 hr 5 mins"))
        XCTAssertEqual("2h 15m", JsonLdRecipeParser.formatDuration("2 Hours and 15 Minutes"))
        XCTAssertEqual("1h 30m", JsonLdRecipeParser.formatDuration("1 hour, 30 minutes"))
        XCTAssertEqual("1h 30m", JsonLdRecipeParser.formatDuration("90 min"))
        XCTAssertEqual("1h 30m", JsonLdRecipeParser.formatDuration("1h30m"))
        XCTAssertEqual("20m", JsonLdRecipeParser.formatDuration("  20 mins  "))
        XCTAssertNil(JsonLdRecipeParser.formatDuration("0 minutes"))
    }

    func testAnythingThatIsNotAPlainDurationPhraseStaysExactlyAsWritten() {
        XCTAssertEqual("Overnight", JsonLdRecipeParser.formatDuration("Overnight"))
        XCTAssertEqual("20 to 25 minutes", JsonLdRecipeParser.formatDuration("20 to 25 minutes"))
        XCTAssertEqual("1-2 hours", JsonLdRecipeParser.formatDuration("1-2 hours"))
        XCTAssertEqual("1.5 hours", JsonLdRecipeParser.formatDuration("1.5 hours"))
        XCTAssertEqual("about 20 minutes", JsonLdRecipeParser.formatDuration("about 20 minutes"))
        XCTAssertEqual("1 hour and", JsonLdRecipeParser.formatDuration("1 hour and"))
        XCTAssertEqual("30 minutes 1 hour", JsonLdRecipeParser.formatDuration("30 minutes 1 hour"))
        XCTAssertEqual("garbage", JsonLdRecipeParser.formatDuration("garbage"))
    }

    func testTimesInARecipeBlockGoThroughTheSameFormatting() throws {
        let block = #"""
        {"@type": "Recipe", "name": "Times", "recipeIngredient": ["a"],
         "prepTime": "20 minutes", "cookTime": "PT0S", "totalTime": "Overnight"}
        """#
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
        XCTAssertEqual("20m", recipe.prepTime)
        XCTAssertNil(recipe.cookTime)
        XCTAssertEqual("Overnight", recipe.totalTime)
    }

    // MARK: - HowToSections: condensed duplicate sections

    func testAWprmAbbreviatedRecipeSectionIsSkippedWhenRealSectionsFollow() throws {
        // Shape of RecipeTin Eats' WP Recipe Maker output.
        let block = #"""
        {"@context": "https://schema.org", "@graph": [
          {"@type": "Article", "@id": "https://www.recipetineats.com/x/#article", "headline": "Beef Ragu"},
          {"@type": "WebPage", "@id": "https://www.recipetineats.com/x/"},
          {"@type": "Recipe", "name": "Beef Ragu", "recipeYield": ["6", "6 people"],
           "prepTime": "PT15M", "cookTime": "PT3H", "totalTime": "PT3H15M",
           "recipeIngredient": ["1 kg beef chuck", "800 g crushed tomato"],
           "recipeInstructions": [
             {"@type": "HowToSection", "name": "Abbreviated Recipe", "itemListElement": [
               {"@type": "HowToStep", "text": "Brown beef, saut&eacute; onion, add tomato and wine, simmer 3 hours, shred and toss with pasta.", "name": "Brown beef", "url": "https://www.recipetineats.com/x/#wprm-recipe-1-step-0-0"}
             ]},
             {"@type": "HowToSection", "name": "Ragu", "itemListElement": [
               {"@type": "HowToStep", "text": "Brown the beef in batches.", "url": "https://www.recipetineats.com/x/#wprm-recipe-1-step-1-0"},
               {"@type": "HowToStep", "text": "Simmer for 3 hours."}
             ]},
             {"@type": "HowToSection", "name": "To serve", "itemListElement": [
               {"@type": "HowToStep", "text": "Toss with pasta &amp; serve."}
             ]}
           ]}
        ]}
        """#
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
        XCTAssertEqual(
            ["Brown the beef in batches.", "Simmer for 3 hours.", "Toss with pasta & serve."],
            recipe.instructions
        )
    }

    func testCondensedSectionNamesMatchAfterStrippingTrimmingAndIgnoringCase() throws {
        for name in [" <b>SUMMARY</b> ", "Quick Version", "short version", "TL;DR",
                     "Recipe summary", "At a glance", "abbreviated recipe"] {
            let block = """
            {"@type": "Recipe", "name": "R", "recipeInstructions": [
              {"@type": "HowToSection", "name": "\(name)", "itemListElement": [{"@type": "HowToStep", "text": "All of it at once."}]},
              {"@type": "HowToSection", "name": "Method", "itemListElement": [{"@type": "HowToStep", "text": "Real step."}]}
            ]}
            """
            let recipe = try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl))
            XCTAssertEqual(["Real step."], recipe.instructions, name)
        }
    }

    func testALoneCondensedSectionIsNotSkipped() throws {
        let block = #"""
        {"@type": "Recipe", "name": "R", "recipeInstructions": [
          {"@type": "HowToSection", "name": "Abbreviated Recipe", "itemListElement": [{"@type": "HowToStep", "text": "Only step."}]}
        ]}
        """#
        XCTAssertEqual(["Only step."], try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl)).instructions)
    }

    func testACondensedSectionIsKeptWhenNoOtherSectionHasSteps() throws {
        let block = #"""
        {"@type": "Recipe", "name": "R", "recipeInstructions": [
          {"@type": "HowToSection", "name": "Summary", "itemListElement": [{"@type": "HowToStep", "text": "Only step."}]},
          {"@type": "HowToSection", "name": "Method", "itemListElement": []}
        ]}
        """#
        XCTAssertEqual(["Only step."], try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl)).instructions)
    }

    func testSectionsWithOrdinaryNamesAreAllKeptAndANameThatOnlyContainsSummaryIsNotSkipped() throws {
        let block = #"""
        {"@type": "Recipe", "name": "R", "recipeInstructions": [
          {"@type": "HowToSection", "name": "Summary of the sauce", "itemListElement": [{"@type": "HowToStep", "text": "Sauce."}]},
          {"@type": "HowToSection", "name": "Crust", "itemListElement": [{"@type": "HowToStep", "text": "Crust."}]}
        ]}
        """#
        XCTAssertEqual(["Sauce.", "Crust."], try XCTUnwrap(JsonLdRecipeParser.parse([block], sourceUrl: sourceUrl)).instructions)
    }

    // MARK: - extractJsonLdBlocks

    func testBlocksAreExtractedInDocumentOrderWhateverTheAttributeSpelling() {
        let html = """
        <html><head>
        <script type="application/ld+json">{"a":1}</script>
        <script src="x.js"></script>
        <SCRIPT id='x' TYPE='Application/LD+JSON' class="c">{"b":2}</SCRIPT >
        <script data-x="a>b" type=application/ld+json>{"c":3}</script>
        <script type="text/javascript">var s = '<script type="application/ld+json">';</script>
        <!-- <script type="application/ld+json">{"commented":true}</script> -->
        <script type=" application/ld+json ">{"d":4}</script>
        </head></html>
        """
        XCTAssertEqual(
            [#"{"a":1}"#, #"{"b":2}"#, #"{"c":3}"#, #"{"d":4}"#],
            JsonLdRecipeParser.extractJsonLdBlocks(fromHtml: html)
        )
    }

    func testAPageWithoutLdJsonGivesNoBlocks() {
        XCTAssertEqual([], JsonLdRecipeParser.extractJsonLdBlocks(fromHtml: "<p>Just a story.</p>"))
        XCTAssertEqual([], JsonLdRecipeParser.extractJsonLdBlocks(fromHtml: ""))
    }

    func testExtractedBlocksFeedTheParser() throws {
        let html = """
        <script type="application/ld+json">{"@type":"WebPage"}</script>
        <script type="application/ld+json">
        {"@type":"Recipe","name":"From Page","recipeIngredient":["1 &amp; 2"],"recipeInstructions":"Go."}
        </script>
        """
        let blocks = JsonLdRecipeParser.extractJsonLdBlocks(fromHtml: html)
        XCTAssertEqual(2, blocks.count)
        let recipe = try XCTUnwrap(JsonLdRecipeParser.parse(blocks, sourceUrl: sourceUrl))
        XCTAssertEqual("From Page", recipe.name)
        XCTAssertEqual(["1 & 2"], recipe.ingredients)
    }

    // MARK: - stripHtml (Jsoup's parse(raw).text())

    func testStripHtmlDecodesEntities() {
        XCTAssertEqual("Mom's \"best\" <pie> & more", JsonLdRecipeParser.stripHtml("Mom&#39;s &quot;best&quot; &lt;pie&gt; &amp; more"))
        XCTAssertEqual("It’s 350°F – ½ cup … “ok” — é", JsonLdRecipeParser.stripHtml("It&rsquo;s 350&deg;F &ndash; &frac12; cup &hellip; &ldquo;ok&rdquo; &mdash; &eacute;"))
        XCTAssertEqual("A ' ' ’", JsonLdRecipeParser.stripHtml("A &#x27; &#39 &#146;"))
        XCTAssertEqual("¼ ¾ '", JsonLdRecipeParser.stripHtml("&frac14; &frac34; &apos;"))
    }

    func testStripHtmlLegacyEntitiesWorkWithoutASemicolonOthersDoNot() {
        XCTAssertEqual("salt & pepper", JsonLdRecipeParser.stripHtml("salt &amp pepper"))
        XCTAssertEqual("&unknown; & &hellip", JsonLdRecipeParser.stripHtml("&unknown; & &hellip"))
    }

    func testStripHtmlTurnsBlockTagsIntoSpacesButNotInlineTags() {
        XCTAssertEqual("Mix well. Then bake.", JsonLdRecipeParser.stripHtml("<p>Mix well.</p><p>Then bake.</p>"))
        XCTAssertEqual("one two", JsonLdRecipeParser.stripHtml("one<br/>two"))
        XCTAssertEqual("one two", JsonLdRecipeParser.stripHtml("one<BR>two"))
        XCTAssertEqual("bold", JsonLdRecipeParser.stripHtml("<b>bo</b>ld"))
        XCTAssertEqual("a b", JsonLdRecipeParser.stripHtml("<ul><li>a</li><li>b</li></ul>"))
        XCTAssertEqual("link", JsonLdRecipeParser.stripHtml(#"<a href="x" title='a > b'>link</a>"#))
    }

    func testStripHtmlDropsCommentsAndScriptContents() {
        XCTAssertEqual("a b", JsonLdRecipeParser.stripHtml("a <!-- hidden --> b"))
        XCTAssertEqual("a b", JsonLdRecipeParser.stripHtml("a <script>var x = '<p>';</script> b"))
        XCTAssertEqual("a b", JsonLdRecipeParser.stripHtml("a <style>p { x: 1 }</style> b"))
    }

    func testStripHtmlNormalisesWhitespace() {
        XCTAssertEqual("a b c", JsonLdRecipeParser.stripHtml("  a \n\t b&nbsp;&nbsp;c  "))
        XCTAssertEqual("a b", JsonLdRecipeParser.stripHtml("a\u{00A0}b"))
        XCTAssertEqual("ab", JsonLdRecipeParser.stripHtml("a\u{200B}b"))
        XCTAssertEqual("", JsonLdRecipeParser.stripHtml("<b></b>"))
        XCTAssertEqual("", JsonLdRecipeParser.stripHtml(""))
    }

    func testStripHtmlLeavesALessThanThatIsNotATagAlone() {
        XCTAssertEqual("1 < 2 and <3", JsonLdRecipeParser.stripHtml("1 < 2 and <3"))
    }
}
