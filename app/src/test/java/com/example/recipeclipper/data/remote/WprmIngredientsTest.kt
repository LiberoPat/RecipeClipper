package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.jsoup.Jsoup

/** WP Recipe Maker's ingredient parts (#118). iOS: `WprmIngredientsTests`, same pages. */
class WprmIngredientsTest {

    private fun ingredients(page: String): List<String> {
        val html = javaClass.getResourceAsStream("/pages/$page.html")!!.bufferedReader().use { it.readText() }
        return (BlogRecipeSource.parse(html, "https://example.com/r") as ParseResult.Success).recipe.ingredients
    }

    private fun card(vararg items: String) =
        Jsoup.parse("<div class='wprm-recipe-ingredients-container'><ul>${items.joinToString("")}</ul></div>")

    private fun li(amount: String, unit: String, name: String, notes: String = "", between: String = " ") =
        "<li class='wprm-recipe-ingredient'><span class='wprm-recipe-ingredient-amount'>$amount</span> " +
            "<span class='wprm-recipe-ingredient-unit'>$unit</span> <span class='wprm-recipe-ingredient-name'>$name</span>" +
            "$between<span class='wprm-recipe-ingredient-notes'>$notes</span></li>"

    @Test fun `RecipeTin Eats' notes read as the card shows them, with its groups as headings`() {
        val lines = ingredients("wprm-recipetineats-greek-zucchini-tots")
        assertEquals(listOf("Zucchini:", "1 lb / 500 g zucchinis (courgettes)", "1/4 tsp cooking salt / kosher salt", "Batter:"), lines.take(4))
        // JSON-LD writes these "2 garlic cloves (, minced)" and "3/4 cup green onion (, finely sliced (…))".
        assert("2 garlic cloves, minced" in lines)
        assert("3/4 cup green onion, finely sliced (white and pale green parts only)" in lines)
        assert("Minted Yoghurt (optional):" in lines)
        assertEquals(22, lines.size)
    }

    @Test fun `an unnamed first group has no heading`() {
        val lines = ingredients("wprm-recipetineats-chicken-chow-mein")
        assertEquals("200g /6 oz chicken breast or thigh fillets, thinly sliced (Note 1 tenderise option)", lines[0])
        assertEquals("Chow Mein Sauce:", lines[9])
    }

    @Test fun `a comma the page puts between name and notes is kept`() {
        assertEquals("kosher salt, *see notes", ingredients("wprm-skinnytaste-air-fryer-chicken")[0])
        val hummus = ingredients("wprm-loveandlemons-hummus")
        assertEquals("1½ cups cooked chickpeas, drained and rinsed", hummus[0])
        // JSON-LD drops this note entirely.
        assertEquals("Paprika, red pepper flakes, and/or fresh parsley, for garnish", hummus[7])
    }

    @Test fun `bracketed notes follow the name`() {
        assertEquals("RICE + VEGETABLES:", ingredients("wprm-minimalistbaker-vegan-fried-rice")[0])
        assertEquals("4 cloves garlic (minced)", ingredients("wprm-minimalistbaker-vegan-fried-rice")[3])
    }

    @Test fun `parts that don't line up with JSON-LD leave its lines alone`() {
        val page = card(li("2", "", "garlic cloves", ", minced"), li("1", "cup", "flour"))
        val jsonLd = listOf("2 garlic cloves (, minced)", "1 cup flour")
        assertEquals(listOf("2 garlic cloves, minced", "1 cup flour"), WprmIngredients.refine(page, jsonLd))
        assertEquals(jsonLd.take(1), WprmIngredients.refine(page, jsonLd.take(1)))
        assertEquals(listOf("2 garlic cloves", "1 cup sugar"), WprmIngredients.refine(page, listOf("2 garlic cloves", "1 cup sugar")))
        assertEquals(jsonLd, WprmIngredients.refine(Jsoup.parse("<p>no card</p>"), jsonLd))
    }

    @Test fun `an ingredient with no name leaves the lines alone`() {
        val page = card(li("2", "", "", "(minced)"))
        assertEquals(listOf("2 (minced)"), WprmIngredients.refine(page, listOf("2 (minced)")))
    }

    @Test fun `notes with no punctuation are spaced, or comma'd when the page puts a comma there`() {
        assertEquals(listOf("1 cup rice rinsed"), WprmIngredients.refine(card(li("1", "cup", "rice", "rinsed")), listOf("1 cup rice")))
        assertEquals(listOf("1 cup rice, rinsed"), WprmIngredients.refine(card(li("1", "cup", "rice", "rinsed", ", ")), listOf("1 cup rice")))
    }
}
