package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.jsoup.Jsoup

/** Tasty Recipes' and Mediavine Create's ingredient headings (#119). iOS: `CardHeadingsTests`, same pages. */
class CardHeadingsTest {

    private fun ingredients(page: String): List<String> {
        val html = javaClass.getResourceAsStream("/pages/$page.html")!!.bufferedReader().use { it.readText() }
        return (BlogRecipeSource.parse(html, "https://example.com/r") as ParseResult.Success).recipe.ingredients
    }

    private fun tasty(body: String) = Jsoup.parse(
        "<div class='tasty-recipes-ingredients'><div class='tasty-recipes-ingredients-header'><h3>Ingredients</h3></div>" +
            "<div class='tasty-recipes-ingredients-body'>$body</div></div>"
    )

    @Test fun `Tasty Recipes' bold paragraphs become headings, and JSON-LD's lines stay`() {
        val lines = ingredients("tasty-pinchofyum-blackout-chocolate-cake")
        assertEquals(listOf("For the chocolate cake:", "3 cups flour"), lines.take(2))
        assertEquals(listOf("1 tablespoon vanilla extract", "For the frosting:"), lines.subList(12, 14))
        // The card curls this dash ("3–4 cups"); JSON-LD's line is kept.
        assertEquals("3-4 cups of chocolate chips", lines.last())
        assertEquals(22, lines.size)
    }

    @Test fun `an unnamed first group has no heading, and a bold name gets a colon`() {
        val lines = ingredients("tasty-pinchofyum-peanut-butter-pie")
        assertEquals("1 cup peanut butter", lines[0])
        assertEquals(listOf("Oreo Crust:", "1 14-ounce package Oreos"), lines.subList(4, 6))
        assertEquals(7, lines.size)
    }

    @Test fun `a plain paragraph ending in a colon is a heading`() {
        val lines = ingredients("tasty-joythebaker-lemon-bars")
        assertEquals("For the Crust:", lines[0])
        assertEquals("For the Topping:", lines[7])
        assertEquals(16, lines.size)
    }

    @Test fun `Mediavine Create's group names become headings`() {
        val pie = ingredients("mv-create-tidymom-apple-pie-bars")
        assertEquals("FOR APPLE FILLING:", pie[0])
        assert("CINNAMON & SUGAR TOPPING:" in pie)
        val brownies = ingredients("mv-create-tidymom-brownie-cookie-sandwiches")
        assertEquals("Brownie Cookies:", brownies[0])
        assertEquals("Chocolate Chip Cookie Dough Frosting:", brownies[2])
    }

    @Test fun `Mediavine Create's older markup, headings straight before each list, reads the same`() {
        val lines = ingredients("mv-create-keytomylime-chicken-kabobs")
        assertEquals(listOf("Chicken Marinade:", "1/3 cup olive oil"), lines.take(2))
        assertEquals("Chicken and Vegetables:", lines[7])
        assertEquals(12, lines.size)
    }

    @Test fun `a card that doesn't line up with JSON-LD leaves its lines alone`() {
        val page = tasty("<p><strong>Dough:</strong></p><ul><li>2 cups flour</li><li>1 egg</li></ul>")
        assertEquals(listOf("Dough:", "2 cups flour", "1 egg"), CardHeadings.refine(page, listOf("2 cups flour", "1 egg")))
        assertEquals(listOf("2 cups flour"), CardHeadings.refine(page, listOf("2 cups flour")))
        assertEquals(listOf("2 cups flour", "2 eggs"), CardHeadings.refine(page, listOf("2 cups flour", "2 eggs")))
        assertEquals(listOf("1 egg"), CardHeadings.refine(Jsoup.parse("<p>no card</p>"), listOf("1 egg")))
    }

    @Test fun `only a heading, a colon or a wholly bold paragraph heads a group`() {
        val page = tasty(
            "<ul><li>1 egg</li></ul><p>Use a <strong>big</strong> bowl</p><ul><li>1 cup milk</li></ul>" +
                "<h4>Topping</h4><ul><li>sugar</li></ul><p><strong>To serve:</strong></p>"
        )
        assertEquals(listOf("1 egg", "1 cup milk", "Topping:", "sugar"), CardHeadings.refine(page, listOf("1 egg", "1 cup milk", "sugar")))
    }
}
