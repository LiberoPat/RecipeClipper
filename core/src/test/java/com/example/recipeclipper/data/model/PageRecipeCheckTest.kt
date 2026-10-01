package com.example.recipeclipper.data.model

import com.example.recipeclipper.data.model.PageRecipeCheck.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The model may only pick text on the page (#103). The corpus's `Pick` rows pin iOS to this. */
class PageRecipeCheckTest {

    private val page = """
        Grandma’s Banana Bread
        Servings: 10 slices
        Ingredients
        ▢ 3 very ripe bananas, mashed
        ⅓ cup melted butter
        1 ½ cups all-purpose flour
        12 cups popcorn
        Instructions
        1. Preheat the oven to 350°F (175°C). Bake for 55 to 65 minutes, until a tester comes out clean.
        Bake for 20-25 minutes.
    """.trimIndent()

    private fun find(picked: String, kind: Kind) = PageRecipeCheck.find(page, picked, kind)

    @Test fun `what shows is the page's own text, found after folding`() {
        assertEquals("⅓ cup melted butter", find("1/3 cup melted butter", Kind.INGREDIENT))
        assertEquals("Grandma’s Banana Bread", find("grandma's banana bread", Kind.NAME))
        assertEquals("1 ½ cups all-purpose flour", find("1 1/2 cups all-purpose flour", Kind.INGREDIENT))
        assertEquals("3 very ripe bananas, mashed", find("3 very ripe bananas, mashed", Kind.INGREDIENT))
    }

    @Test fun `text that isn't on the page is dropped`() {
        assertNull(find("2 cups melted butter", Kind.INGREDIENT))
        assertNull(find("1/2 cup melted butter", Kind.INGREDIENT))
        assertNull(find("Mash the bananas.", Kind.STEP))
    }

    @Test fun `a span never cuts into a number or a word`() {
        assertNull(find("2 cups popcorn", Kind.INGREDIENT))
        assertNull(find("½ cups all-purpose flour", Kind.INGREDIENT))
        assertNull(find("25 minutes.", Kind.STEP))
        assertNull(find("Bake for 20", Kind.STEP))
        assertNull(find("ake for 55 to 65 minutes", Kind.STEP))
        assertEquals("Bake for 55 to 65 minutes, until a tester comes out clean.",
            find("Bake for 55 to 65 minutes, until a tester comes out clean.", Kind.STEP))
    }

    @Test fun `an ingredient starts its line, after a bullet only`() {
        assertNull(find("10 slices", Kind.INGREDIENT))
        assertEquals("10 slices", find("10 slices", Kind.OTHER))
    }

    @Test fun `dashes fold, and a name or step needs a letter`() {
        assertEquals("Bake for 20-25 minutes.", find("Bake for 20–25 minutes.", Kind.STEP))
        assertNull(find("350", Kind.STEP))
        assertNull(find("", Kind.NAME))
    }

    @Test fun `a recipe needs a name plus ingredients or steps`() {
        val picked = PageSelection(
            name = "Grandma's Banana Bread",
            ingredients = listOf("⅓ cup melted butter", "2 eggs"),
            steps = listOf("Mash everything."),
            yield = "10 slices", prepTime = "15 minutes"
        )
        val kept = PageRecipeCheck.verify(page, picked)!!
        assertEquals("Grandma’s Banana Bread", kept.name)
        assertEquals(listOf("⅓ cup melted butter"), kept.ingredients)
        assertEquals(emptyList<String>(), kept.steps)
        assertEquals("10 slices", kept.yield)
        assertNull(kept.prepTime)
        assertNull(PageRecipeCheck.verify(page, picked.copy(name = "Banana Loaf")))
        assertNull(PageRecipeCheck.verify(page, picked.copy(ingredients = listOf("2 eggs"))))
    }
}
