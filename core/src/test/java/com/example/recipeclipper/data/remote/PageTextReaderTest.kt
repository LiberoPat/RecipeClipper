package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A page's HTML as lines (#103). iOS's `PageTextReaderTests` expects exactly the same. */
class PageTextReaderTest {

    @Test fun `each block is a line, inline markup joins, scripts and navigation are left out`() {
        val page = PageTextReader.read(
            """<html lang="de"><head><title>T</title><script>var x = 1;</script></head><body>
               <nav><a href="/">Home</a></nav><h1>Apfel&shy;kuchen</h1>
               <p>Ein <b>schneller</b> Kuchen.<br>Mit&nbsp;Zimt.</p>
               <ul><li><span>200</span> <span>g</span> Mehl</li><li>2 Eier</li></ul>
               <div>lose <div>innen</div> danach</div>
               <footer>© 2024</footer><button>Drucken</button></body></html>""",
            "https://example.com/k"
        )
        assertEquals(
            listOf("Apfelkuchen", "Ein schneller Kuchen.", "Mit Zimt.", "200 g Mehl", "2 Eier", "lose", "innen", "danach"),
            page.lines
        )
        assertEquals("Apfelkuchen", page.title)
        assertEquals("de", page.language)
    }

    @Test fun `the title falls back to og title then the title element, and og image is absolute`() {
        val og = PageTextReader.read(
            """<head><title>Site | Soup</title><meta property="og:title" content="Soup">
               <meta property="og:image" content="/img/soup.jpg"></head><body><p>x</p></body>""",
            "https://example.com/soup"
        )
        assertEquals("Soup", og.title)
        assertEquals("https://example.com/img/soup.jpg", og.image)
        assertEquals("Site | Soup", PageTextReader.read("<title>Site | Soup</title><p>x</p>", "https://e.com/").title)
    }

    @Test fun `a real blog page with no recipe data`() {
        val page = PageTextReader.read(fixture("blog-no-recipe-data.html"), "https://blog.example/banana-bread")
        assertEquals("Grandma’s Banana Bread", page.title)
        assertEquals("https://blog.example/wp-content/uploads/banana-bread.jpg", page.image)
        assertEquals("en-US", page.language)
        assertTrue(page.lines.contains("⅓ cup melted butter"))
        assertTrue(page.lines.contains("1 ½ cups all-purpose flour"))
        assertTrue(page.lines.contains("Prep Time: 15 minutes Cook Time: 1 hour"))
        assertFalse(page.lines.any { "dataLayer" in it || "console" in it || "Recipes" == it || "rights reserved" in it })
    }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/pages/$name")!!.bufferedReader().use { it.readText() }
}
