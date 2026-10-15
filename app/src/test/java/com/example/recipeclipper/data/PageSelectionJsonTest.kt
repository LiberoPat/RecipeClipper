package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.PageSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Prompt API's reply, read strictly (#103). */
class PageSelectionJsonTest {

    @Test fun `a well-formed reply, bare or in one code fence`() {
        val json = """{"name": "Soup", "ingredients": ["1 onion", " "], "steps": ["Chop."], "yield": null, "cookTime": "20 minutes", "extra": 1}"""
        val expected = PageSelection("Soup", listOf("1 onion"), listOf("Chop."), cookTime = "20 minutes")
        assertEquals(expected, PageSelectionJson.parse(json))
        assertEquals(expected, PageSelectionJson.parse("```json\n$json\n```"))
        assertEquals(PageSelection(null, emptyList(), emptyList()), PageSelectionJson.parse("{}"))
    }

    @Test fun `anything else is no answer`() {
        assertNull(PageSelectionJson.parse("Here is the recipe: {\"name\": \"Soup\"}"))
        assertNull(PageSelectionJson.parse("{\"name\": \"Soup\"} and more"))
        assertNull(PageSelectionJson.parse("{\"name\": 3}"))
        assertNull(PageSelectionJson.parse("{\"name\": \"Soup\", \"steps\": [{\"text\": \"Chop.\"}]}"))
        assertNull(PageSelectionJson.parse("{\"name\": \"Soup\", \"ingredients\": \"1 onion\"}"))
        assertNull(PageSelectionJson.parse("[\"Soup\"]"))
        assertNull(PageSelectionJson.parse("{\"name\": \"Soup\""))
    }
}
