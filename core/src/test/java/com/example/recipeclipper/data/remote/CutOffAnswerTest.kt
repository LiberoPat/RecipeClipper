package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.CutOffMessage
import com.example.recipeclipper.data.model.PlanDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An AI's answer that stopped mid-recipe (#352): detected, and the message that asks for the rest. */
class CutOffAnswerTest {

    private val whole = """{"@context": "https://schema.org", "@type": "Recipe", "name": "Soup", "day": "2026-11-23", """ +
        """"recipeIngredient": ["1 egg"], "recipeInstructions": [{"@type": "HowToStep", "text": "Cook."}]}"""

    private val cutInFirstRecipe =
        "```json\n[\n  {\n    \"@context\": \"https://schema.org\",\n    \"@type\": \"Recipe\",\n    \"name\": \"Mon\",\n" +
            "    \"day\": \"2026-11-19\",\n    \"recipeIngredient\": [\"1 cup rice\"],\n    \"recipeInstructions\": [\n" +
            "      { \"@type\": \"HowToStep\","

    @Test fun `a recipe cut off with no whole recipe before it is a cut-off answer`() {
        assertTrue(SharedText.isCutOff(cutInFirstRecipe))
        assertTrue(SharedText.isCutOff(cutInFirstRecipe.removePrefix("```json\n")))
        // One recipe asked for: the object itself never closes.
        assertTrue(SharedText.isCutOff("Here you go:\n{ \"@type\": \"Recipe\", \"name\": \"Soup\", \"recipeIngredient\": [\"1 egg\","))
        assertTrue(SharedText.isCutOff("{\"@type\": [\"Recipe\"], \"name\": \"Soup\", \"recipeInstructions\": [{"))
        // And it is no recipe and no plan for the readers.
        assertNull(MealPlanImport.read(cutInFirstRecipe))
        assertFalse(SharedText.read(cutInFirstRecipe).fromChat)
    }

    @Test fun `text that is not JSON, a whole recipe and a whole plan are not cut off`() {
        assertFalse(SharedText.isCutOff("Hi Mum, the scones recipe: 2 cups flour, bake 20 minutes. [see the card"))
        assertFalse(SharedText.isCutOff("Look at this recipe { not json at all"))
        assertFalse(SharedText.isCutOff("[\"a\", \"b\""))
        assertFalse(SharedText.isCutOff(whole))
        assertFalse(SharedText.isCutOff("```json\n[$whole, $whole]\n```"))
        assertFalse(SharedText.isCutOff(""))
        // A closed recipe in a block of its own is whole, whatever follows it.
        assertFalse(SharedText.isCutOff("```json\n$whole\n```\nAnything else? [just ask"))
    }

    @Test fun `a cut-off answer after whole recipes is a plan, so the message names what is missing`() {
        val words = CutOffMessage.Words({ "plan:$it" }, "rest", "recipe")
        fun day(d: Int) = PlanDays.epochDay(2026, 11, d)
        // Five of seven days came: the last two are named, in order.
        val five = (19..23).map { day(it) as Long? }
        assertEquals("plan:2026-11-24, 2026-11-25", CutOffMessage.forPlan(five, words))
        // A gap inside the block is named too.
        assertEquals("plan:2026-11-20, 2026-11-23", CutOffMessage.forPlan(listOf(day(19), day(21), day(22), day(24), day(25)), words))
        // No readable day, or nothing missing in the block: "the remaining days".
        assertEquals("rest", CutOffMessage.forPlan(listOf(null, null), words))
        assertEquals("rest", CutOffMessage.forPlan((19..25).map { day(it) as Long? }, words))
        assertEquals("recipe", CutOffMessage.forRecipe(words))
        assertEquals("2026-01-05", CutOffMessage.iso(PlanDays.epochDay(2026, 1, 5)))
        assertNull(CutOffMessage.missingDays(emptyList()))
    }

    @Test fun `the reply to the follow-up reads as a plan, with prose and a fence before it`() {
        fun meal(name: String, day: String) = whole.replace("Soup", name).replace("2026-11-23", day)
        val reply = "Sure! Here are the remaining days:\n\n```json\n[\n  ${meal("Sat", "2026-11-24")},\n  ${meal("Sun", "2026-11-25")}\n]\n```\n\nEnjoy!"
        val plan = MealPlanImport.read(reply)!!
        assertEquals(listOf("Sat", "Sun"), plan.items.map { it.recipe.name })
        assertEquals(listOf(PlanDays.epochDay(2026, 11, 24), PlanDays.epochDay(2026, 11, 25)), plan.items.map { it.day })
        assertFalse(plan.cutOff)
        assertNotNull(MealPlanImport.read(reply.replace("Enjoy!", "")))
    }
}
