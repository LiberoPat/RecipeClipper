package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.LineRun
import com.example.recipeclipper.data.model.PagePick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Prompt API's reply, read strictly (#103; line runs since #128). */
class PagePickJsonTest {

    @Test fun `a well-formed reply, bare or in one code fence`() {
        val json = """{"name": "Soup", "yield": null, "cookTime": "20 minutes", "extra": 1,
            "ingredients": [{"first": 3, "last": 5}, {"first": 7, "last": 7}], "steps": [{"first": 9, "last": 12}]}"""
        val expected = PagePick(
            "Soup", listOf(LineRun(3, 5), LineRun(7, 7)), listOf(LineRun(9, 12)), cookTime = "20 minutes"
        )
        assertEquals(expected, PagePickJson.parse(json))
        assertEquals(expected, PagePickJson.parse("```json\n$json\n```"))
        assertEquals(PagePick(null, emptyList(), emptyList()), PagePickJson.parse("{}"))
    }

    @Test fun `anything else is no answer`() {
        assertNull(PagePickJson.parse("Here is the recipe: {\"name\": \"Soup\"}"))
        assertNull(PagePickJson.parse("{\"name\": \"Soup\"} and more"))
        assertNull(PagePickJson.parse("{\"name\": 3}"))
        assertNull(PagePickJson.parse("{\"name\": \"Soup\", \"steps\": [\"Chop.\"]}"))
        assertNull(PagePickJson.parse("{\"name\": \"Soup\", \"steps\": [[9, 12]]}"))
        assertNull(PagePickJson.parse("{\"name\": \"Soup\", \"steps\": [{\"first\": \"9\", \"last\": 12}]}"))
        assertNull(PagePickJson.parse("{\"name\": \"Soup\", \"steps\": [{\"first\": 9.5, \"last\": 12}]}"))
        assertNull(PagePickJson.parse("{\"name\": \"Soup\", \"ingredients\": [{\"first\": 3}]}"))
        assertNull(PagePickJson.parse("{\"name\": \"Soup\", \"ingredients\": {\"first\": 3, \"last\": 5}}"))
        assertNull(PagePickJson.parse("[\"Soup\"]"))
        assertNull(PagePickJson.parse("{\"name\": \"Soup\""))
    }
}
