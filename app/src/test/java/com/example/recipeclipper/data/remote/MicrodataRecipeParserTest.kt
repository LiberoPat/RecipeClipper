package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pages for the microdata fallback. [JETPACK] has the structure of a Smitten Kitchen page
 * (WordPress's Jetpack recipe block, as served in September 2026) with made-up text: the
 * markup is what matters, and the repo is public. The iOS port's MicrodataRecipeParserTests
 * uses the same pages and expects the same results.
 */
internal object MicrodataFixtures {
    const val JETPACK_URL = "https://blog.example/2026/09/tomato-soup/"

    val JETPACK = """
        <!doctype html><html><head>
        <meta property="og:image" content="https://img.example/soup.jpg?fit=1200%2C800&#038;ssl=1">
        </head><body>
        <header><a href="https://blog.example/" itemprop="url">Blog</a></header>
        <article><p>A long story about soup.</p>
        <div class="hrecipe h-recipe jetpack-recipe" itemscope itemtype="https://schema.org/Recipe">
        <h3 class="p-name jetpack-recipe-title" itemprop="name">Tomato Soup with Crispy Onions</h3>
        <ul class="jetpack-recipe-meta">
        <li class="jetpack-recipe-servings" itemprop="recipeYield"><strong>Servings: </strong>4, more as a side</li>
        <li class="jetpack-recipe-time"> <time itemprop="totalTime" datetime="P0DT0H50M0S"><strong>Time:</strong> <span class="time">50 minutes</span></time> </li>
        <li class="jetpack-recipe-print"><a href="#">Print</a></li>
        </ul>
        <div class="jetpack-recipe-content">
        <div class="jetpack-recipe-notes">Make it a day ahead if you like.</div>
        <div class="jetpack-recipe-ingredients"><ul>
        <li class="jetpack-recipe-ingredient" itemprop="recipeIngredient">2 pounds ripe tomatoes, halved</li>
        <li class="jetpack-recipe-ingredient" itemprop="recipeIngredient">1 red onion, thinly sliced</li>
        <li class="jetpack-recipe-ingredient" itemprop="recipeIngredient">Salt &amp; pepper</li>
        </ul></div>
        <div class="jetpack-recipe-directions e-instructions"><strong>Heat oven:</strong> To 350°F.</p>
        <p><strong>Make the soup:</strong> Grate the tomatoes [I use <a href="https://shop.example/grater">this</a>] and simmer for 20 minutes.</p>
        <p>Fry the onion until crisp, about 8 to 10 minutes.</p>
        </div></div></div>
        <p>Comments</p></article></body></html>
    """.trimIndent()

    const val GENERIC_URL = "https://bars.example/lemon-bars"

    val GENERIC = """
        <html><body>
        <div itemscope itemtype="http://schema.org/Recipe">
        <div itemprop="author" itemscope itemtype="http://schema.org/Person"><span itemprop="name">Jane Cook</span></div>
        <h1 itemprop="name">Lemon Bars</h1>
        <img itemprop="image" src="/img/lemon-bars.jpg">
        <meta itemprop="prepTime" content="PT15M">
        <span itemprop="recipeYield">16 bars</span>
        <ul><li itemprop="recipeIngredient">1 cup flour</li><li itemprop="recipeIngredient">2 lemons</li></ul>
        <ol>
        <li itemprop="recipeInstructions" itemscope itemtype="http://schema.org/HowToStep"><span itemprop="text">Heat the oven to 350°F.</span></li>
        <li itemprop="recipeInstructions" itemscope itemtype="http://schema.org/HowToStep"><span itemprop="text">Bake 25 minutes.</span></li>
        </ol>
        </div></body></html>
    """.trimIndent()

    /** A recipe with no ingredients but steps in a plain list, typed alongside another type. */
    const val LIST = """<div itemscope itemtype="https://schema.org/Thing https://schema.org/Recipe"><span itemprop="name">Toast</span><div itemprop="recipeInstructions"><ol><li>Slice the bread.</li><li>Toast it.</li></ol></div></div>"""

    /** Steps in paragraphs nobody closed. */
    const val UNCLOSED = """<div itemscope itemtype="https://schema.org/Recipe"><b itemprop="name">Pancakes</b><div itemprop="recipeInstructions"><p>Mix.<p>Rest 10 minutes.<p>Fry.</div></div>"""

    const val NO_NAME = """<div itemscope itemtype="https://schema.org/Recipe"><span itemprop="recipeIngredient">1 egg</span></div>"""
    const val NAME_ONLY = """<div itemscope itemtype="https://schema.org/Recipe"><span itemprop="name">Nothing</span></div>"""
    const val NOT_A_RECIPE = """<div itemscope itemtype="https://schema.org/Article"><span itemprop="name">News</span><span itemprop="recipeIngredient">1 egg</span></div>"""

    /** Thousands of nested blocks inside the steps: must not overflow the stack. */
    val DEEP = "<div itemscope itemtype=\"https://schema.org/Recipe\"><span itemprop=\"name\">Deep</span>" +
        "<div itemprop=\"recipeInstructions\">" + "<div>".repeat(5_000) + "Stir." + "</div>".repeat(5_000) +
        "</div></div>"
}

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
