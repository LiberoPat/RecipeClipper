package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.remote.SharedText.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextTest {

    private val recipe = "Grandma's scones\n\nIngredients:\n- 2 cups flour\n- 1 tsp salt\n\n" +
        "Method:\n1. Mix the flour and salt.\n2. Bake for 20 minutes."

    @Test fun `a recipe splits and opens sorted, in the text's language`() {
        assertEquals(Kind.RECIPE, SharedText.kind(recipe))
        val reading = SharedText.read(recipe)
        assertTrue(reading.sorted)
        assertEquals(listOf("2 cups flour", "1 tsp salt"), reading.recipe.ingredients)
        assertEquals(listOf("Mix the flour and salt.", "Bake for 20 minutes."), reading.recipe.instructions)
        assertEquals("en", reading.recipe.language)
    }

    @Test fun `a recipe wins over a link in it`() {
        assertEquals(Kind.RECIPE, SharedText.kind("$recipe\n\nSent from https://example.com/signature"))
    }

    @Test fun `a link with a message imports, as today`() {
        assertEquals(Kind.LINK, SharedText.kind("Look at this! https://example.com/scones"))
        assertEquals("https://example.com/scones", SharedText.link("Look at this! https://example.com/scones"))
        assertEquals(Kind.LINK, SharedText.kind("https://example.com/scones"))
    }

    @Test fun `a bulleted list is a list`() {
        assertEquals(Kind.LIST, SharedText.kind("Groceries\n- 2 lb chicken thighs\n- milk"))
    }

    @Test fun `prose opens the editor as written, every line a step`() {
        val text = "Hi Mum,\n  Mix two cups of flour with the butter, then bake it.  \n\nLove, Sam"
        assertEquals(Kind.TEXT, SharedText.kind(text))
        val reading = SharedText.read(text)
        assertFalse(reading.sorted)
        assertEquals(emptyList<String>(), reading.recipe.ingredients)
        assertEquals(
            listOf("Hi Mum,", "Mix two cups of flour with the butter, then bake it.", "Love, Sam"),
            reading.recipe.instructions
        )
        assertNull(reading.recipe.yield)
    }

    @Test fun `unbulleted lines with no steps are text, not a list`() {
        assertEquals(Kind.TEXT, SharedText.kind("milk\neggs\nbread"))
    }

    @Test fun `text past the cap is never offered as a recipe`() {
        val long = recipe + "\n" + "x".repeat(SharedText.MAX_LENGTH)
        // As before #306: its bulleted lines are a list.
        assertEquals(Kind.LIST, SharedText.kind(long))
    }

    @Test fun `German text is read with German words`() {
        val text = "Zutaten:\n- 500 g Mehl\n- 2 EL Zucker\n\nZubereitung:\n1. Mehl und Zucker mischen.\n2. Backen."
        assertEquals(Kind.RECIPE, SharedText.kind(text))
        assertEquals("de", SharedText.read(text).recipe.language)
    }
}
