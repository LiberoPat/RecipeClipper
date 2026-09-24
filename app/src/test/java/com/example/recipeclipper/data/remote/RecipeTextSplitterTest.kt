package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.remote.RecipeTextSplitter.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The iOS suite (`RecipeTextSplitterTests.swift`) has the same cases and expectations. */
class RecipeTextSplitterTest {

    private fun split(text: String) = RecipeTextSplitter.split(text)

    @Test fun `splits a Markdown recipe with bold headers, bullets and numbered steps`() {
        val result = split(
            """
            **Ingredients**

            * 2 cups flour
            * 1 tsp salt

            **Instructions**

            1. Mix everything.
            2. Bake for 20 minutes.
            """.trimIndent()
        )!!
        assertEquals(listOf("2 cups flour", "1 tsp salt"), result.ingredients)
        assertEquals(listOf("Mix everything.", "Bake for 20 minutes."), result.instructions)
        assertNull(result.yield)
    }

    @Test fun `prose without headers is never split`() {
        assertNull(split("I mixed 2 cups of flour with some butter and baked it at 350 for 20 minutes. So good!"))
        assertNull(split("2 cups flour\n1 tsp salt\nMix and bake for 20 minutes."))
    }

    @Test fun `needs both an ingredients and an instructions section, each with lines`() {
        assertNull(split("Ingredients:\n2 cups flour\n1 tsp salt"))
        assertNull(split("Directions:\nMix.\nBake."))
        assertNull(split("Ingredients:\n\nDirections:\nMix.\nBake."))
        assertNull(split("Ingredients:\n2 cups flour\nDirections:\n"))
    }

    @Test fun `a header must be the whole line`() {
        assertNull(split("Ingredients: flour, salt, water\nInstructions: mix and bake"))
        assertNull(split("The ingredients are simple\n2 cups flour\nThe method is easy\nBake."))
    }

    @Test fun `recognises the usual header spellings`() {
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("Ingredients"))
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("INGREDIENTS:"))
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("You'll need:"))
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("Ingredients (serves 4)"))
        assertEquals(Section.INGREDIENTS, RecipeTextSplitter.section("Ingredients for the cake:"))
        assertEquals(Section.INSTRUCTIONS, RecipeTextSplitter.section("Directions"))
        assertEquals(Section.INSTRUCTIONS, RecipeTextSplitter.section("Method -"))
        assertEquals(Section.INSTRUCTIONS, RecipeTextSplitter.section("Steps:"))
        assertEquals(Section.INSTRUCTIONS, RecipeTextSplitter.section("How to make it"))
        assertEquals(Section.END, RecipeTextSplitter.section("Notes:"))
        assertEquals(Section.END, RecipeTextSplitter.section("Edit: fixed formatting"))
        assertEquals(Section.END, RecipeTextSplitter.section("EDIT 2: thanks all"))
        assertNull(RecipeTextSplitter.section("Ingredients for this are cheap"))
        assertNull(RecipeTextSplitter.section("Preparation time: 10 min"))
        assertNull(RecipeTextSplitter.section("Update the seasoning to taste: salt"))
        assertNull(RecipeTextSplitter.section("2 cups flour"))
    }

    @Test fun `cleans Markdown off each line`() {
        val clean = RecipeTextSplitter::cleanLine
        assertEquals("2 cups flour", clean("* 2 cups flour"))
        assertEquals("2 cups flour", clean("- 2 cups flour"))
        assertEquals("2 cups flour", clean("• 2 cups flour"))
        assertEquals("Mix.", clean("1. Mix."))
        assertEquals("Mix.", clean("2) Mix."))
        assertEquals("Mix.", clean("Step 3: Mix."))
        assertEquals("1.5 cups milk", clean("1.5 cups milk"))
        assertEquals("Ingredients:", clean("**Ingredients:**"))
        assertEquals("Directions", clean("## Directions"))
        assertEquals("Directions", clean("> __Directions__"))
        assertEquals("#10 can tomatoes", clean("#10 can tomatoes"))
        assertEquals("King Arthur flour", clean("[King Arthur](https://example.com/flour) flour"))
        assertEquals("salt & pepper", clean("salt &amp; pepper"))
        assertEquals("1 cup sugar*", clean("1 cup sugar\\*"))
        assertEquals("butter, softened", clean("*butter, softened*"))
        assertEquals("", clean("---"))
        assertEquals("", clean("&#x200B;"))
        assertEquals("2 eggs", clean("  2 eggs  "))
    }

    @Test fun `the story before the first header is dropped, but a labelled yield and times are kept`() {
        val result = split(
            """
            This one is from my aunt, who made it every summer.
            Serves 4
            Prep time: 10 min
            Cook time: 1 hour 30 minutes
            Total time: Overnight

            Ingredients
            2 eggs
            Instructions
            Whisk.
            """.trimIndent()
        )!!
        assertEquals("Serves 4", result.yield)
        assertEquals("10m", result.prepTime)
        assertEquals("1h 30m", result.cookTime)
        assertEquals("Overnight", result.totalTime)
        assertEquals(listOf("2 eggs"), result.ingredients)
        assertEquals(listOf("Whisk."), result.instructions)
    }

    @Test fun `a labelled yield loses its label, serves and makes stay whole`() {
        assertEquals("12 cookies", split("Yield: 12 cookies\nIngredients\n1 egg\nMethod\nBake.")!!.yield)
        assertEquals("4", split("Servings: 4\nIngredients\n1 egg\nMethod\nBake.")!!.yield)
        assertEquals("Makes 2 loaves", split("Makes 2 loaves\nIngredients\n1 egg\nMethod\nBake.")!!.yield)
    }

    @Test fun `notes and edits after the steps are dropped`() {
        val result = split("Ingredients\n1 egg\nDirections\nBoil for 7 minutes.\nNotes\nUse fresh eggs.\nEdit: typo")!!
        assertEquals(listOf("Boil for 7 minutes."), result.instructions)
    }

    @Test fun `sections may repeat and come in either order`() {
        val result = split(
            """
            For the sauce
            Method
            Simmer the tomatoes.
            Ingredients
            1 can tomatoes
            Ingredients for the pasta:
            200 g spaghetti
            Directions
            Boil the pasta.
            """.trimIndent()
        )!!
        assertEquals(listOf("1 can tomatoes", "200 g spaghetti"), result.ingredients)
        assertEquals(listOf("Simmer the tomatoes.", "Boil the pasta."), result.instructions)
    }

    @Test fun `a sub-heading inside a section stays as written`() {
        val result = split("Ingredients\nFor the dough:\n2 cups flour\nInstructions\nKnead.")!!
        assertEquals(listOf("For the dough:", "2 cups flour"), result.ingredients)
    }

    @Test fun `windows and old-mac line endings split the same`() {
        val expected = split("Ingredients\n1 egg\nMethod\nBoil.")
        assertEquals(expected, split("Ingredients\r\n1 egg\r\nMethod\r\nBoil."))
        assertEquals(expected, split("Ingredients\r1 egg\rMethod\rBoil."))
    }
}
