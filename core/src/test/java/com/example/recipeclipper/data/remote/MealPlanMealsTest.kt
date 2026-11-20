package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.CutOffMessage
import com.example.recipeclipper.data.model.MealType
import com.example.recipeclipper.data.model.PlanDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan my week with choices (#392, #367): a plan's `"meal"`, a recipe named by its link instead of
 * written out, and the cut-off follow-up that names day and meal pairs. The Swift twin is
 * `MealPlanMealsTests`.
 */
class MealPlanMealsTest {

    private fun day(d: Int) = PlanDays.epochDay(2026, 11, d)

    private fun written(name: String, day: String?, meal: String?) =
        """{"@context": "https://schema.org", "@type": "Recipe", "name": "$name", """ +
            (day?.let { """"day": "$it", """ } ?: "") + (meal?.let { """"meal": "$it", """ } ?: "") +
            """"recipeIngredient": ["1 cup rice"], "recipeInstructions": [{"@type": "HowToStep", "text": "Cook."}]}"""

    private fun saved(name: String, link: String, day: String?, meal: String?, key: String = "isBasedOn") =
        """{"@type": "Recipe", "name": "$name", "$key": "$link", """ +
            (day?.let { """"day": "$it", """ } ?: "") + (meal?.let { """"meal": "$it", """ } ?: "") +
            """"x": 0}"""

    @Test fun `each recipe keeps the meal it names, trimmed, as written`() {
        val text = "[" + written("Porridge", "2026-11-23", " Breakfast ") + "," + written("Soup", "2026-11-23", "dinner") + "," +
            written("Toast", "2026-11-24", null) + "]"
        val plan = MealPlanImport.read(text)!!
        assertEquals(listOf("Breakfast", "dinner", null), plan.items.map { it.meal })
        assertEquals(listOf(day(23), day(23), day(24)), plan.items.map { it.day })
        assertTrue(plan.items.none { it.namesSavedRecipe })
    }

    @Test fun `a wrapper's meal is carried to the recipe under it`() {
        val text = """{"days": [{"day": "2026-11-20", "meal": "Lunch", "recipe": ${written("A", null, null)}},
            {"day": "2026-11-20", "recipe": ${written("B", null, "Dinner")}}]}"""
        assertEquals(listOf("Lunch", "Dinner"), MealPlanImport.read(text)!!.items.map { it.meal })
    }

    @Test fun `an object that names a saved recipe by its link is read as that, with no recipe of its own`() {
        val text = "```json\n[" + saved("Chicken Adobo", "https://example.com/adobo", "2026-11-23", "Dinner") + "," +
            saved("Pancakes", "manual:abc-123", "2026-11-24", "Breakfast") + "]\n```"
        val plan = MealPlanImport.read(text)!!
        assertEquals(listOf("Chicken Adobo", "Pancakes"), plan.items.map { it.recipe.name })
        assertEquals(listOf("https://example.com/adobo", "manual:abc-123"), plan.items.map { it.link })
        assertEquals(listOf("Dinner", "Breakfast"), plan.items.map { it.meal })
        assertTrue(plan.items.all { it.namesSavedRecipe && it.recipe.ingredients.isEmpty() && it.recipe.instructions.isEmpty() })
        assertEquals(listOf(day(23), day(24)), plan.items.map { it.day })
    }

    @Test fun `url names a saved recipe too, isBasedOn first, and an object with a body is a new recipe`() {
        val byUrl = saved("Soup", "https://example.com/soup", "2026-11-23", "Lunch", key = "url")
        val withBody = """{"@type": "Recipe", "name": "Stew", "isBasedOn": "https://example.com/stew", "day": "2026-11-24", "meal": "Dinner", """ +
            """"recipeIngredient": ["1 carrot"], "recipeInstructions": ["Simmer."]}"""
        val plan = MealPlanImport.read("[$byUrl, $withBody]")!!
        assertEquals("https://example.com/soup", plan.items[0].link)
        assertNull(plan.items[1].link)
        assertEquals(listOf("1 carrot"), plan.items[1].recipe.ingredients)
        // isBasedOn as a thing: its @id, else its url.
        val thing = """{"@type": "Recipe", "name": "Pie", "isBasedOn": {"@id": "https://example.com/pie"}, "day": "2026-11-25"}"""
        assertEquals("https://example.com/pie", MealPlanImport.read("[$byUrl, $thing]")!!.items[1].link)
    }

    @Test fun `a nameless or linkless object with nothing in it is no recipe`() {
        val nameless = """{"@type": "Recipe", "isBasedOn": "https://example.com/x", "day": "2026-11-23"}"""
        val linkless = """{"@type": "Recipe", "name": "Ghost", "day": "2026-11-24"}"""
        val text = "[" + written("A", "2026-11-23", null) + "," + written("B", "2026-11-24", null) + ",$nameless,$linkless]"
        assertEquals(listOf("A", "B"), MealPlanImport.read(text)!!.items.map { it.recipe.name })
    }

    @Test fun `a plan of saved recipes with a meal and no day is still a plan, not a changed recipe`() {
        val text = "[" + saved("A", "https://example.com/a", null, "Dinner") + "," + saved("B", "https://example.com/b", null, "Lunch") + "]"
        assertEquals(2, MealPlanImport.read(text)!!.items.size)
        // With no day and no meal, a link is a changed recipe's (#354), not a week.
        val bare = "[" + saved("A", "https://example.com/a", null, null) + "," + saved("B", "https://example.com/b", null, null) + "]"
        assertNull(MealPlanImport.read(bare))
    }

    @Test fun `one written recipe and one saved recipe are a plan`() {
        val text = "[" + written("New", "2026-11-23", "Dinner") + "," + saved("Old", "https://example.com/old", "2026-11-24", "Dinner") + "]"
        val plan = MealPlanImport.read(text)!!
        assertEquals(listOf(false, true), plan.items.map { it.namesSavedRecipe })
    }

    @Test fun `a week of three meals fits, and a runaway answer is capped at the ceiling`() {
        val week = (0 until 21).joinToString(",", "[", "]") { written("R$it", "2026-11-%02d".format(19 + it / 3), listOf("Breakfast", "Lunch", "Dinner")[it % 3]) }
        assertEquals(21, MealPlanImport.read(week)!!.items.size)
        assertEquals(70, MealPlanImport.MAX_RECIPES)
    }

    // --- Mapping a meal to a meal type (the review's)

    private val types = listOf(
        MealType(1, "Breakfast", "breakfast", 0), MealType(2, "Lunch", "lunch", 1),
        MealType(3, "Abendessen", "dinner", 2), MealType(4, "Snack", "snack", 3), MealType(5, "Brunch", null, 4, uid = "u-5")
    )

    @Test fun `a meal maps to the type of that name, case and spaces ignored, else Dinner`() {
        assertEquals(1L, MealType.idForMeal("breakfast", types))
        assertEquals(2L, MealType.idForMeal("  LUNCH ", types))
        assertEquals(3L, MealType.idForMeal("Abendessen", types)) // the cook's own name for Dinner
        assertEquals(5L, MealType.idForMeal("brunch", types))
        assertEquals(3L, MealType.idForMeal("Dinner", types)) // not a current name: Dinner by default
        assertEquals(3L, MealType.idForMeal("supper", types))
        assertEquals(3L, MealType.idForMeal(null, types))
        assertEquals(3L, MealType.idForMeal("  ", types))
        assertNull(MealType.idForMeal("Lunch", emptyList()))
    }

    @Test fun `a type is named by its builtInKey, else its uid, never its name`() {
        assertEquals("dinner", types[2].key)
        assertEquals("u-5", types[4].key)
    }

    // --- The cut-off follow-up

    private val words = CutOffMessage.Words({ "days:$it" }, "rest", "recipe", { "pairs:$it" })

    @Test fun `a cut-off week of meals names the missing day and meal pairs`() {
        val entries = listOf<Pair<Long?, String?>>(
            day(19) to "Breakfast", day(19) to "Dinner", day(20) to "Breakfast", day(20) to "dinner "
        )
        val missing = CutOffMessage.missingPairs(entries)!!
        assertEquals(14 - 4, missing.size)
        assertEquals(day(21) to "Breakfast", missing[0])
        assertEquals(day(21) to "Dinner", missing[1])
        assertEquals(
            true,
            CutOffMessage.forPlanMeals(entries, words).startsWith("pairs:2026-11-21 (Breakfast), 2026-11-21 (Dinner), 2026-11-22 (Breakfast)")
        )
    }

    @Test fun `a hole inside the block is named, and a whole block asks for the remaining days`() {
        val entries = (19..25).flatMap { d -> listOf<Pair<Long?, String?>>(day(d) to "Lunch", day(d) to "Dinner") }
            .filterNot { it.first == day(21) && it.second == "Lunch" }
        assertEquals("pairs:2026-11-21 (Lunch)", CutOffMessage.forPlanMeals(entries, words))
        assertEquals("rest", CutOffMessage.forPlanMeals(entries + (day(21) to "Lunch"), words))
    }

    @Test fun `with no meal read the follow-up is the days one, and with no day the remaining days`() {
        val days = listOf<Pair<Long?, String?>>(day(19) to null, day(20) to null)
        assertEquals(CutOffMessage.forPlan(listOf(day(19), day(20)), words), CutOffMessage.forPlanMeals(days, words))
        assertEquals("rest", CutOffMessage.forPlanMeals(listOf(null to "Dinner", null to "Lunch"), words))
        assertNull(CutOffMessage.missingPairs(emptyList()))
        assertFalse(CutOffMessage.forPlanMeals(days, words).startsWith("pairs:"))
        assertNotNull(CutOffMessage.missingPairs(listOf(day(19) to "Dinner")))
    }
}
