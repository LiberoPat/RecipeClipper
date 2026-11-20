package com.example.recipeclipper.data.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Swift twin is `AskAiPromptTests`: the same expectations, word for word. */
class AskAiPromptTest {

    private val today = PlanDays.epochDay(2026, 11, 24) // a Tuesday

    private fun item(
        name: String,
        quantity: String? = null,
        expires: Long? = null,
        inStock: Boolean = true,
        alwaysHave: Boolean = false,
        id: Long = 0
    ) = PantryItem(
        id = id, name = name, quantity = quantity, language = "en", aisle = Aisle.OTHER, inStock = inStock,
        alwaysHave = alwaysHave, purchasedDay = null, expiresDay = expires
    )

    private fun setup(units: UnitSystem = UnitSystem.AS_WRITTEN, temperature: TemperatureUnit = TemperatureUnit.AS_WRITTEN) =
        AskAiPrompt.Setup(AskAiPrompt.Words.ENGLISH, units, temperature)

    private val recipe = Recipe(
        name = "Chicken \"Adobo\"",
        image = "https://example.com/adobo.jpg",
        ingredients = listOf("2 lb chicken thighs", "For the sauce:", "1/2 cup soy sauce"),
        instructions = listOf("Marinate the chicken.", "Simmer\n30 minutes."),
        prepTime = "10m",
        cookTime = "30m",
        totalTime = null,
        yield = "6 servings",
        sourceUrl = "https://example.com/adobo",
        language = "en"
    )

    @Test fun `describe asks for one recipe as JSON in a json block with the cook's own words`() {
        val prompt = AskAiPrompt.describe("  a quick lentil soup ", setup())
        assertEquals(
            "I want to cook: a quick lentil soup\n\n" +
                "Reply with exactly one recipe as schema.org Recipe JSON, in a ```json code block. " +
                AskAiPrompt.Words.ENGLISH.recipeFields +
                " Use the units that suit the recipe. Write it in English. " +
                "Put anything else you want to say outside the code block.",
            prompt
        )
    }

    @Test fun `the units and the oven temperature follow Settings`() {
        val metric = AskAiPrompt.describe("soup", setup(UnitSystem.METRIC, TemperatureUnit.CELSIUS))
        assertTrue(metric.contains("Give amounts in metric units (g, kg, ml, l), never cups or ounces. Give oven temperatures in °C."))
        assertFalse(metric.contains("Use the units that suit the recipe."))
        val ounces = AskAiPrompt.describe("soup", setup(UnitSystem.OUNCES, TemperatureUnit.FAHRENHEIT))
        assertTrue(ounces.contains("Give weights in ounces and pounds, and volumes in cups and spoons. Give oven temperatures in °F."))
        // As written asks nothing about temperatures.
        assertFalse(AskAiPrompt.describe("soup", setup()).contains("oven temperatures"))
    }

    @Test fun `the language is the one the words are in`() {
        val german = AskAiPrompt.Setup(
            AskAiPrompt.Words.ENGLISH.copy(language = "Deutsch", writeIn = { "Schreibe es auf $it." }),
            UnitSystem.AS_WRITTEN, TemperatureUnit.AS_WRITTEN
        )
        assertTrue(AskAiPrompt.describe("Suppe", german).contains("Schreibe es auf Deutsch."))
    }

    @Test fun `what can I make puts the items to use up first and the rest after, in stock only`() {
        val pantry = listOf(
            item("rice", "2 cups", id = 1),
            item("Spinach", "1 bag", expires = today + 1, id = 2),
            item("milk", expires = today, id = 3),
            item("flour", inStock = false, id = 4),
            item("yoghurt", expires = today + 5, id = 5),
            item("salt", expires = today, alwaysHave = true, id = 6),
            item("old cream", expires = today - 1, id = 7)
        )
        val prompt = AskAiPrompt.whatCanIMake(pantry, today, "", setup())
        assertTrue(prompt.contains("Use these up first, they expire soon: milk, Spinach (1 bag)."))
        // Past its date is not "soon"; a staple is never offered to use up; run out is left out.
        assertTrue(prompt.contains("Also in my pantry: old cream, rice (2 cups), salt, yoghurt."))
        assertFalse(prompt.contains("flour"))
        assertFalse(prompt.contains("Also: "))
        assertTrue(prompt.contains("```json"))
    }

