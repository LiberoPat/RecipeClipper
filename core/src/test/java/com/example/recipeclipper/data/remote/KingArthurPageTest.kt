package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * kingarthurbaking.com lists its tips after the method as schema.org `HowToTip` entries in
 * `recipeInstructions` (#432): seventeen HowToSteps, then three tips (a crumb-topping variation,
 * a spice substitution, a baking-stone tip). They are the recipe's tips, never steps 18 to 20.
 * The page in `shared/fixtures/pages/` is the saved page's one JSON-LD block, verbatim. iOS:
 * `KingArthurPageTests`.
 */
class KingArthurPageTest {

    private val html = javaClass.getResourceAsStream("/pages/site-kingarthur-mini-apple-pies.html")!!
        .bufferedReader().use { it.readText() }

    @Test fun `King Arthur's HowToTip entries are tips, apart from the seventeen steps`() {
        val result = BlogPageParser.parse(html, "https://www.kingarthurbaking.com/recipes/mini-apple-pies-recipe")
        assertTrue(result is ParseResult.Success)
        val recipe = (result as ParseResult.Success).recipe
        assertEquals("Mini Apple Pies", recipe.name)
        assertEquals(9, recipe.ingredients.size)
        assertEquals(17, recipe.instructions.size)
        assertEquals(
            "Storage information: Store any leftover Mini Apple Pies, covered, at room temperature for up to 4 days; " +
                "freeze for longer storage.",
            recipe.instructions.last()
        )
        assertEquals(3, recipe.tips.size)
        assertTrue(recipe.tips[0].startsWith("You can skip the double-crust or lattice and make a crumb topping instead."))
        assertEquals(
            "If you don’t have Apple Pie Spice , mix cinnamon, nutmeg, and allspice to taste and use 1 teaspoon of " +
                "that mixture in the filling.",
            recipe.tips[1]
        )
        assertTrue(recipe.tips[2].startsWith("Baking the Mini Apple Pies on a baking stone or steel"))
        assertEquals("1h 15m", recipe.prepTime)
        assertEquals("24", recipe.yield)
        assertEquals("en", recipe.language)
    }
}
