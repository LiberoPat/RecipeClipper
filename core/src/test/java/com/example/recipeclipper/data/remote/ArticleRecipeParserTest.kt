package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A recipe written inside an article (#435): a list of amounts, then the method. The pages are in
 * `shared/fixtures/pages/article-*.html`; iOS: `ArticleRecipeParserTests`, with the same expectations.
 */
class ArticleRecipeParserTest {

    private fun page(name: String) =
        javaClass.getResourceAsStream("/pages/$name")!!.bufferedReader().use { it.readText() }

    private fun parse(html: String, url: String = "https://example.com/a") = ArticleRecipeParser.parse(Jsoup.parse(html, url), url)

    private val elPeriodico = "https://recetas.elperiodico.com/articulo-los-cocineros-coinciden-no-comas-los-mejillones-" +
        "de-lata-con-patatas-tienen-mas-sabor-y-son-mas-nutritivos-si-los-combinas-con-queso-parmesano-y-tomate-seco-79292.html"

    @Test fun `the El Periodico article's list and method paragraphs, as written`() {
        val recipe = parse(page("article-elperiodico-mejillones.html"), elPeriodico)!!
        assertEquals(
            "Los cocineros coinciden: “No comas los mejillones de lata con patatas, tienen más sabor y son más " +
                "nutritivos si los combinas con queso parmesano y tomate seco”",
            recipe.name
        )
        assertEquals(
            listOf(
                "1 lata de mejillones en escabeche, preferiblemente grandes.",
                "100-120 gramos de queso parmesano rallado.",
                "1 huevo.",
                "5 o 6 tomates secos conservados en aceite.",
                "Un poco de agua.",
                "Aceite para freír."
            ),
            recipe.ingredients
        )
        assertEquals(6, recipe.steps.size)
        assertEquals(
            "Para la preparación, primero rallamos finamente el parmesano y lo mezclamos con el huevo batido hasta " +
                "obtener una masa espesa y manejable. Si queda demasiado líquida, incorporamos un poco más de queso.",
            recipe.steps.first()
        )
        assertEquals(
            "Conviene montar el aperitivo justo antes de servirlo, porque la humedad del tomate y del escabeche puede " +
                "ablandar la superficie crujiente del queso.",
            recipe.steps.last()
        )
        // The image caption under the method and the next section's paragraphs are not steps.
        assertEquals(0, recipe.steps.count { it.startsWith("Imagen:") || it.startsWith("Más allá") })
        assertEquals("es", recipe.language)
        assertEquals(
            "https://cdn0.recetasgratis.net/es/posts/2/9/2/los_cocineros_coinciden_no_comas_los_mejillones_de_lata_con_patatas_" +
                "tienen_mas_sabor_y_son_mas_nutritivos_si_los_combinas_con_queso_parmesano_y_tomate_seco_79292_1200.jpg",
            recipe.image
        )
        assertEquals(elPeriodico, recipe.sourceUrl)
    }

    @Test fun `the page parser hands it over with no recipe found`() {
        val fetched = BlogPageParser.parsePage(page("article-elperiodico-mejillones.html"), elPeriodico)
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), fetched.result)
        assertEquals(6, fetched.article?.ingredients?.size)
    }

    @Test fun `an English article's ordered method, a steps header skipped and a notes header ending it`() {
        val recipe = parse(page("article-english-ol.html"))!!
        assertEquals("The lemon bars my neighbour taught me", recipe.name)
        assertEquals(
            listOf("1 cup (2 sticks) butter, softened", "2 cups flour", "½ cup powdered sugar", "4 large eggs", "Zest of 2 lemons"),
            recipe.ingredients
        )
        assertEquals(
            listOf(
                "Heat the oven to 350°F.",
                "Press the butter, flour and sugar into a 9x13 pan and bake for 20 minutes.",
                "Whisk the eggs and zest, pour over the crust and bake 25 minutes more."
            ),
            recipe.steps
        )
        assertEquals("https://cdn.example.com/lemon-bars.jpg", recipe.image)
        assertEquals("en", recipe.language)
    }

    @Test fun `the site's menu and footer lists are never ingredients`() {
        assertNull(parse(page("article-nav-list.html")))
    }

    @Test fun `a list of links is never ingredients`() {
        assertNull(parse(page("article-link-list.html")))
    }

    @Test fun `a short bulleted list of tips is never ingredients`() {
        assertNull(parse(page("article-tips-list.html")))
    }

    @Test fun `prose alone is never a recipe`() {
        assertNull(parse(page("article-prose.html")))
    }

    @Test fun `a list of amounts with no method after it is no recipe`() {
        assertNull(parse(page("article-list-only.html")))
    }

    private fun article(items: List<String>, lang: String = "en") =
        "<html lang='$lang'><body><article><h1>Soup</h1><ul>" + items.joinToString("") { "<li>$it</li>" } +
            "</ul><p>Simmer it all for 20 minutes.</p></article></body></html>"

    @Test fun `at least three items must be amounts`() {
        assertNull(parse(article(listOf("2 onions", "1 l stock"))))
        assertEquals(3, parse(article(listOf("2 onions", "1 l stock", "3 carrots")))?.ingredients?.size)
    }

    @Test fun `more than half the items must be amounts`() {
        assertNull(parse(article(listOf("2 onions", "1 l stock", "3 carrots", "Salt", "Pepper", "Parsley"))))
        assertEquals(5, parse(article(listOf("2 onions", "1 l stock", "3 carrots", "Salt", "Pepper")))?.ingredients?.size)
    }

    @Test fun `an item past 200 characters is not an amount`() {
        val long = "2 " + "very ".repeat(45) + "long onions"
        assertNull(parse(article(listOf(long, "1 l stock", "3 carrots"))))
    }

    @Test fun `a language with no tables finds nothing`() {
        assertNull(parse(article(listOf("2 uien", "1 l bouillon", "3 wortels"), lang = "nl")))
    }

    @Test fun `a nested list is not the list`() {
        val html = "<html lang='en'><body><article><h1>Soup</h1><ul><li>Tips<ul><li>2 onions</li><li>1 l stock</li>" +
            "<li>3 carrots</li></ul></li></ul><p>Simmer.</p></article></body></html>"
        assertNull(parse(html))
    }

    @Test fun `the method stops at the next list and the next heading`() {
        val html = "<html lang='en'><body><article><h1>Soup</h1><ul><li>2 onions</li><li>1 l stock</li><li>3 carrots</li></ul>" +
            "<div><p>Chop everything.</p><p>Simmer for 20 minutes.</p></div><ul><li>1 cup rice</li></ul><p>Not a step.</p>" +
            "</article></body></html>"
        assertEquals(listOf("Chop everything.", "Simmer for 20 minutes."), parse(html)?.steps)
        val headed = html.replace("<ul><li>1 cup rice</li></ul>", "<h3>Next</h3>")
        assertEquals(listOf("Chop everything.", "Simmer for 20 minutes."), parse(headed)?.steps)
    }

    @Test fun `no heading, no name, no recipe`() {
        assertNull(parse("<html lang='en'><body><ul><li>2 onions</li><li>1 l stock</li><li>3 carrots</li></ul><p>Simmer.</p></body></html>"))
    }
}
