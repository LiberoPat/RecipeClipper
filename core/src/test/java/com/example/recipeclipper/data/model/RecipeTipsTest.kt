package com.example.recipeclipper.data.model

import com.example.recipeclipper.data.remote.SharedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recipe's tips (#432) wherever a recipe is carried: shared as text and printed after the steps,
 * as written and unnumbered; written as HowToTip in the recipe JSON an AI is asked to change, and
 * read back as tips; kept by the editor's draft. iOS: `RecipeTipsTests`.
 */
class RecipeTipsTest {

    private val pies = Recipe(
        name = "Mini Apple Pies", image = null, ingredients = listOf("1 apple"), instructions = listOf("Bake.", "Cool."),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "https://example.com/pies",
        tips = listOf("Use a baking stone.", "Bake at 350°F if you have no stone.")
    )

    @Test fun `shared text puts the tips after the steps, under their heading, unnumbered`() {
        val text = RecipeShareText.format(pies, null, pies.ingredients, pies.instructions)
        assertEquals(
            "Mini Apple Pies\n\nINGREDIENTS\n1 apple\n\nINSTRUCTIONS\n1. Bake.\n2. Cool.\n\n" +
                "TIPS\nUse a baking stone.\nBake at 350°F if you have no stone.",
            text
        )
        val none = RecipeShareText.format(pies.copy(tips = emptyList()), null, pies.ingredients, pies.instructions)
        assertTrue(!none.contains("TIPS"))
    }

    @Test fun `the printed card has a Tips section after the steps and before the note`() {
        val blocks = RecipePrint.blocks(pies, null, pies.ingredients, pies.instructions, null, "Mine")
        assertEquals(
            listOf(
                RecipePrint.Block.Step(2, "Cool."),
                RecipePrint.Block.Section("Tips"),
                RecipePrint.Block.Tip("Use a baking stone."),
                RecipePrint.Block.Tip("Bake at 350°F if you have no stone."),
                RecipePrint.Block.Section("Notes"),
                RecipePrint.Block.Note("Mine")
            ),
            blocks.takeLast(6)
        )
    }

    @Test fun `the recipe JSON for an AI writes the tips as HowToTip after the steps, and reads them back as tips`() {
        val json = AskAiPrompt.recipeJson(pies)
        assertTrue(json.contains("    {\"@type\": \"HowToStep\", \"text\": \"Cool.\"},\n    {\"@type\": \"HowToTip\", \"text\": \"Use a baking stone.\"}"))
        val read = SharedText.read("```json\n$json\n```").recipe
        assertEquals(listOf("Bake.", "Cool."), read.instructions)
        assertEquals(pies.tips, read.tips)
        assertEquals(listOf("Bake.", "Cool.") + pies.tips, read.withTipsAsSteps().instructions)
        assertTrue(!AskAiPrompt.recipeJson(pies.copy(tips = emptyList())).contains("HowToTip"))
    }

    @Test fun `the editor's draft carries the tips one per line, and a blank box is none`() {
        val draft = RecipeDraft.of(pies)
        assertEquals("Use a baking stone.\nBake at 350°F if you have no stone.", draft.tipsText)
        assertEquals(pies.tips, draft.applyTo(pies).tips)
        assertEquals(emptyList<String>(), draft.copy(tipsText = " \n ").applyTo(pies).tips)
        // Tips are never what makes a recipe: a name, plus ingredients or steps.
        assertTrue(!RecipeDraft(name = "R", tipsText = "A tip.").isValid)
    }
}
