package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeDraftTest {

    @Test fun `lines are trimmed and blank lines dropped`() {
        assertEquals(listOf("1 onion", "2 carrots"), RecipeDraft.lines("  1 onion\r\n\n\t\n2 carrots \n"))
    }

    @Test fun `a recipe needs a name plus ingredients or steps`() {
        assertFalse(RecipeDraft().isValid)
        assertFalse(RecipeDraft(name = "Soup").isValid)
        assertFalse(RecipeDraft(name = "  ", ingredientsText = "1 onion").isValid)
        assertFalse(RecipeDraft(name = "Soup", ingredientsText = "\n  \n").isValid)
        assertTrue(RecipeDraft(name = "Soup", ingredientsText = "1 onion").isValid)
        assertTrue(RecipeDraft(name = "Soup", instructionsText = "Cook.").isValid)
    }

    @Test fun `applying keeps the recipe's identity and makes blank fields absent`() {
        val base = Recipe(
            name = "Old", image = "https://example.com/a.jpg", ingredients = listOf("x"), instructions = emptyList(),
            prepTime = "5m", cookTime = null, totalTime = null, yield = "2", sourceUrl = "https://example.com/a",
            id = 4L, notes = "Mine"
        )

        val applied = RecipeDraft(name = " New ", prepTime = " ", ingredientsText = "a\nb").applyTo(base)

        assertEquals("New", applied.name)
        assertEquals(listOf("a", "b"), applied.ingredients)
        assertNull(applied.prepTime)
        assertNull(applied.image)
        assertNull(applied.yield)
        assertEquals(4L, applied.id)
        assertEquals("Mine", applied.notes)
        assertEquals("https://example.com/a", applied.sourceUrl)
    }

    @Test fun `a draft of a recipe round-trips its content`() {
        val recipe = Recipe(
            name = "Soup", image = null, ingredients = listOf("1 onion", "2 carrots"), instructions = listOf("Cook."),
            prepTime = null, cookTime = "1h", totalTime = null, yield = "4", sourceUrl = "https://example.com/a"
        )
        assertEquals(recipe, RecipeDraft.of(recipe).applyTo(recipe))
    }

    @Test fun `content origin is read by name, an unknown one as the user's version`() {
        assertEquals(ContentOrigin.PARSED, ContentOrigin.fromName(null))
        assertEquals(ContentOrigin.CLIPPED, ContentOrigin.fromName("CLIPPED"))
        assertEquals(ContentOrigin.EDITED, ContentOrigin.fromName("SOMETHING_NEWER"))
        assertEquals(ContentOrigin.EDITED, ContentOrigin.PARSED.afterEdit())
        assertEquals(ContentOrigin.CLIPPED, ContentOrigin.CLIPPED.afterEdit())
        assertEquals(ContentOrigin.MANUAL, ContentOrigin.MANUAL.afterEdit())
    }
}
