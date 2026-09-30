package com.example.recipeclipper.ui.clip

import com.example.recipeclipper.data.remote.RedditPageComment
import com.example.recipeclipper.data.remote.RedditPageText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedditTextPageTest {

    private fun html(text: RedditPageText) =
        RedditTextPage.html(text, commentsHeading = "Comments", loadedNote = "Only loaded ones.", author = { "u/$it" })

    @Test fun `the post is a page of paragraphs, the title first`() {
        val page = html(RedditPageText("Pie", listOf("3 apples", "Bake."), emptyList()))
        assertTrue(page.contains("<h1>Pie</h1><p>3 apples</p><p>Bake.</p>"))
        // No comments, no comment heading.
        assertFalse(page.contains("Comments"))
    }

    @Test fun `comments follow under a heading and a note, their labels unselectable, replies indented`() {
        val page = html(
            RedditPageText(
                "Pie", emptyList(),
                listOf(RedditPageComment("baker", 0, listOf("Use 4.")), RedditPageComment("op", 1, listOf("Thanks!")))
            )
        )
        assertTrue(page.contains("<h2 class=\"label\">Comments</h2><p class=\"label note\">Only loaded ones.</p>"))
        assertTrue(page.contains("<section style=\"margin-left:0px\"><p class=\"label author\">u/baker</p><p>Use 4.</p></section>"))
        assertTrue(page.contains("<section style=\"margin-left:12px\"><p class=\"label author\">u/op</p><p>Thanks!</p></section>"))
        assertTrue(page.contains(".label{-webkit-user-select:none;user-select:none}"))
    }

    @Test fun `what the post says is text, never markup`() {
        val page = html(RedditPageText("<b>Pie</b> & \"more\"", listOf("<script>alert(1)</script>"), emptyList()))
        assertTrue(page.contains("<h1>&lt;b&gt;Pie&lt;/b&gt; &amp; &quot;more&quot;</h1>"))
        assertFalse(page.contains("<script>"))
    }
}
