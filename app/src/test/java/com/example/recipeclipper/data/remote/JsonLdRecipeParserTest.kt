package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the pure JSON-LD parser: string in, [com.example.recipeclipper.data.model.Recipe] out. */
class JsonLdRecipeParserTest {

    private val sourceUrl = "https://example.com/recipe"

    @Test
    fun `entities are unescaped, tags stripped, blank lines dropped, br-split steps stay separate`() {
        val block = """
            {
              "@context": "https://schema.org",
              "@type": "Recipe",
              "name": "Mix &amp; Match <b>Pie</b>",
              "recipeIngredient": ["1 cup sugar &amp; spice", "<i>2 eggs</i>", "<b></b>"],
              "recipeInstructions": "<p>Mix flour &amp; sugar.</p>\n<br>\n<p>Bake for 10 minutes.</p>"
            }
        """.trimIndent()

        val recipe = JsonLdRecipeParser.parse(listOf(block), sourceUrl)

        assertNotNull(recipe)
        assertEquals("Mix & Match Pie", recipe!!.name)
        // The tag-only ingredient strips to an empty string and is dropped.
        assertEquals(listOf("1 cup sugar & spice", "2 eggs"), recipe.ingredients)
        // Splitting happens on the raw newlines first, so the two <p> steps (with a blank
        // <br>-only line between them) stay separate instead of collapsing into one line.
        assertEquals(listOf("Mix flour & sugar.", "Bake for 10 minutes."), recipe.instructions)
    }

    @Test
    fun `deeply nested JSON returns null rather than crashing, and a normal block after it still parses`() {
        // 20,000 reliably overflows a default JVM stack; this overflows inside
        // JSONTokener#nextValue() itself, before findRecipeNode's depth cap ever runs.
        val deep = "{\"a\":".repeat(20000) + "1" + "}".repeat(20000)
        val normal = """
            {
              "@type": "Recipe",
              "name": "Simple Recipe",
              "recipeIngredient": ["1 egg"],
              "recipeInstructions": ["Boil it."]
            }
        """.trimIndent()

        val recipe = JsonLdRecipeParser.parse(listOf(deep, normal), sourceUrl)

        assertNotNull(recipe)
        assertEquals("Simple Recipe", recipe!!.name)
    }

    @Test
    fun `deeply nested JSON alone returns null`() {
        val deep = "{\"a\":".repeat(20000) + "1" + "}".repeat(20000)
        assertNull(JsonLdRecipeParser.parse(listOf(deep), sourceUrl))
    }

    // --- Durations (D2: zero ISO totals, D3: English phrases) ---

    @Test
    fun `an ISO duration totalling zero is null so the label is hidden`() {
        // Delish publishes "cookTime": "PT0S"; it used to show as "COOK PT0S".
        assertNull(JsonLdRecipeParser.formatDuration("PT0S"))
        assertNull(JsonLdRecipeParser.formatDuration("P0D"))
        assertNull(JsonLdRecipeParser.formatDuration("PT0M"))
        assertNull(JsonLdRecipeParser.formatDuration("PT0H0M"))
    }

    @Test
    fun `ISO durations still format as before`() {
        assertEquals("1h 30m", JsonLdRecipeParser.formatDuration("PT1H30M"))
        assertEquals("45m", JsonLdRecipeParser.formatDuration("PT45M"))
        assertEquals("2h", JsonLdRecipeParser.formatDuration("pt2h"))
        assertEquals("2m", JsonLdRecipeParser.formatDuration("PT90S"))
        assertEquals("25h", JsonLdRecipeParser.formatDuration("P1DT1H"))
        assertEquals("P", JsonLdRecipeParser.formatDuration("P"))
        assertNull(JsonLdRecipeParser.formatDuration("   "))
    }

    @Test
    fun `English duration phrases are rendered like ISO ones`() {
        // Condé Nast sites (Bon Appétit, Epicurious) publish these instead of ISO.
        assertEquals("20m", JsonLdRecipeParser.formatDuration("20 minutes"))
        assertEquals("1h", JsonLdRecipeParser.formatDuration("1 hour"))
        assertEquals("1h 30m", JsonLdRecipeParser.formatDuration("1 hour 30 minutes"))
        assertEquals("1h 5m", JsonLdRecipeParser.formatDuration("1 hr 5 mins"))
        assertEquals("2h 15m", JsonLdRecipeParser.formatDuration("2 Hours and 15 Minutes"))
        assertEquals("1h 30m", JsonLdRecipeParser.formatDuration("1 hour, 30 minutes"))
        assertEquals("1h 30m", JsonLdRecipeParser.formatDuration("90 min"))
        assertEquals("1h 30m", JsonLdRecipeParser.formatDuration("1h30m"))
        assertEquals("20m", JsonLdRecipeParser.formatDuration("  20 mins  "))
        assertNull(JsonLdRecipeParser.formatDuration("0 minutes"))
    }

