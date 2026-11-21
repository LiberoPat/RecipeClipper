package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading Recipe Keeper's `recipes.html` (#390): by `itemprop` only, over the markup the real
 * export has (unclosed `<meta>`, unquoted attributes, `itemprop=photo0/`). iOS's
 * `RecipeKeeperParserTests` reads the same pages and expects the same values.
 */
class RecipeKeeperParserTest {

    private fun page(vararg blocks: String) = "<html><body>${blocks.joinToString("")}</body></html>"

    private fun block(inner: String) = "<div class=recipe-details>$inner</div>"

    private val full = block(
        """
        <meta content=abc-123 itemprop=recipeId><meta content="" itemprop=recipeShareId>
        <meta content=True itemprop=recipeIsFavourite><meta content=3 itemprop=recipeRating>
        <h2 itemprop=name>Tarte &amp; Crème</h2>
        <div>Portate: <span itemprop=recipeCourse>Dolce</span><meta content="Primo" itemprop=recipeCourse></div>
        <div>Categorie: <span>Dolci</span><meta content=Dolci itemprop=recipeCategory></div>
        <div>Raccolte: <meta content="Nonna" itemprop=recipeCollection></div>
        <div>Fonte: <span itemprop=recipeSource>https://www.Example.com/tarte?utm_source=x&amp;id=2#c</span></div>
        <div>Porzioni: <span itemprop=recipeYield>4 persone</span></div>
        <meta content=PT15M itemprop=prepTime><meta content=PT0S itemprop=cookTime>
        <div itemprop=recipeIngredients><p><b>La base</b></p><p>200 g farina</p><p></p><p>Il ripieno:</p><p>3 uova</p><p><strong>Sugo </strong><b>speciale</b></p></div>
        <div itemprop=recipeDirections><p>1. Impastare.</p><p></p><p>2. Cuocere a 180&#176;C.</p><p>350 gradi</p></div>
        <div itemprop=recipeNotes><p>Prima nota.</p><p>Seconda nota.</p></div>
        <img src=images/abc-123_1.jpg class=recipe-photos itemprop=photo1/>
        <img src=images/abc-123_0.jpg class=recipe-photos itemprop=photo0/>
        """
    )

    @Test fun `a recipe is read by its itemprops, whatever the labels say`() {
        val parsed = RecipeKeeperParser.parse(page(full))
        assertEquals(1, parsed.blocks)
        assertEquals(0, parsed.notRecipes)
        val r = parsed.recipes.single()
        assertEquals("abc-123", r.id)
        assertEquals("Tarte & Crème", r.name)
        assertEquals("4 persone", r.servings)
        assertEquals("PT15M", r.prepTime)
        assertEquals("PT0S", r.cookTime)
        assertEquals("Prima nota.\nSeconda nota.", r.notes)
        assertEquals(listOf("Dolce", "Primo", "Dolci", "Nonna"), r.categories)
        assertTrue(r.favorite)
    }

    @Test fun `a link is kept cleaned, anything else in the source is none`() {
        assertEquals("https://www.example.com/tarte?id=2", RecipeKeeperParser.parse(page(full)).recipes.single().sourceUrl)
        for (text in listOf("Recipe Keeper", "n/a", "ftp://x.example/a", "www.example.com", "")) {
            val b = block("<h2 itemprop=name>A</h2><span itemprop=recipeSource>$text</span><div itemprop=recipeIngredients><p>1 egg</p></div>")
            assertNull(text, RecipeKeeperParser.parse(page(b)).recipes.single().sourceUrl)
        }
    }

    @Test fun `a bold line is an ingredient heading, written with a colon, and blanks are dropped`() {
        val lines = RecipeKeeperParser.parse(page(full)).recipes.single().ingredients
        assertEquals(listOf("La base:", "200 g farina", "Il ripieno:", "3 uova", "Sugo speciale:"), lines)
    }

    @Test fun `a bold word inside a line is no heading`() {
        val b = block("<h2 itemprop=name>A</h2><div itemprop=recipeIngredients><p>2 <b>large</b> eggs</p><p><b>Salt</b>, to taste</p></div>")
        assertEquals(listOf("2 large eggs", "Salt, to taste"), RecipeKeeperParser.parse(page(b)).recipes.single().ingredients)
    }

    @Test fun `a direction loses a leading number and its dot, and only that`() {
        assertEquals(
            listOf("Impastare.", "Cuocere a 180°C.", "350 gradi"),
            RecipeKeeperParser.parse(page(full)).recipes.single().directions
        )
        val b = block("<h2 itemprop=name>A</h2><div itemprop=recipeDirections><p>12. Serve.</p><p>2.5 cups later</p><p>1.Stir</p></div>")
        assertEquals(listOf("Serve.", "2.5 cups later", "1.Stir"), RecipeKeeperParser.parse(page(b)).recipes.single().directions)
    }

    @Test fun `photos come in their own order, from an unquoted itemprop with a slash`() {
        assertEquals(listOf("images/abc-123_0.jpg", "images/abc-123_1.jpg"), RecipeKeeperParser.parse(page(full)).recipes.single().photos)
    }

    @Test fun `a block with no name, or with neither ingredients nor steps, is not a recipe`() {
        val noName = block("<h2 itemprop=name></h2><div itemprop=recipeIngredients><p>1 egg</p></div>")
        val noText = block("<h2 itemprop=name>Title</h2><div itemprop=recipeIngredients><p></p></div>")
        val parsed = RecipeKeeperParser.parse(page(noName, noText, full))
        assertEquals(3, parsed.blocks)
        assertEquals(2, parsed.notRecipes)
        assertEquals(listOf("Tarte & Crème"), parsed.recipes.map { it.name })
    }

    @Test fun `a favourite is only the word True, a missing id is none`() {
        val b = block("<meta content=False itemprop=recipeIsFavourite><h2 itemprop=name>A</h2><div itemprop=recipeIngredients><p>1 egg</p></div>")
        val r = RecipeKeeperParser.parse(page(b)).recipes.single()
        assertFalse(r.favorite)
        assertNull(r.id)
    }

    @Test fun `a page without recipe blocks has none, a BOM is stripped, and the markup of a web page is no recipe`() {
        assertEquals(0, RecipeKeeperParser.parse("<html><body><h1>Hi</h1></body></html>").blocks)
        assertEquals(1, RecipeKeeperParser.parse("﻿" + page(full)).blocks)
        assertEquals(0, RecipeKeeperParser.parse("").blocks)
    }

    @Test fun `text with no paragraphs is one line, notes and all`() {
        val b = block("<h2 itemprop=name>A</h2><div itemprop=recipeIngredients>1 egg and 2 cups flour</div><div itemprop=recipeNotes>Plain note</div>")
        val r = RecipeKeeperParser.parse(page(b)).recipes.single()
        assertEquals(listOf("1 egg and 2 cups flour"), r.ingredients)
        assertEquals("Plain note", r.notes)
    }
}
