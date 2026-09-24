package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import org.junit.Assert.assertEquals
import org.junit.Test

/** The parser records the recipe's language (#14) and reads times and sections with its words. */
class RecipeLanguageTest {

    private fun page(lang: String?, recipe: String): String {
        val html = if (lang == null) "<html>" else """<html lang="$lang">"""
        return """$html<head><script type="application/ld+json">$recipe</script></head><body></body></html>"""
    }

    private fun parse(html: String): Recipe =
        (BlogRecipeSource.parse(html, "https://example.com/r") as ParseResult.Success).recipe

    private val sections = """
        "recipeInstructions": [
          {"@type": "HowToSection", "name": "Summary", "itemListElement": [{"@type": "HowToStep", "text": "Short version."}]},
          {"@type": "HowToSection", "name": "Method", "itemListElement": [{"@type": "HowToStep", "text": "Mix."}]}
        ]"""

    @Test
    fun `inLanguage is recorded and wins over the page`() {
        val recipe = parse(page("en", """{"@type": "Recipe", "name": "Kuchen", "inLanguage": "de-DE", "recipeIngredient": ["200 g Mehl"]}"""))
        assertEquals("de-de", recipe.language)
    }

    @Test
    fun `a schema_org Language gives its alternateName`() {
        val recipe = parse(page(null, """{"@type": "Recipe", "name": "Torta", "inLanguage": {"@type": "Language", "name": "Italian", "alternateName": "it"}, "recipeIngredient": ["200 g farina"]}"""))
        assertEquals("it", recipe.language)
    }

    @Test
    fun `the page's lang is used when the recipe declares none`() {
        val recipe = parse(page("fr-FR", """{"@type": "Recipe", "name": "Gâteau", "recipeIngredient": ["200 g farine"]}"""))
        assertEquals("fr-fr", recipe.language)
    }

    @Test
    fun `with no declared language an English recipe is detected, and otherwise English is assumed`() {
        val detected = parse(page(null, """{"@type": "Recipe", "name": "Pancakes", "recipeIngredient": ["1 cup flour", "1 large egg", "2 tablespoons sugar, divided"]}"""))
        assertEquals("en", detected.language)
        val fallback = parse(page(null, """{"@type": "Recipe", "name": "Kuchen", "recipeIngredient": ["200 g Mehl"]}"""))
        assertEquals("en", fallback.language)
    }

    @Test
    fun `a page declaring English whose ingredients are clearly German is German`() {
        val recipe = parse(page("en", """{"@type": "Recipe", "name": "Rührkuchen",
            "recipeIngredient": ["500 g Mehl", "200 g Zucker", "3 Eier", "1 Prise Salz", "2 EL Öl"]}"""))
        assertEquals("de", recipe.language)
    }

    @Test
    fun `a page declaring English with ambiguous ingredients stays English`() {
        val recipe = parse(page("en", """{"@type": "Recipe", "name": "Kuchen", "recipeIngredient": ["200 g Mehl", "1 cup sugar"]}"""))
        assertEquals("en", recipe.language)
    }

    @Test
    fun `English words read an English recipe's times and condensed sections`() {
        val recipe = parse(page("en-US", """{"@type": "Recipe", "name": "Cake", "recipeIngredient": ["1 cup flour"],
            "prepTime": "1 hour 30 minutes", "cookTime": "PT20M", $sections}"""))
        assertEquals("1h 30m", recipe.prepTime)
        assertEquals("20m", recipe.cookTime)
        assertEquals(listOf("Mix."), recipe.instructions)
    }

    @Test
    fun `a language with no words reads ISO times only and skips no section`() {
        val recipe = parse(page("de", """{"@type": "Recipe", "name": "Kuchen", "recipeIngredient": ["200 g Mehl"],
            "prepTime": "1 hour 30 minutes", "cookTime": "PT20M", $sections}"""))
        assertEquals("1 hour 30 minutes", recipe.prepTime)
        assertEquals("20m", recipe.cookTime)
        assertEquals(listOf("Short version.", "Mix."), recipe.instructions)
    }

    @Test
    fun `microdata records inLanguage too`() {
        val html = """<html lang="en"><body><div itemscope itemtype="https://schema.org/Recipe">
            <meta itemprop="inLanguage" content="es-MX"><h1 itemprop="name">Tacos</h1>
            <li itemprop="recipeIngredient">2 tortillas</li></div></body></html>"""
        assertEquals("es-mx", parse(html).language)
    }
}
