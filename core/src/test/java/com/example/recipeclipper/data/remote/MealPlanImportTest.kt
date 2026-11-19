package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.PlanDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test fun `one recipe, or recipes with no day, are not a plan`() {
        assertNull(MealPlanImport.read(recipe("Fried rice", "2026-11-23")))
        assertNull(MealPlanImport.read("[" + recipe("Fried rice", "2026-11-23") + "]"))
        assertNull(MealPlanImport.read("[" + recipe("A", null) + "," + recipe("B", null) + "]"))
        assertNull(MealPlanImport.read("No recipe here, just 2026-11-23."))
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
