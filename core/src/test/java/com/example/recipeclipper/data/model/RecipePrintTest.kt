package com.example.recipeclipper.data.model

import com.example.recipeclipper.data.model.RecipePrint.Block
import com.example.recipeclipper.data.model.RecipePrint.Measured
import com.example.recipeclipper.data.model.RecipePrint.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// iOS: RecipePrintTests.swift, case for case.
class RecipePrintTest {

    private val recipe = Recipe(
        name = "Chicken Adobo",
        image = "https://example.com/adobo.jpg",
        ingredients = listOf("2 lb chicken thighs", "For the sauce:", "1/2 cup soy sauce"),
        instructions = listOf("Marinate the chicken.", "Simmer 30 minutes."),
        prepTime = "10m",
        cookTime = "30m",
        totalTime = null,
        yield = "6 servings",
        sourceUrl = "https://example.com/adobo"
    )

    private fun blocks(
        ingredients: List<String> = listOf("1 kg chicken thighs", "For the sauce:", "60 ml soy sauce"),
        note: String? = null,
        photo: Boolean = true,
        recipe: Recipe = this.recipe
    ) = RecipePrint.blocks(
        recipe, ServingsScale(base = 6, target = 3), ingredients, recipe.instructions, "example.com", note, photo = photo
    )

    @Test fun `the card is the lines as shown, in the reading view's order`() {
        assertEquals(
            listOf(
                Block.Photo("https://example.com/adobo.jpg"),
                Block.Title("Chicken Adobo"),
                Block.Credit("example.com"),
                Block.Meta("Serves 3 (originally 6)"),
                Block.Meta("Prep 10m · Cook 30m"),
                Block.Section("Ingredients"),
                Block.Ingredient("1 kg chicken thighs"),
                Block.GroupHeading("For the sauce:"),
                Block.Ingredient("60 ml soy sauce"),
                Block.Section("Instructions"),
                Block.Step(1, "Marinate the chicken."),
                Block.Step(2, "Simmer 30 minutes.")
            ),
            blocks()
        )
    }

    @Test fun `a note prints last under its heading, a blank one not at all`() {
        assertEquals(listOf(Block.Section("Notes"), Block.Note("Less salt")), blocks(note = "  Less salt\n").takeLast(2))
        assertFalse(blocks(note = "  ").any { it is Block.Note })
    }

    @Test fun `no photo when asked for none or the recipe has none`() {
        assertFalse(blocks(photo = false).any { it is Block.Photo })
        assertFalse(blocks(recipe = recipe.copy(image = null)).any { it is Block.Photo })
    }

    @Test fun `an empty section has no heading`() {
        assertFalse(blocks(ingredients = emptyList()).contains(Block.Section("Ingredients")))
    }

    @Test fun `headings and the title keep with the next block, lines don't`() {
        assertTrue(RecipePrint.keepsWithNext(Block.Section("Ingredients")))
        assertTrue(RecipePrint.keepsWithNext(Block.Title("A")))
        assertFalse(RecipePrint.keepsWithNext(Block.Ingredient("1 egg")))
        assertFalse(RecipePrint.keepsWithNext(Block.Step(1, "Stir")))
    }

    /** Whole blocks (one line each), as the page each lands on. */
    private fun pages(heights: List<Double>, keep: List<Boolean>, pageHeight: Double): List<Int> =
        RecipePrint.paginate(heights.mapIndexed { i, h -> Measured(0.0, listOf(h), keep[i]) }, pageHeight).map { it.page }

    @Test fun `blocks fill a page, then the next`() {
        assertEquals(listOf(0, 0, 1, 1, 2), pages(listOf(40.0, 50.0, 60.0, 30.0, 20.0), List(5) { false }, 100.0))
    }

    @Test fun `a heading never ends a page`() {
        // The heading (20) fits after 70, but its first line (20) wouldn't: both move on.
        assertEquals(listOf(0, 1, 1), pages(listOf(70.0, 20.0, 20.0), listOf(false, true, false), 100.0))
    }

    @Test fun `a kept run taller than a page breaks where it must`() {
        assertEquals(listOf(0, 0, 1), pages(listOf(60.0, 30.0, 90.0), listOf(true, true, false), 100.0))
    }

    @Test fun `a single line taller than a page gets a page of its own`() {
        assertEquals(listOf(0, 1, 2), pages(listOf(10.0, 150.0, 10.0), List(3) { false }, 100.0))
    }

    @Test fun `a block taller than a page carries on over the next pages, split between lines`() {
        // 15 lines of 10 after a block of 10: nine fill the first page, six go on with the next block.
        val pieces = RecipePrint.paginate(
            listOf(Measured(0.0, listOf(10.0), false), Measured(0.0, List(15) { 10.0 }, false), Measured(0.0, listOf(10.0), false)), 100.0
        )
        assertEquals(listOf(Piece(0, 0, 0, 1), Piece(1, 0, 0, 9), Piece(1, 1, 9, 15), Piece(2, 1, 0, 1)), pieces)
    }

    @Test fun `a heading stays with the first line of a block taller than a page`() {
        // The heading (20) and the note's first line (4 + 10) move on together; the note's space counts once.
        val pieces = RecipePrint.paginate(
            listOf(Measured(0.0, listOf(75.0), false), Measured(0.0, listOf(20.0), true), Measured(4.0, List(12) { 10.0 }, false)), 100.0
        )
        assertEquals(listOf(Piece(0, 0, 0, 1), Piece(1, 1, 0, 1), Piece(2, 1, 0, 7), Piece(2, 2, 7, 12)), pieces)
    }

    @Test fun `a block with no lines takes its space`() {
        assertEquals(listOf(Piece(0, 0, 0, 0)), RecipePrint.paginate(listOf(Measured(5.0, emptyList(), false)), 100.0))
        assertEquals(15.0, Measured(5.0, listOf(4.0, 6.0), false).height, 0.0)
    }

    @Test fun `a cookbook numbers from its title and contents pages`() {
        assertEquals(listOf(3, 5, 6), RecipePrint.startPages(listOf(2, 1, 3), contentsPages = 1))
        assertEquals(listOf(4, 5), RecipePrint.startPages(listOf(0, 1), contentsPages = 2))
    }
}
