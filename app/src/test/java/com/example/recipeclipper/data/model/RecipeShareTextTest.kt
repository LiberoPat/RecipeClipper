package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeShareTextTest {

    private val recipe = Recipe(
        name = "Chicken Adobo",
        image = "https://example.com/adobo.jpg",
        ingredients = listOf("2 lb chicken thighs", "1/2 cup soy sauce"),
        instructions = listOf("Marinate the chicken.", "Simmer 30 minutes."),
        prepTime = "10m",
        cookTime = "30m",
        totalTime = "40m",
        yield = "6 servings",
        sourceUrl = "https://example.com/adobo"
    )

    private fun format(
        recipe: Recipe = this.recipe,
        servings: ServingsScale? = ServingsScale(base = 6, target = 6),
        ingredients: List<String> = recipe.ingredients,
        instructions: List<String> = recipe.instructions
    ) = RecipeShareText.format(recipe, servings, ingredients, instructions)

    @Test fun `title is the first line, followed by a blank line`() {
        val lines = format().lines()
        assertEquals("Chicken Adobo", lines[0])
        assertEquals("", lines[1])
    }

    @Test fun `scaled servings are labelled with the original`() {
        val text = format(servings = ServingsScale(base = 6, target = 3))
        assertTrue(text.contains("Serves 3 (originally 6)"))
    }

    @Test fun `unscaled servings are not labelled`() {
        val text = format(servings = ServingsScale(base = 6, target = 6))
        assertTrue(text.contains("Serves 6"))
        assertFalse(text.contains("originally"))
    }

    @Test fun `a yield that counts things made says Makes`() {
        val cookies = recipe.copy(yield = "Makes 16")
        val scaled = format(recipe = cookies, servings = ServingsScale(base = 16, target = 32))
        assertTrue(scaled.contains("Makes 32 (originally 16)"))
        assertFalse(scaled.contains("Serves"))

        val unscaled = format(recipe = cookies, servings = ServingsScale(base = 16, target = 16))
        assertTrue(unscaled.lines().contains("Makes 16"))
        assertFalse(unscaled.contains("Serves"))
    }

    @Test fun `a recipe with no usable yield omits the serves line entirely`() {
        val text = format(servings = null)
        assertFalse(text.contains("Serves"))
    }

    @Test fun `times line includes only the times that are present`() {
        val all = format()
        assertTrue(all.contains("Prep 10m · Cook 30m · Total 40m"))

        val cookOnly = format(recipe = recipe.copy(prepTime = null, totalTime = null))
        assertTrue(cookOnly.contains("Cook 30m"))
        assertFalse(cookOnly.contains("Prep"))
        assertFalse(cookOnly.contains("Total"))

        val none = format(recipe = recipe.copy(prepTime = null, cookTime = null, totalTime = null))
        assertFalse(none.contains("Prep"))
        assertFalse(none.contains("Cook"))
        assertFalse(none.contains("Total"))
    }

    @Test fun `ingredients are listed one per line under a header`() {
        val text = format()
        assertTrue(text.contains("INGREDIENTS\n2 lb chicken thighs\n1/2 cup soy sauce"))
    }

    @Test fun `instructions are numbered under a header`() {
        val text = format()
        assertTrue(text.contains("INSTRUCTIONS\n1. Marinate the chicken.\n2. Simmer 30 minutes."))
    }

    @Test fun `the ingredients and instructions passed in are used, not the recipe's own`() {
        // Proves scaling/conversion isn't re-derived here: whatever is passed in is what's shared.
        val text = format(ingredients = listOf("1 cup flour (scaled)"), instructions = listOf("Do the thing."))
        assertTrue(text.contains("1 cup flour (scaled)"))
        assertTrue(text.contains("1. Do the thing."))
        assertFalse(text.contains(recipe.ingredients[0]))
    }

    @Test fun `the source url is not shared, and the last step is the last line`() {
        val text = format()
        assertFalse(text.contains(recipe.sourceUrl))
        assertEquals("2. Simmer 30 minutes.", text.lines().last())
    }

    @Test fun `no markdown syntax appears anywhere`() {
        val text = format()
        assertFalse(text.contains("**"))
        assertFalse(text.contains("##"))
        assertFalse(text.contains("- "))
    }
}
