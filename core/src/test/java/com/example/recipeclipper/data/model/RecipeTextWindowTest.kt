package com.example.recipeclipper.data.model

import com.example.recipeclipper.data.remote.PageTextReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which part of a page goes to the model (#103). iOS's `RecipeTextWindowTests` is the same. */
class RecipeTextWindowTest {

    private val story = List(30) { "A long paragraph of the story, number $it, about why this cake matters to me." }

    @Test fun `the ingredients heading with ingredient lines after it wins over one in the story`() {
        val lines = listOf("Ingredients you'll need", "Ripe bananas are the secret.") + story +
            listOf("Banana Bread", "Ingredients", "3 bananas", "1/3 cup butter", "1 egg", "Instructions", "Mash the bananas.")
        assertEquals(lines.indexOf("Ingredients"), RecipeTextWindow.anchor(lines))
    }

    @Test fun `with no heading, the densest run of ingredient lines`() {
        val lines = story + listOf("200 g Mehl", "2 Eier", "½ TL Salz", "Alles verrühren.")
        assertEquals(30, RecipeTextWindow.anchor(lines))
    }

    @Test fun `the page title leads a window that doesn't hold it, and the lead stays within a fifth`() {
        val lines = story + listOf("200 g Mehl", "2 Eier", "½ TL Salz", "Alles verrühren.")
        val window = RecipeTextWindow.window(PageText("Apfelkuchen", lines), 500)!!.lines()
        assertEquals("Apfelkuchen", window.first())
        assertEquals(listOf("Apfelkuchen", story[29], "200 g Mehl", "2 Eier", "½ TL Salz", "Alles verrühren."), window)
    }

    @Test fun `a page with nothing like a recipe sends nothing`() {
        assertNull(RecipeTextWindow.window(PageText("About us", story), 5_000))
        assertNull(RecipeTextWindow.window(PageText("Login", listOf("Sign in", "Ingredients", "Password")), 5_000))
    }

    @Test fun `headings in other languages and shapes`() {
        assertTrue(RecipeTextWindow.isHeading("INGREDIENTS:"))
        assertTrue(RecipeTextWindow.isHeading("Zutaten für 4 Personen"))
        assertTrue(RecipeTextWindow.isHeading("材料（2人分）"))
        assertTrue(RecipeTextWindow.isHeading("Modo de preparo"))
        assertFalse(RecipeTextWindow.isHeading("Ingredientsnotaword"))
        assertFalse(RecipeTextWindow.isHeading("The ingredients " + "x".repeat(60)))
    }

    @Test fun `the window starts with the title, keeps the card's lead lines and fits the budget`() {
        val page = PageTextReader.read(fixture("blog-no-recipe-data.html"), "https://blog.example/b")
        val window = RecipeTextWindow.window(page, 1_000)!!
        val lines = window.lines()
        assertTrue("Grandma’s Banana Bread" in lines)
        assertTrue(window.length <= 1_000)
        assertTrue("Servings: 10 slices" in lines)
        assertTrue("Ingredients" in lines && "1 ½ cups all-purpose flour" in lines)
        assertFalse(lines.any { it.startsWith("There is something") })
        val wide = RecipeTextWindow.window(page, 4_000)!!
        assertTrue("Mix in the flour." in wide.lines())
    }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/pages/$name")!!.bufferedReader().use { it.readText() }
}
