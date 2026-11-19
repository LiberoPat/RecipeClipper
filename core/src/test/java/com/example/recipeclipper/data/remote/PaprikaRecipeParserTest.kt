package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One Paprika recipe's JSON (#382), with the fields libpaprika documents and the ones that go missing. */
class PaprikaRecipeParserTest {

    private fun recipe(json: String): PaprikaRecipe =
        (PaprikaRecipeParser.parse(json) as PaprikaEntry.Recipe).recipe

    private val full = """
        {"uid": "7C3B1A52-0F1E-4C8E-9A57-1D2E3F4A5B6C", "name": "  Brownies ", "ingredients": "For the batter:\n1 cup butter\r\n\n2 eggs\n",
         "directions": "Melt.\n\nBake.", "notes": " Dad's favourite ", "servings": "12 brownies", "prep_time": "20 mins",
         "cook_time": "1 hour 30 minutes", "total_time": "Overnight", "source_url": "http://www.Example.com/b?utm_source=x&id=7#top",
         "image_url": "http://img.example.com/b.jpg", "categories": ["Dessert", "", 5, "Baking"], "photo_data": "AAAA",
         "rating": 5, "unknown": {"deep": [1, [2]]}}
    """.trimIndent()

    @Test fun `every field is read as written, trimmed`() {
        val r = recipe(full)
        assertEquals("7C3B1A52-0F1E-4C8E-9A57-1D2E3F4A5B6C", r.uid)
        assertEquals("Brownies", r.name)
        assertEquals(listOf("For the batter:", "1 cup butter", "2 eggs"), r.ingredients)
        assertEquals(listOf("Melt.", "Bake."), r.directions)
        assertEquals("Dad's favourite", r.notes)
        assertEquals("12 brownies", r.servings)
        // Times are text here; the importer turns whole-string phrases into "1h 30m" later.
        assertEquals("20 mins", r.prepTime)
        assertEquals("1 hour 30 minutes", r.cookTime)
        assertEquals("Overnight", r.totalTime)
        assertEquals(listOf("Dessert", "Baking"), r.categories)
        assertTrue(r.hasPhoto)
    }

    @Test fun `a link is cleaned and upgraded, and a picture link is a web link`() {
        val r = recipe(full)
        assertEquals("https://www.example.com/b?id=7", r.sourceUrl)
        assertEquals("https://img.example.com/b.jpg", r.imageUrl)
    }

    @Test fun `a source that is not a web link is no link`() {
        for (source in listOf("", "n/a", "Grandma", "ftp://x.example.com/r", "file:///etc/passwd", "javascript:alert(1)")) {
            val r = recipe("""{"name": "A", "ingredients": "1 egg", "source_url": ${'"'}${source}${'"'}, "image_url": ${'"'}${source}${'"'}}""")
            assertNull(source, r.sourceUrl)
            assertNull(source, r.imageUrl)
        }
    }

    @Test fun `missing and null fields are absent, never the text null`() {
        val r = recipe("""{"name": "Tea", "directions": "Steep.", "notes": null, "servings": null, "uid": null, "photo_data": null,
            "categories": null, "source_url": null, "prep_time": ""}""")
        assertEquals(listOf("Steep."), r.directions)
        assertEquals(emptyList<String>(), r.ingredients)
        assertNull(r.notes)
        assertNull(r.servings)
        assertNull(r.uid)
        assertNull(r.prepTime)
        assertNull(r.sourceUrl)
        assertEquals(emptyList<String>(), r.categories)
        assertFalse(r.hasPhoto)
    }

    @Test fun `a photos list counts as a picture the file carries`() {
        assertTrue(recipe("""{"name": "A", "ingredients": "1 egg", "photos": [{"filename": "a.jpg", "data": "AAAA"}]}""").hasPhoto)
        assertFalse(recipe("""{"name": "A", "ingredients": "1 egg", "photos": []}""").hasPhoto)
    }

    @Test fun `a number where text goes is kept as its text`() {
        assertEquals("4", recipe("""{"name": "A", "ingredients": "1 egg", "servings": 4}""").servings)
    }

    @Test fun `a recipe needs a name plus ingredients or steps`() {
        assertEquals(PaprikaEntry.NotARecipe, PaprikaRecipeParser.parse("""{"name": "Only a title"}"""))
        assertEquals(PaprikaEntry.NotARecipe, PaprikaRecipeParser.parse("""{"ingredients": "1 egg", "directions": "Boil."}"""))
        assertEquals(PaprikaEntry.NotARecipe, PaprikaRecipeParser.parse("""{"name": "  ", "ingredients": "1 egg"}"""))
        assertEquals(PaprikaEntry.NotARecipe, PaprikaRecipeParser.parse("""{"name": "A", "ingredients": " \n \n", "directions": ""}"""))
        assertEquals(PaprikaEntry.NotARecipe, PaprikaRecipeParser.parse("{}"))
    }

    @Test fun `text that is not a JSON object is unreadable`() {
        for (text in listOf("", "not json", "[]", "[{\"name\": \"A\", \"ingredients\": \"1 egg\"}]", "\"text\"", "42", "{\"name\": ")) {
            assertEquals(text, PaprikaEntry.Unreadable, PaprikaRecipeParser.parse(text))
        }
    }

    @Test fun `a byte order mark is ignored`() {
        assertEquals("A", recipe("﻿{\"name\": \"A\", \"ingredients\": \"1 egg\"}").name)
    }

    @Test fun `nesting thousands deep is unreadable, not a crash`() {
        val deep = "[".repeat(200_000)
        assertEquals(PaprikaEntry.Unreadable, PaprikaRecipeParser.parse("{\"name\": \"A\", \"x\": $deep"))
        assertEquals(PaprikaEntry.Unreadable, PaprikaRecipeParser.parse(deep))
    }
}