    @Test
    fun `anything that is not a plain duration phrase stays exactly as written`() {
        assertEquals("Overnight", JsonLdRecipeParser.formatDuration("Overnight"))
        assertEquals("20 to 25 minutes", JsonLdRecipeParser.formatDuration("20 to 25 minutes"))
        assertEquals("1-2 hours", JsonLdRecipeParser.formatDuration("1-2 hours"))
        assertEquals("1.5 hours", JsonLdRecipeParser.formatDuration("1.5 hours"))
        assertEquals("about 20 minutes", JsonLdRecipeParser.formatDuration("about 20 minutes"))
        assertEquals("1 hour and", JsonLdRecipeParser.formatDuration("1 hour and"))
        assertEquals("30 minutes 1 hour", JsonLdRecipeParser.formatDuration("30 minutes 1 hour"))
        assertEquals("garbage", JsonLdRecipeParser.formatDuration("garbage"))
    }

    @Test
    fun `times in a recipe block go through the same formatting`() {
        val block = """
            {"@type": "Recipe", "name": "Times", "recipeIngredient": ["a"],
             "prepTime": "20 minutes", "cookTime": "PT0S", "totalTime": "Overnight"}
        """.trimIndent()

        val recipe = JsonLdRecipeParser.parse(listOf(block), sourceUrl)!!

        assertEquals("20m", recipe.prepTime)
        assertNull(recipe.cookTime)
        assertEquals("Overnight", recipe.totalTime)
    }

    // --- HowToSections (D4: condensed duplicate sections) ---

    @Test
    fun `a WPRM Abbreviated Recipe section is skipped when real sections follow`() {
        // Shape of RecipeTin Eats' WP Recipe Maker output.
        val block = """
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
        """.trimIndent()

        val recipe = JsonLdRecipeParser.parse(listOf(block), sourceUrl)!!

        assertEquals(
            listOf("Brown the beef in batches.", "Simmer for 3 hours.", "Toss with pasta & serve."),
            recipe.instructions
        )
    }

    @Test
    fun `condensed section names match after stripping, trimming and ignoring case`() {
        for (name in listOf(" <b>SUMMARY</b> ", "Quick Version", "short version", "TL;DR",
            "Recipe summary", "At a glance", "abbreviated recipe")) {
            val block = """
                {"@type": "Recipe", "name": "R", "recipeInstructions": [
                  {"@type": "HowToSection", "name": "$name", "itemListElement": [{"@type": "HowToStep", "text": "All of it at once."}]},
                  {"@type": "HowToSection", "name": "Method", "itemListElement": [{"@type": "HowToStep", "text": "Real step."}]}
                ]}
            """.trimIndent()
            assertEquals(name, listOf("Real step."), JsonLdRecipeParser.parse(listOf(block), sourceUrl)!!.instructions)
        }
    }

    @Test
    fun `a lone condensed section is not skipped`() {
        val block = """
            {"@type": "Recipe", "name": "R", "recipeInstructions": [
              {"@type": "HowToSection", "name": "Abbreviated Recipe", "itemListElement": [{"@type": "HowToStep", "text": "Only step."}]}
            ]}
        """.trimIndent()
        assertEquals(listOf("Only step."), JsonLdRecipeParser.parse(listOf(block), sourceUrl)!!.instructions)
    }

    @Test
    fun `a condensed section is kept when no other section has steps`() {
        val block = """
            {"@type": "Recipe", "name": "R", "recipeInstructions": [
              {"@type": "HowToSection", "name": "Summary", "itemListElement": [{"@type": "HowToStep", "text": "Only step."}]},
              {"@type": "HowToSection", "name": "Method", "itemListElement": []}
            ]}
        """.trimIndent()
        assertEquals(listOf("Only step."), JsonLdRecipeParser.parse(listOf(block), sourceUrl)!!.instructions)
    }

    @Test
    fun `sections with ordinary names are all kept, in order, and a name that only contains summary is not skipped`() {
        val block = """
            {"@type": "Recipe", "name": "R", "recipeInstructions": [
              {"@type": "HowToSection", "name": "Summary of the sauce", "itemListElement": [{"@type": "HowToStep", "text": "Sauce."}]},
              {"@type": "HowToSection", "name": "Crust", "itemListElement": [{"@type": "HowToStep", "text": "Crust."}]}
            ]}
        """.trimIndent()
        assertEquals(listOf("Sauce.", "Crust."), JsonLdRecipeParser.parse(listOf(block), sourceUrl)!!.instructions)
    }
}