    @Test fun `what can I make with an empty pantry still asks, without the lists`() {
        val prompt = AskAiPrompt.whatCanIMake(emptyList(), today, "vegetarian", setup())
        assertFalse(prompt.contains("Use these up first"))
        assertFalse(prompt.contains("Also in my pantry"))
        assertTrue(prompt.contains("\n\nAlso: vegetarian\n\n"))
    }

    @Test fun `the pantry in a prompt is capped`() {
        val pantry = (1..100).map { item("item%03d".format(it), id = it.toLong()) }
        val prompt = AskAiPrompt.whatCanIMake(pantry, today, "", setup())
        assertTrue(prompt.contains("item060."))
        assertFalse(prompt.contains("item061"))
    }

    @Test fun `plan my week lists the days, the favourites and what was cooked lately`() {
        val prompt = AskAiPrompt.planMyWeek(
            days = (0 until 3).map { today + it },
            favourites = listOf("Lasagne", " Pad Thai "),
            cooked = listOf("Chili"),
            request = "no fish",
            setup = setup(UnitSystem.METRIC, TemperatureUnit.CELSIUS)
        )
        assertTrue(
            prompt.startsWith(
                "Plan one dinner recipe for each of these days:\n\n" +
                    "- 2026-11-24 (Tuesday)\n- 2026-11-25 (Wednesday)\n- 2026-11-26 (Thursday)\n\n" +
                    "Recipes I like, for ideas: Lasagne, Pad Thai.\n\n" +
                    "I cooked these lately, so please don't repeat them: Chili.\n\n" +
                    "Also: no fish\n\n" +
                    "Reply with a JSON array in one ```json code block: one schema.org Recipe object for each day listed, " +
                    "in the same order. Give each object one extra property, \"day\", holding that day's date exactly as " +
                    "listed (YYYY-MM-DD). "
            )
        )
        assertTrue(prompt.contains("metric units"))
    }

    @Test fun `plan my week without favourites or cooked recipes leaves those lines out`() {
        val prompt = AskAiPrompt.planMyWeek(listOf(today), emptyList(), listOf(" "), "", setup())
        assertFalse(prompt.contains("Recipes I like"))
        assertFalse(prompt.contains("lately"))
    }

    @Test fun `favourites and cooked titles are capped`() {
        val titles = (1..40).map { "Dish%02d".format(it) }
        val prompt = AskAiPrompt.planMyWeek(listOf(today), titles, titles, "", setup())
        assertTrue(prompt.contains("Dish15."))
        assertFalse(prompt.contains("Dish16"))
    }

    @Test fun `the recipe goes out as schema org JSON with isBasedOn the stable id`() {
        assertEquals(
            """
            {
              "@context": "https://schema.org",
              "@type": "Recipe",
              "name": "Chicken \"Adobo\"",
              "isBasedOn": "https://example.com/adobo",
              "inLanguage": "en",
              "recipeYield": "6 servings",
              "prepTime": "10m",
              "cookTime": "30m",
              "recipeIngredient": [
                "2 lb chicken thighs",
                "For the sauce:",
                "1/2 cup soy sauce"
              ],
              "recipeInstructions": [
                {"@type": "HowToStep", "text": "Marinate the chicken."},
                {"@type": "HowToStep", "text": "Simmer\n30 minutes."}
              ]
            }
            """.trimIndent(),
            AskAiPrompt.recipeJson(recipe)
        )
    }

    @Test fun `the recipe JSON is valid JSON a parser reads back`() {
        val typed = recipe.copy(sourceUrl = "manual:0b1c", name = "Back\\slash \u0001 é 日本", ingredients = emptyList())
        val json = JSONObject(AskAiPrompt.recipeJson(typed))
        assertEquals("manual:0b1c", json.getString("isBasedOn"))
        assertEquals("Back\\slash \u0001 é 日本", json.getString("name"))
        assertEquals(0, json.getJSONArray("recipeIngredient").length())
        val steps: JSONArray = json.getJSONArray("recipeInstructions")
        assertEquals("Simmer\n30 minutes.", steps.getJSONObject(1).getString("text"))
    }

