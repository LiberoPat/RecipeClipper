package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Site rules as shared data (#120), on trimmed real pages. iOS: `SiteRulesTests`, same pages. */
class SiteRulesTest {

    private fun html(page: String) = javaClass.getResourceAsStream("/pages/$page.html")!!.bufferedReader().use { it.readText() }

    private fun recipe(page: String, url: String): Recipe = (BlogRecipeSource.parse(html(page), url) as ParseResult.Success).recipe

    private val nyt = "https://cooking.nytimes.com/recipes/1026066-lemon-layer-cake-with-cream-cheese-frosting"
    private val bbc = "https://www.bbcgoodfood.com/recipes/classic-victoria-sandwich-recipe"
    private val ba = "https://www.bonappetit.com/recipe/bas-best-carrot-cake"
    private val epicurious = "https://www.epicurious.com/recipes/food/views/ba-syn-7up-cake"
    private val delish = "https://www.delish.com/cooking/recipe-ideas/a21968799/cannoli-pie-recipe/"

    @Test fun `NYT Cooking's group names become headings`() {
        val lines = recipe("site-nytimes-lemon-layer-cake", nyt).ingredients
        assertEquals("FOR THE CAKE AND LEMON SYRUP:", lines[0])
        assertEquals(listOf("1 tablespoon lemon extract (optional)", "FOR THE FROSTING:"), lines.subList(11, 13))
        assertEquals(18, lines.size)
    }

    @Test fun `BBC Good Food's heading comes back, and its promo after the list is no heading`() {
        val lines = recipe("site-bbcgoodfood-victoria-sandwich", bbc).ingredients
        assertEquals("200g caster sugar", lines[0])
        assertEquals(listOf("2 tbsp milk", "For the filling:", "100g butter softened"), lines.subList(5, 8))
        assertEquals("icing sugar to decorate", lines.last())
        assertEquals(12, lines.size)
    }

    @Test fun `Bon Appetit gets its headings, and the editor's note leaves the last step`() {
        val recipe = recipe("site-bonappetit-carrot-cake", ba)
        assertEquals(listOf("Cake:", "Nonstick vegetable oil spray"), recipe.ingredients.take(2))
        assertEquals("Cream cheese frosting and assembly:", recipe.ingredients[19])
        assertEquals(26, recipe.ingredients.size)
        val last = recipe.instructions.last()
        assertTrue(last, last.endsWith("Garnish top of the cake with Candied Carrot Coins, if desired."))
        assertTrue(recipe.instructions.none { "Editor’s note" in it || "Head this way" in it })
    }

    @Test fun `Epicurious shares Bon Appetit's layout, down to its Special Equipment`() {
        val lines = recipe("site-epicurious-7up-cake", epicurious).ingredients
        assertEquals(listOf("Cake:", "Glaze and assembly:", "Special Equipment:"), lines.filter { it.endsWith(":") })
        assertEquals(listOf("Special Equipment:", "A 12-cup Bundt pan"), lines.takeLast(2))
    }

    @Test fun `Delish's card writes units its own way, so its amounts are left out of the match`() {
        val lines = recipe("site-delish-cannoli-pie", delish).ingredients
        // The card shows "6 Tbsp."; JSON-LD's line stays.
        assertEquals(listOf("For the crust:", "Cooking spray, for pie dish", "10 graham crackers, crushed", "6 tbsp. butter, melted"), lines.take(4))
        assertEquals("For the filling:", lines[6])
        assertEquals(15, lines.size)
    }

    @Test fun `a card with an ingredient JSON-LD lacks leaves JSON-LD's lines alone`() {
        val caesar = "https://www.delish.com/cooking/recipe-ideas/a19695267/caesar-salad-recipe/"
        val lines = recipe("site-delish-caesar-salad", caesar).ingredients
        assertEquals(15, lines.size)
        assertTrue(lines.none { it.endsWith(":") })
    }

    @Test fun `a rule applies only on its own site`() {
        val lines = recipe("site-bbcgoodfood-victoria-sandwich", "https://example.com/victoria-sandwich").ingredients
        assertEquals(11, lines.size)
        val steps = recipe("site-bonappetit-carrot-cake", "https://example.com/carrot-cake").instructions
        assertTrue("Editor’s note" in steps.last())
    }

    @Test fun `the site check sees each rule match, and a renamed class stop matching`() {
        val page = Jsoup.parse(html("site-bonappetit-carrot-cake"))
        assertEquals(mapOf("ingredients" to true, "step noise" to true), BlogRecipeSource.siteRuleCheck(page, ba))
        val renamed = Jsoup.parse(html("site-bbcgoodfood-victoria-sandwich").replace("ingredients-list__item ", "item "))
        assertEquals(mapOf("ingredients" to false), BlogRecipeSource.siteRuleCheck(renamed, bbc))
        assertNull(BlogRecipeSource.siteRuleCheck(page, "https://example.com/carrot-cake"))
    }

    @Test fun `noise is cut only where its phrase starts, and never takes the only step`() {
        val phrases = listOf("Editor’s note:")
        assertEquals(listOf("Mix.", "Bake."), SiteRules.dropNoise(listOf("Mix.", "Bake. Editor’s note: First printed in 2019."), phrases))
        assertEquals(listOf("Mix."), SiteRules.dropNoise(listOf("Mix.", "Editor’s note: First printed in 2019."), phrases))
        assertEquals(listOf("Editor’s note: x"), SiteRules.dropNoise(listOf("Editor’s note: x"), phrases))
        assertEquals(listOf("Bake.Editor’s note: x"), SiteRules.dropNoise(listOf("Bake.Editor’s note: x"), phrases))
        assertEquals(listOf("Editor’s note: x", "Bake."), SiteRules.dropNoise(listOf("Editor’s note: x", "Bake."), phrases))
    }

    @Test fun `selectors match the documented subset and nothing else`() {
        val e = Jsoup.parse("<h3 id='a' class='x  SubHed-icPl y' data-testid=\"List\">t</h3>").selectFirst("h3")!!
        listOf("h3", ".x", ".SubHed-icPl", "#a", "[data-testid]", "[data-testid=List]", "[data-testid='List']",
            "[class^=x]", "[class*=SubHed-]", "h3.x.y[class*=Hed]", "p, h3").forEach { assertTrue(it, CardSelector(it).matches(e)) }
        listOf("p", ".X", ".SubHed", "#b", "[data-x]", "[data-testid=list]", "[class^=y]", "h4.x").forEach { assertFalse(it, CardSelector(it).matches(e)) }
        listOf("div > p", "div p", "a:hover", "li,", "*", "[class|=x]", "").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { CardSelector(it) }
        }
    }

    @Test fun `the table is versioned, keyed by bare host, and every rule reads`() {
        val table = JSONObject(javaClass.getResource("/tables/site-rules.json")!!.readText())
        assertEquals(1, table.getInt("schemaVersion"))
        assertTrue(SiteRules.version >= 1)
        table.getJSONObject("sites").keys().forEach { host ->
            assertTrue(host, host == host.lowercase() && !host.startsWith("www.") && '/' !in host)
            assertTrue(host, SiteRules.site("https://www.$host/r") != null)
        }
        assertEquals(2, SiteRules.cards.size)
    }
}
