package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.StepPhotos
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

    private fun recipe(page: String, url: String): Recipe = (BlogPageParser.parse(html(page), url) as ParseResult.Success).recipe

    private val nyt = "https://cooking.nytimes.com/recipes/1026066-lemon-layer-cake-with-cream-cheese-frosting"
    private val bbc = "https://www.bbcgoodfood.com/recipes/classic-victoria-sandwich-recipe"
    private val ba = "https://www.bonappetit.com/recipe/bas-best-carrot-cake"
    private val epicurious = "https://www.epicurious.com/recipes/food/views/ba-syn-7up-cake"
    private val delish = "https://www.delish.com/cooking/recipe-ideas/a21968799/cannoli-pie-recipe/"
    private val g750 = "https://www.750g.com/brookie-r98937.htm"
    private val recetas = "https://recetas.elperiodico.com/receta-de-galletas-de-mantequilla-caseras-31553.html"
    private val habas = "https://recetas.elperiodico.com/receta-de-pure-de-habas-56441.html"

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

    @Test fun `750g's brownie and cookie doughs get their headings`() {
        val lines = recipe("site-750g-brookie", g750).ingredients
        assertEquals(listOf("Brownie:", "2 oeufs"), lines.take(2))
        assertEquals(listOf("120 g de chocolat", "Cookie:", "½ paquet de levure chimique"), lines.subList(6, 9))
        assertEquals(listOf("Brownie:", "Cookie:"), lines.filter { it.endsWith(":") })
        assertEquals(13, lines.size)
    }

    @Test fun `RecetasGratis's video pointer and call for comments leave the last step, on its new host and its old one`() {
        listOf(recetas, recetas.replace("recetas.elperiodico.com", "www.recetasgratis.net")).forEach { url ->
            val steps = recipe("site-recetasgratis-galletas-mantequilla", url).instructions
            assertEquals(url, 8, steps.size)
            assertTrue(url, steps.last().endsWith("cuéntanos cómo te han quedado."))
            assertTrue(url, steps.none { "vídeo" in it })
        }
        val last = recipe("site-recetasgratis-pure-de-habas", habas).instructions.last()
        assertEquals("Servir el puré de hablas y espolvorear cebollín finamente picado.", last)
    }

    private val avena = "https://recetas.elperiodico.com/receta-de-galletas-de-avena-faciles-y-rapidas-67303.html"
    private val smitten = "https://smittenkitchen.com/2013/11/green-bean-casserole-with-crispy-onions/"
    private val arancini = "https://ricette.giallozafferano.it/Arancini-di-riso.html"
    private val cuisineaz = "https://www.cuisineaz.com/recettes/baeckeoffe-de-noel-pas-cher-121205.aspx"

    @Test fun `RecetasGratis's last step that only points at a video goes whole, on both hosts (#440)`() {
        listOf(avena, avena.replace("recetas.elperiodico.com", "www.recetasgratis.net")).forEach { url ->
            val steps = recipe("site-recetasgratis-galletas-avena", url).instructions
            assertEquals(url, 4, steps.size)
            assertTrue(url, steps.last().startsWith("Pasado ese tiempo, deja que las galletas de avena se enfríen"))
        }
        // Elsewhere the step stays.
        assertEquals(5, recipe("site-recetasgratis-galletas-avena", "https://example.com/galletas").instructions.size)
    }

    @Test fun `a whole-step phrase drops only the last step, in any case, and never the only one`() {
        val phrases = listOf("no te pierdas este VÍDEO")
        assertEquals(listOf("Mezcla."), SiteRules.dropWholeStep(listOf("Mezcla.", "Además, no te pierdas este vídeo:"), phrases))
        assertEquals(listOf("No te pierdas este VÍDEO."), SiteRules.dropWholeStep(listOf("No te pierdas este VÍDEO."), phrases))
        val middle = listOf("Mezcla y no te pierdas este VÍDEO.", "Hornea.")
        assertEquals(middle, SiteRules.dropWholeStep(middle, phrases))
        assertEquals(listOf("a", "b"), SiteRules.dropWholeStep(listOf("a", "b"), listOf("")))
    }

    @Test fun `Smitten Kitchen's microdata recipe gets its Jetpack card's group headings (#437)`() {
        val recipe = recipe("site-smittenkitchen-green-bean-casserole", smitten)
        val lines = recipe.ingredients
        assertEquals(listOf("Crispy Onions:", "Mushroom Sauce:", "Assembly:"), lines.filter { it.endsWith(":") })
        assertEquals(listOf("Crispy Onions:", "2 medium yellow onions, halved and thinly sliced"), lines.take(2))
        assertEquals(listOf("Mushroom Sauce:", "3 tablespoons unsalted butter"), lines.subList(7, 9))
        assertEquals("1 to 1 1/2 pounds green beans, trimmed and halved (see note; I prefer more)", lines.last())
        assertEquals(19, lines.size)
        // The microdata's own reading is otherwise unchanged; on another host there are no headings.
        assertEquals(16, recipe("site-smittenkitchen-green-bean-casserole", "https://example.com/beans").ingredients.size)
    }

    @Test fun `Giallo Zafferano's dt group names are trusted as headings (#439)`() {
        val lines = recipe("site-giallozafferano-arancini", arancini).ingredients
        assertEquals(
            listOf("Per il riso (per circa 12 arancini):", "per il ripieno al ragù:", "Per il ripieno al prosciutto:", "Per la pastella:", "Per impanare e friggere:"),
            lines.filter { it.endsWith(":") }
        )
        assertEquals(listOf("Per la pastella:", "Farina 00 200 g", "Acqua 350 g", "Sale fino 1 pizzico"), lines.subList(20, 24))
        assertEquals(27, lines.size)
    }

    @Test fun `CuisineAZ's marinade gets its heading, its amounts left out of the match (#439)`() {
        val lines = recipe("site-cuisineaz-baeckeoffe", cuisineaz).ingredients
        assertEquals(listOf("Poivre", "Pour la marinade:", "1 Carotte(s)", "1 Oignon(s)"), lines.subList(9, 13))
        assertEquals("4 Magret de canard", lines[0])
        assertEquals(19, lines.size)
    }

    @Test fun `a heading a card doesn't trust is still refused`() {
        val untrusted = CardHeadings.Card(CardSelector("section.ingredients"), CardSelector("li.ingredient_item"), CardSelector("li.ingredient_step"), amount = CardSelector(".ingredient_qte"))
        val page = Jsoup.parse(html("site-cuisineaz-baeckeoffe"))
        val lines = recipe("site-cuisineaz-baeckeoffe", "https://example.com/b").ingredients
        assertEquals(lines, CardHeadings.lineUp(page, lines, listOf(untrusted)))
    }

    @Test fun `Giallo Zafferano's step photos come from its page, numbered as its text numbers them (#438)`() {
        val recipe = recipe("site-giallozafferano-arancini", arancini)
        assertEquals(11, recipe.instructions.size)
        // The text stays exactly as written, numbers and all.
        assertTrue(recipe.instructions[0], "con il sale grosso 1 ; in questo modo" in recipe.instructions[0])
        assertEquals(List(11) { 3 }, recipe.stepPhotos.map { it.size })
        assertEquals("https://ricette.giallozafferano.it/images/ricette/2/247/arancini-di-riso_1.jpg", recipe.stepPhotos[0][0])
        assertEquals("https://ricette.giallozafferano.it/images/ricette/2/247/arancini-di-riso_33.jpg", recipe.stepPhotos[10][2])
        val numbered = StepPhotos.numbered(recipe.instructions, recipe.stepPhotos)
        assertEquals((1..33).toList(), numbered.flatten().map { it.number })
        assertEquals(listOf(31, 32, 33), numbered[10].map { it.number })
        // Elsewhere the page's photos aren't read.
        assertEquals(emptyList<List<String>>(), recipe("site-giallozafferano-arancini", "https://example.com/arancini").stepPhotos)
    }

    @Test fun `step photos the page doesn't prove are none, and the text stays as written`() {
        val page = html("site-giallozafferano-arancini")
        fun photos(html: String) = (BlogPageParser.parse(html, arancini) as ParseResult.Success).recipe.stepPhotos
        // A number in the text with no photo for it.
        assertEquals(emptyList<List<String>>(), photos(page.replaceFirst("""<span class="num-step">2</span>""", """<span class="num-step">7</span>""")))
        // A photo dropped: its number points at nothing.
        assertEquals(emptyList<List<String>>(), photos(page.replaceFirst("""data-step="5"""", """data-step="x"""")))
        // A step missing from the page.
        assertEquals(emptyList<List<String>>(), photos(page.replaceFirst("""<div class="gz-content-recipe-step">""", "<div>")))
        // A step whose words differ from JSON-LD's.
        assertEquals(emptyList<List<String>>(), photos(page.replaceFirst("Mescolate di tanto in tanto", "Girate")))
    }

    @Test fun `the site check sees the new kinds of rule`() {
        assertEquals(mapOf("ingredients" to true), BlogPageParser.siteRuleCheck(Jsoup.parse(html("site-smittenkitchen-green-bean-casserole"), smitten), smitten))
        assertEquals(
            mapOf("ingredients" to true, "step photos" to true),
            BlogPageParser.siteRuleCheck(Jsoup.parse(html("site-giallozafferano-arancini"), arancini), arancini)
        )
        assertEquals(
            mapOf("step noise" to false, "whole step noise" to true),
            BlogPageParser.siteRuleCheck(Jsoup.parse(html("site-recetasgratis-galletas-avena"), avena), avena)
        )
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
        assertEquals(mapOf("ingredients" to true, "step noise" to true), BlogPageParser.siteRuleCheck(page, ba))
        val renamed = Jsoup.parse(html("site-bbcgoodfood-victoria-sandwich").replace("ingredients-list__item ", "item "))
        assertEquals(mapOf("ingredients" to false), BlogPageParser.siteRuleCheck(renamed, bbc))
        assertNull(BlogPageParser.siteRuleCheck(page, "https://example.com/carrot-cake"))
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