    @Test fun `change this recipe sends the request, the recipe and the reply format`() {
        val prompt = AskAiPrompt.changeRecipe(recipe, " make it vegetarian ", setup(UnitSystem.METRIC, TemperatureUnit.AS_WRITTEN))
        assertTrue(prompt.startsWith("Please change this recipe: make it vegetarian\n\nThe recipe, as schema.org Recipe JSON:\n\n```json\n{"))
        assertTrue(prompt.contains("\n}\n```\n\nReply with the changed recipe as schema.org Recipe JSON, in a ```json code block, keeping \"isBasedOn\" exactly as it is. "))
        assertTrue(prompt.contains("\"isBasedOn\": \"https://example.com/adobo\""))
        assertTrue(prompt.contains("metric units"))
    }

    // --- Plan my week with choices (#392, #367). The Swift twin holds the same strings.

    private fun choice(
        meals: List<String> = listOf("Breakfast", "Dinner"),
        source: AskAiPrompt.PlanSource = AskAiPrompt.PlanSource.NEW,
        candidates: List<AskAiPrompt.PlanCandidate> = emptyList(),
        mix: Int = 0,
        days: Int = 2
    ) = AskAiPrompt.PlanChoice((0 until days).map { today + it }, meals, source, candidates, mix)

    private val saved = listOf(
        AskAiPrompt.PlanCandidate("Chicken Adobo", "https://example.com/adobo"),
        AskAiPrompt.PlanCandidate(" Pancakes ", "manual:abc-123")
    )

    @Test fun `new recipes ask for each chosen meal on each day, with day and meal on each object`() {
        val prompt = AskAiPrompt.planMyWeekPlus(choice(), listOf("Lasagne"), listOf("Chili"), "no fish", setup())
        assertTrue(
            prompt.startsWith(
                "Plan one recipe for each of these meals (Breakfast, Dinner) on each of these days:\n\n" +
                    "- 2026-11-24 (Tuesday)\n- 2026-11-25 (Wednesday)\n\n" +
                    "Recipes I like, for ideas: Lasagne.\n\n" +
                    "I cooked these lately, so please don't repeat them: Chili.\n\n" +
                    "Also: no fish\n\n" +
                    "Reply with a JSON array in one ```json code block: one schema.org Recipe object for each of these " +
                    "meals on each day listed, day by day in the order listed. Give each object two extra properties: " +
                    "\"day\", holding that day's date exactly as listed (YYYY-MM-DD), and \"meal\", holding the meal's " +
                    "name exactly as listed. Use @context"
            )
        )
        assertFalse(prompt.contains("saved recipes"))
        assertFalse(prompt.contains("isBasedOn"))
    }

    @Test fun `from my recipes lists them by name and link and asks for no recipe to be written out`() {
        val prompt = AskAiPrompt.planMyWeekPlus(
            choice(source = AskAiPrompt.PlanSource.MINE, candidates = saved), listOf("Ignored"), listOf("Ignored"), "", setup()
        )
        assertTrue(
            prompt.contains(
                "My saved recipes, one per line as name | link:\n" +
                    "- Chicken Adobo | https://example.com/adobo\n- Pancakes | manual:abc-123\n\n" +
                    "Use only my saved recipes listed above. Don't write them out: for each one give just @type \"Recipe\", " +
                    "its \"name\", its link exactly as listed in \"isBasedOn\", and its day and meal."
            )
        )
        // Nothing is written out, so no fields, units or temperatures are asked for.
        assertFalse(prompt.contains("Use @context"))
        assertFalse(prompt.contains("Give amounts"))
        assertTrue(prompt.endsWith("Write it in English. Put anything else you want to say outside the code block."))
    }

