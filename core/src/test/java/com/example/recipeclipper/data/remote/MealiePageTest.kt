package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Mealie recipe page already imports through the link (#391): Mealie writes a schema.org
 * `application/ld+json` block into the HTML of a public or shared recipe's page itself
 * (`mealie/routes/spa/__init__.py` in Mealie's source: name, ISO times, the ingredient lines as text,
 * the steps as text, categories, tags as `keywords`), so no JavaScript is needed. The page in
 * `shared/fixtures/pages/` is made to that shape, not saved from a Mealie server. iOS:
 * `MealiePageTests`.
 */
class MealiePageTest {

    private val html = javaClass.getResourceAsStream("/pages/mealie-pasta-e-ceci.html")!!.bufferedReader().use { it.readText() }

    @Test fun `a Mealie page's JSON-LD gives its recipe through the ordinary link import`() {
        val result = BlogPageParser.parse(html, "https://mealie.example.com/g/home/shared/r/9a8b7c6d")
        assertTrue(result is ParseResult.Success)
        val recipe = (result as ParseResult.Success).recipe
        assertEquals("Pasta e Ceci", recipe.name)
        assertEquals(
            listOf("2 cups canned chickpeas, drained", "1 tablespoon olive oil", "200 g ditalini", "salt & pepper, to taste"),
            recipe.ingredients
        )
        assertEquals(3, recipe.instructions.size)
        assertEquals("Add the chickpeas and 4 cups of water, then simmer for 20 minutes.", recipe.instructions[1])
        assertEquals("10m", recipe.prepTime)
        assertEquals("35m", recipe.cookTime)
        assertEquals("45m", recipe.totalTime)
        assertEquals("4 servings", recipe.yield)
        assertEquals("en", recipe.language)
    }
}
