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

    @Test fun `a nutrition box is left out of the window (#271)`() {
        listOf("Nutrition: per serving", "Nutrition Facts", "kcal507", "fat27.05g", "Protein 5 g", "Calories: 250kcal", "Nährwerte", "Fett: 12 g")
            .forEach { assertTrue(it, RecipeTextWindow.isNutrition(it)) }
        listOf("1 tsp salt", "Salt and pepper to taste", "200g caster sugar", "Salt 1 tsp", "Fat-free yogurt", "Low-fat milk 200ml")
            .forEach { assertFalse(it, RecipeTextWindow.isNutrition(it)) }
        // BBC Good Food's shape: no ingredients heading, and the nutrition box was the densest run of short numeric lines.
        val lines = story + listOf(
            "Units:", "200g caster sugar", "200g softened butter", "4 eggs beaten", "200g self-raising flour", "1 tsp baking powder",
            "2 tbsp milk", "For the filling:", "100g butter softened", "Nutrition: per serving", "kcal507", "fat27.05g", "saturates16g",
            "carbs61.02g", "sugars45g", "low", "fibre0.8g", "protein5g", "salt0.9g", "Method:", "step 1:", "Heat oven to 190C."
        ) + story
        val window = RecipeTextWindow.window(PageText("Victoria sandwich", lines), 1_200)!!.lines()
        assertTrue("200g caster sugar" in window && "Heat oven to 190C." in window)
        assertFalse(window.any { it == "kcal507" || it.startsWith("Nutrition") })
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
