package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.PlanDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A week of AI recipes in shared text (#352): the "Plan my week" answer, read for the review. */
class MealPlanImportTest {

    private fun recipe(name: String, day: String?) =
        """{"@context": "https://schema.org", "@type": "Recipe", "name": "$name", """ +
            (day?.let { """"day": "$it", """ } ?: "") +
            """"recipeYield": "2 servings", "recipeIngredient": ["1 cup rice", "2 eggs"], """ +
            """"recipeInstructions": [{"@type": "HowToStep", "text": "Cook."}]}"""

    private val week = "[" + recipe("Fried rice", "2026-11-23") + ",\n" + recipe("Omelette", "2026-11-24") + "]"

    private fun day(y: Int, m: Int, d: Int) = PlanDays.epochDay(y, m, d)

    @Test fun `an array of recipes with days is a plan, in order, each on its day`() {
        val plan = MealPlanImport.read(week)!!
        assertEquals(listOf("Fried rice", "Omelette"), plan.items.map { it.recipe.name })
        assertEquals(listOf(day(2026, 11, 23), day(2026, 11, 24)), plan.items.map { it.day })
        assertEquals(listOf("1 cup rice", "2 eggs"), plan.items[0].recipe.ingredients)
        assertEquals(listOf("Cook."), plan.items[1].recipe.instructions)
        assertEquals("2 servings", plan.items[0].recipe.yield)
    }

    @Test fun `a fence and prose around it do not matter`() {
        val text = "Here is your week! [Enjoy]\n\n```json\n$week\n```\n\nShall I swap a day? {just ask}"
        assertEquals(2, MealPlanImport.read(text)!!.items.size)
        assertEquals(2, MealPlanImport.read(week.replace("\n", "\r\n"))!!.items.size)
    }

    @Test fun `one recipe is not a plan`() {
        assertNull(MealPlanImport.read(recipe("Fried rice", "2026-11-23")))
        assertNull(MealPlanImport.read("[" + recipe("Fried rice", "2026-11-23") + "]"))
        assertNull(MealPlanImport.read("[" + recipe("Fried rice", null) + "]"))
        assertNull(MealPlanImport.read("No recipe here, just 2026-11-23."))
    }

    // --- What real chat apps produce for the prompt ---

    /** A recipe as an AI writes it for the prompt: the prompt's fields, pretty-printed, a day. */
    private fun pretty(name: String, day: String?, key: String = "day", indent: String = "  "): String {
        val dayLine = day?.let { "$indent  \"$key\": \"$it\",\n" } ?: ""
        return "{\n$indent  \"@context\": \"https://schema.org\",\n$indent  \"@type\": \"Recipe\",\n" +
            "$indent  \"name\": \"$name\",\n$indent  \"description\": \"A quick dinner.\",\n$dayLine" +
            "$indent  \"recipeYield\": \"2 servings\",\n$indent  \"prepTime\": \"PT10M\",\n$indent  \"cookTime\": \"PT20M\",\n" +
            "$indent  \"recipeIngredient\": [\"1 cup rice\", \"2 eggs\"],\n$indent  \"recipeInstructions\": [\n" +
            "$indent    { \"@type\": \"HowToStep\", \"text\": \"Cook.\" },\n" +
            "$indent    { \"@type\": \"HowToStep\", \"text\": \"Serve.\" }\n$indent  ]\n$indent}"
    }

    private val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private fun dayN(n: Int) = "2026-11-%02d".format(19 + n)

    /** The owner's answer: one fenced array, five whole recipes, the sixth stopped inside its steps. */
    private val cutOffArray: String = (0 until 5).joinToString(",\n", "[\n", ",\n") { pretty(names[it], dayN(it)) } +
        "  {\n    \"@context\": \"https://schema.org\",\n    \"@type\": \"Recipe\",\n    \"name\": \"Sat\",\n" +
        "    \"day\": \"2026-11-24\",\n    \"recipeIngredient\": [\"1 cup rice\", \"2 eggs\"],\n" +
        "    \"recipeInstructions\": [\n      { \"@type\": \"HowToStep\","

    @Test fun `a cut-off answer gives every whole recipe, and says it was cut off`() {
        for (text in listOf("Here is your week:\n\n```json\n$cutOffArray", cutOffArray)) {
            val plan = MealPlanImport.read(text)!!
            assertEquals(names.take(5), plan.items.map { it.recipe.name })
            assertEquals((0 until 5).map { day(2026, 11, 19 + it) }, plan.items.map { it.day })
            assertEquals(listOf("Cook.", "Serve."), plan.items[4].recipe.instructions)
            assertTrue(plan.cutOff)
        }
    }

    @Test fun `a whole answer is not cut off, and one whole recipe in a cut-off answer is not a plan`() {
        assertFalse(MealPlanImport.read(week)!!.cutOff)
        val oneThenCut = "```json\n[" + pretty("Mon", dayN(0)) + ",\n{\"@type\": \"Recipe\", \"name\": \"Tue\", \"recipeIngredient\": [\"1 egg\""
        assertNull(MealPlanImport.read(oneThenCut))
    }

    @Test fun `seven code blocks with prose between are one plan`() {
        val text = (0 until 7).joinToString("\n\n") {
            "**${names[it]}**: dinner [see below] {just a note}\n\n```json\n${pretty(names[it], dayN(it), indent = "")}\n```"
        } + "\n\nEnjoy! Want a shopping list?"
        val plan = MealPlanImport.read(text)!!
        assertEquals(names, plan.items.map { it.recipe.name })
        assertEquals((0 until 7).map { day(2026, 11, 19 + it) }, plan.items.map { it.day })
        assertFalse(plan.cutOff)
    }

    @Test fun `seven blocks with the last one cut off give six`() {
        val blocks = (0 until 6).joinToString("\n\n") { "```json\n${pretty(names[it], dayN(it))}\n```" }
        val last = "{\"@type\": \"Recipe\", \"name\": \"Sun\", \"day\": \"2026-11-25\", \"recipeInstructions\": [{ \"@type\": \"HowToStep\","
        val plan = MealPlanImport.read("$blocks\n\n```json\n$last")!!
        assertEquals(names.take(6), plan.items.map { it.recipe.name })
        assertTrue(plan.cutOff)
    }

    @Test fun `a month of separate blocks is read, no more than a month is kept`() {
        val many = (1..40).joinToString("\n") { "```json\n${recipe("R$it", "2026-11-23")}\n```" }
        assertEquals(MealPlanImport.MAX_RECIPES, MealPlanImport.read(many)!!.items.size)
    }

    @Test fun `a wrapper object around the recipes is walked`() {
        for (key in listOf("mealPlan", "recipes", "plan")) {
            val text = "{\"title\": \"Your week\", \"$key\": [" + recipe("A", "2026-11-23") + "," + recipe("B", "2026-11-24") + "]}"
            assertEquals(listOf("A", "B"), MealPlanImport.read(text)!!.items.map { it.recipe.name })
        }
        val nested = "{\"plan\": {\"meals\": [" + recipe("A", "2026-11-23") + "," + recipe("B", "2026-11-24") + "]}}"
        assertEquals(2, MealPlanImport.read(nested)!!.items.size)
    }

    @Test fun `a days wrapper carries the day down to the recipe inside it`() {
        val text = """{"days": [
            {"day": "2026-11-20", "recipe": ${recipe("A", null)}},
            {"date": "2026-11-21", "meal": "dinner", "recipe": ${recipe("B", null)}},
            {"day": "2026-11-22", "recipe": ${recipe("C", "2026-11-25")}}
        ]}"""
        val plan = MealPlanImport.read(text)!!
        assertEquals(listOf("A", "B", "C"), plan.items.map { it.recipe.name })
        assertEquals(listOf(day(2026, 11, 20), day(2026, 11, 21), day(2026, 11, 25)), plan.items.map { it.day })
        val byKey = """{"2026-11-21": ${recipe("B", null)}, "2026-11-20": ${recipe("A", null)}}"""
        assertEquals(listOf(day(2026, 11, 20), day(2026, 11, 21)), MealPlanImport.read(byKey)!!.items.map { it.day })
    }

    @Test fun `date stands for day on a recipe, but only when it is a date`() {
        val text = "[" + pretty("A", "2026-11-23", key = "date") + "," + pretty("B", "tomorrow", key = "date") + "," +
            pretty("C", "2026-11-25", key = "date") + "]"
        assertEquals(listOf(day(2026, 11, 23), null, day(2026, 11, 25)), MealPlanImport.read(text)!!.items.map { it.day })
    }

    @Test fun `a recipe object without its type is read inside a wrapper`() {
        val bare = """{"name": "A", "day": "2026-11-23", "recipeIngredient": ["1 egg"]}"""
        val text = "{\"mealPlan\": [$bare, ${bare.replace("\"A\"", "\"B\"")}]}"
        assertEquals(listOf("A", "B"), MealPlanImport.read(text)!!.items.map { it.recipe.name })
    }

    @Test fun `recipes with no day in one array or wrapper are a plan with no days`() {
        val array = "[" + recipe("A", null) + "," + recipe("B", null) + "," + recipe("C", null) + "]"
        val plan = MealPlanImport.read("Three dinners:\n```json\n$array\n```")!!
        assertEquals(listOf("A", "B", "C"), plan.items.map { it.recipe.name })
        assertEquals(listOf<Long?>(null, null, null), plan.items.map { it.day })
        assertEquals(2, MealPlanImport.read("{\"mealPlan\": [" + recipe("A", null) + "," + recipe("B", null) + "]}")!!.items.size)
        val cut = "```json\n" + array.dropLast(1) + ",{\"@type\": \"Recipe\", \"name\": \"D\", \"recipeInstructions\": [{"
        val cutPlan = MealPlanImport.read(cut)!!
        assertEquals(3, cutPlan.items.size)
        assertTrue(cutPlan.cutOff)
    }

    @Test fun `recipes with no day in separate blocks, or a changed recipe, are not a plan`() {
        assertNull(MealPlanImport.read("```json\n" + recipe("A", null) + "\n```\nand\n```json\n" + recipe("B", null) + "\n```"))
        val changed = recipe("A", null).replace("\"name\"", "\"isBasedOn\": \"manual:1\", \"name\"")
        assertNull(MealPlanImport.read("[$changed, " + recipe("B", null) + "]"))
        assertNull(MealPlanImport.read("Original:\n$changed\nChanged:\n" + recipe("B", null)))
    }

    @Test fun `a stray bracket in the prose does not hide the plan`() {
        val plan = MealPlanImport.read("Your week [note: a draft\n```json\n$week\n```")!!
        assertEquals(2, plan.items.size)
        assertFalse(plan.cutOff)
    }

    @Test fun `a plan may leave some recipes without a usable day`() {
        val plan = MealPlanImport.read(
            "[" + recipe("A", "2026-11-23") + "," + recipe("B", null) + "," + recipe("C", "Tuesday") + "," +
                recipe("D", "2026-02-30") + "]"
        )!!
        assertEquals(listOf(day(2026, 11, 23), null, null, null), plan.items.map { it.day })
    }

    @Test fun `an item list and a graph are read as arrays`() {
        val list = """{"@type": "ItemList", "itemListElement": [
            {"@type": "ListItem", "position": 1, "day": "2026-11-23", "item": ${recipe("A", null)}},
            {"@type": "ListItem", "position": 2, "item": ${recipe("B", "2026-11-24")}}]}"""
        val plan = MealPlanImport.read(list)!!
        assertEquals(listOf("A", "B"), plan.items.map { it.recipe.name })
        assertEquals(listOf(day(2026, 11, 23), day(2026, 11, 24)), plan.items.map { it.day })
        val graph = """{"@context": "https://schema.org", "@graph": [${recipe("A", "2026-11-23")}, ${recipe("B", "2026-11-24")}]}"""
        assertEquals(2, MealPlanImport.read(graph)!!.items.size)
    }

    @Test fun `a thing that is not a recipe in the array is skipped`() {
        val text = "[" + recipe("A", "2026-11-23") + ", {\"@type\": \"Thing\", \"name\": \"x\"}, 7, " +
            recipe("B", "2026-11-24") + "]"
        assertEquals(listOf("A", "B"), MealPlanImport.read(text)!!.items.map { it.recipe.name })
    }

    @Test fun `no more than a month of recipes are kept`() {
        val many = (1..40).joinToString(",", "[", "]") { recipe("R$it", "2026-11-23") }
        assertEquals(MealPlanImport.MAX_RECIPES, MealPlanImport.read(many)!!.items.size)
    }

    @Test fun `a recipe's url and image are never used`() {
        val text = week.replace("\"name\": \"Fried rice\"", "\"name\": \"Fried rice\", \"url\": \"https://made-up.example/r\", \"image\": \"https://made-up.example/i.jpg\"")
        val first = MealPlanImport.read(text)!!.items[0].recipe
        assertEquals("", first.sourceUrl)
    }

    @Test fun `broken or too deep JSON is not a plan`() {
        assertNull(MealPlanImport.read(week.dropLast(30)))
        assertNull(MealPlanImport.read("[".repeat(5000) + "Recipe" + "]".repeat(5000)))
    }

    @Test fun `days are real dates written year, month, day`() {
        assertEquals(day(2026, 11, 23), MealPlanImport.parseDay("2026-11-23"))
        assertEquals(day(2026, 11, 23), MealPlanImport.parseDay(" 2026-11-23T18:00:00 "))
        assertEquals(day(2028, 2, 29), MealPlanImport.parseDay("2028-02-29"))
        assertNull(MealPlanImport.parseDay("2027-02-29"))
        assertNull(MealPlanImport.parseDay("2026-13-01"))
        assertNull(MealPlanImport.parseDay("2026-00-10"))
        assertNull(MealPlanImport.parseDay("23/11/2026"))
        assertNull(MealPlanImport.parseDay("Monday"))
        assertNull(MealPlanImport.parseDay(""))
        assertNull(MealPlanImport.parseDay(null))
        assertNotNull(MealPlanImport.parseDay("1970-01-01"))
    }
}
