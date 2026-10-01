package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MicrodataRecipeParserTest {

    private fun parse(html: String, url: String = "https://x.example/r") = MicrodataRecipeParser.parse(html, url)

    @Test fun `a Jetpack recipe block is read, steps from the directions div`() {
        val recipe = parse(MicrodataFixtures.JETPACK, MicrodataFixtures.JETPACK_URL)!!

        assertEquals("Tomato Soup with Crispy Onions", recipe.name)
        assertEquals(listOf("2 pounds ripe tomatoes, halved", "1 red onion, thinly sliced", "Salt & pepper"), recipe.ingredients)
        assertEquals(
            listOf(
                "Heat oven: To 350°F.", // bare text before the first <p>: the step a per-<p> reading loses
                "Make the soup: Grate the tomatoes [I use this] and simmer for 20 minutes.",
                "Fry the onion until crisp, about 8 to 10 minutes.",
            ),
            recipe.instructions
        )
        assertEquals("Servings: 4, more as a side", recipe.yield)
        assertEquals("50m", recipe.totalTime)
        assertNull(recipe.prepTime)
        assertNull(recipe.cookTime)
        assertEquals("https://img.example/soup.jpg?fit=1200%2C800&ssl=1", recipe.image) // og:image, entity decoded
        assertEquals(MicrodataFixtures.JETPACK_URL, recipe.sourceUrl)
    }

    @Test fun `the notes and the page around the block are left out`() {
        val recipe = parse(MicrodataFixtures.JETPACK, MicrodataFixtures.JETPACK_URL)!!
        val everything = recipe.instructions + recipe.ingredients
        assertEquals(emptyList<String>(), everything.filter { "day ahead" in it || "story" in it || "Comments" in it })
    }

    @Test fun `standard microdata is read, and a nested author's name is not the recipe's`() {
        val recipe = parse(MicrodataFixtures.GENERIC, MicrodataFixtures.GENERIC_URL)!!

        assertEquals("Lemon Bars", recipe.name)
        assertEquals("https://bars.example/img/lemon-bars.jpg", recipe.image) // relative src made absolute
        assertEquals("15m", recipe.prepTime)
        assertEquals("16 bars", recipe.yield)
        assertEquals(listOf("1 cup flour", "2 lemons"), recipe.ingredients)
        assertEquals(listOf("Heat the oven to 350°F.", "Bake 25 minutes."), recipe.instructions)
    }

    @Test fun `plain list steps split one per item, and a second itemtype is fine`() {
        val recipe = parse(MicrodataFixtures.LIST)!!
        assertEquals("Toast", recipe.name)
        assertEquals(emptyList<String>(), recipe.ingredients)
        assertEquals(listOf("Slice the bread.", "Toast it."), recipe.instructions)
    }

    @Test fun `unclosed paragraphs are still separate steps`() {
        assertEquals(listOf("Mix.", "Rest 10 minutes.", "Fry."), parse(MicrodataFixtures.UNCLOSED)!!.instructions)
    }

    @Test fun `no name, nothing to cook, or no Recipe item gives nothing`() {
        assertNull(parse(MicrodataFixtures.NO_NAME))
        assertNull(parse(MicrodataFixtures.NAME_ONLY))
        assertNull(parse(MicrodataFixtures.NOT_A_RECIPE))
        assertNull(parse("<html><body><p>Just a story.</p></body></html>"))
    }

    @Test fun `deeply nested steps don't overflow the stack`() {
        val recipe = parse(MicrodataFixtures.DEEP)
        assertNotNull(recipe)
        assertEquals(listOf("Stir."), recipe!!.instructions)
    }
}
