package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clip's Text view (#213) reads a Reddit post out of its page. The fixtures in
 * `shared/fixtures/reddit/page-text` (the iOS suite reads them too) are what `RCReddit.text()`
 * returned in the clip view in September 2026 (the `shreddit-post` and the comment tree), with
 * the usernames replaced and the icons cut.
 */
class RedditPageTextTest {

    private fun fixture(name: String) =
        javaClass.getResource("/reddit/page-text/$name.html")!!.readText()

    @Test fun `a text post's body is read a block a line, all of it, though the page collapses it`() {
        val text = RedditPageText.parse(fixture("self-post"))
        assertEquals("Braised Chicken with White Beans and Zucchini", text.title)
        assertEquals(
            "Recipe here originally: https://www.triedandtruerecipe.com/braised-chicken-with-white-beans-and-zucchini/",
            text.body.first()
        )
        assertEquals("2 pounds bone-in, skin-on chicken thighs; about 5 to 6 thighs total", text.body[1])
        assertTrue("For serving:" in text.body)
        assertEquals(
            "Note 1: I use 1 teaspoon kosher salt per pound of chicken. If your chicken weighs a " +
                "little less or a little more than 2 pounds, adjust the salt accordingly.",
            text.body.last()
        )
        // Only the comments the page loaded: 4 of the post's 13.
        assertEquals(
            listOf("salt_fat_acid_fan", "oven_mitt_22", "braise_me", "zest_quest"),
            text.comments.map { it.author }
        )
        assertEquals(
            listOf("This looks great!", "I'm making it this weekend since I have all the ingredients at home, thank you for sharing the recipe."),
            text.comments[1].lines
        )
    }

    @Test fun `a photo post has no body, and the recipe in the poster's comment keeps its lines`() {
        val text = RedditPageText.parse(fixture("gallery-op-comment"))
        assertEquals("Beef Bourguignon", text.title)
        assertEquals(emptyList<String>(), text.body)
        val op = text.comments.first()
        assertEquals("stew_and_bread", op.author)
        assertEquals(0, op.depth)
        assertEquals("Ingredients (serves 4):", op.lines[0])
        assertEquals("800g beef chuck, diced (any good stewing cut works)", op.lines[1])
        // A search link Reddit wraps round a phrase stays inside its line.
        assertEquals("500ml Burgundy red wine (two thirds of a bottle)", op.lines[2])
        // Replies follow their comment, one deeper, each with only its own text.
        assertEquals(listOf(0, 0, 1, 0, 1, 0, 1), text.comments.map { it.depth })
        assertTrue(text.comments.none { c -> c.lines.any { it.startsWith("Ingredients (serves 4)") } && c !== op })
    }

    @Test fun `lists, headings, preformatted text and line breaks each give their own lines`() {
        val html = """
            <shreddit-post post-title="  Pie  "><div slot="text-body"><div property="schema:articleBody">
              <h2>Crust</h2>
              <ul><li><p>1 cup <a href="#">flour</a></p></li><li>1/2 cup butter<ul><li>cold</li></ul></li></ul>
              <p>Mix.<br>Chill.</p>
              <pre><code>Bake 30 min
            Cool</code></pre>
            </div></div></shreddit-post>
        """.trimIndent()
        val text = RedditPageText.parse(html)
        assertEquals("Pie", text.title)
        assertEquals(listOf("Crust", "1 cup flour", "1/2 cup butter", "cold", "Mix.", "Chill.", "Bake 30 min", "Cool"), text.body)
    }

    @Test fun `a page with no post in it reads as empty`() {
        assertTrue(RedditPageText.parse("<html><body><p>Checking your browser</p></body></html>").isEmpty)
    }

    @Test fun `with no post-title, the title heading is the title`() {
        val text = RedditPageText.parse("""<shreddit-post><h1 slot="title"> Soup </h1></shreddit-post>""")
        assertEquals("Soup", text.title)
    }
}