    @Test fun `a mix says how many of mine at most, and the new ones are written out`() {
        val prompt = AskAiPrompt.planMyWeekPlus(
            choice(source = AskAiPrompt.PlanSource.MIX, candidates = saved, mix = 3), listOf("Lasagne"), emptyList(), "",
            setup(UnitSystem.METRIC, TemperatureUnit.CELSIUS)
        )
        assertTrue(prompt.contains("Use up to 3 of my saved recipes listed above and suggest new recipes for the rest."))
        assertTrue(prompt.contains("Write every new recipe out in full."))
        assertTrue(prompt.contains("Recipes I like, for ideas: Lasagne."))
        assertTrue(prompt.contains("Use @context"))
        assertTrue(prompt.contains("metric units"))
        // The count is a number of the week's meals at most (4 here), and at least one.
        assertTrue(AskAiPrompt.planMyWeekPlus(choice(source = AskAiPrompt.PlanSource.MIX, candidates = saved, mix = 99), emptyList(), emptyList(), "", setup())
            .contains("Use up to 4 of my saved recipes"))
        assertTrue(AskAiPrompt.planMyWeekPlus(choice(source = AskAiPrompt.PlanSource.MIX, candidates = saved, mix = 0), emptyList(), emptyList(), "", setup())
            .contains("Use up to 1 of my saved recipes"))
    }

    @Test fun `the saved recipes are capped, blanks are left out`() {
        val many = (1..60).map { AskAiPrompt.PlanCandidate("Dish%02d".format(it), "https://example.com/%02d".format(it)) } +
            AskAiPrompt.PlanCandidate(" ", "https://example.com/blank")
        val prompt = AskAiPrompt.planMyWeekPlus(choice(source = AskAiPrompt.PlanSource.MINE, candidates = many), emptyList(), emptyList(), "", setup())
        assertTrue(prompt.contains("- Dish40 | https://example.com/40"))
        assertFalse(prompt.contains("Dish41"))
        assertEquals(40, AskAiPrompt.MAX_CANDIDATES)
        // Without any usable recipe, a "from my recipes" prompt is no different from new recipes.
        val none = AskAiPrompt.planMyWeekPlus(choice(source = AskAiPrompt.PlanSource.MINE), emptyList(), emptyList(), "", setup())
        assertFalse(none.contains("saved recipes"))
    }

    @Test fun `the meals are as chosen, trimmed, blanks and repeats out, and none falls back to dinners`() {
        val prompt = AskAiPrompt.planMyWeekPlus(choice(meals = listOf(" Lunch ", "", "lunch", "Snack")), emptyList(), emptyList(), "", setup())
        assertTrue(prompt.startsWith("Plan one recipe for each of these meals (Lunch, Snack) on each of these days:"))
        val none = AskAiPrompt.planMyWeekPlus(choice(meals = emptyList()), emptyList(), emptyList(), "", setup())
        assertEquals(AskAiPrompt.planMyWeek((0 until 2).map { today + it }, emptyList(), emptyList(), "", setup()), none)
    }

    @Test fun `a long answer is one with many recipes to write out`() {
        val seven = (0 until 7).map { today + it }
        fun c(meals: Int, source: AskAiPrompt.PlanSource = AskAiPrompt.PlanSource.NEW, mix: Int = 0) =
            AskAiPrompt.PlanChoice(seven, List(meals) { "M$it" }, source, saved, mix)
        assertFalse(c(2).longAnswer) // 14
        assertTrue(c(3).longAnswer) // 21
        assertEquals(21, c(3).slots)
        assertFalse(c(4, AskAiPrompt.PlanSource.MINE).longAnswer)
        assertEquals(19, c(3, AskAiPrompt.PlanSource.MIX, 2).writtenOut)
        assertFalse(c(3, AskAiPrompt.PlanSource.MIX, 10).longAnswer)
    }

    @Test fun `the start day is the first listed`() {
        val prompt = AskAiPrompt.planMyWeekPlus(
            AskAiPrompt.PlanChoice((0 until 7).map { today + 3 + it }, listOf("Dinner")), emptyList(), emptyList(), "", setup()
        )
        assertTrue(prompt.contains("- 2026-11-27 (Friday)\n- 2026-11-28 (Saturday)"))
        assertTrue(prompt.contains("- 2026-12-03 (Thursday)"))
        assertFalse(prompt.contains("2026-11-24"))
    }

    @Test fun `iso dates are padded`() {
        assertEquals("2026-01-05", AskAiPrompt.isoDate(PlanDays.epochDay(2026, 1, 5)))
        assertEquals("1970-01-01", AskAiPrompt.isoDate(0))
    }
}
