package com.example.recipeclipper.ui.clip

import com.example.recipeclipper.data.remote.RedditPageComment
import com.example.recipeclipper.data.remote.RedditPageText
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Text view's rows (#286). iOS's RedditPageTextTests checks the same. */
class ClipTextRowTest {

    @Test fun `the post is a line a row, the title first, with no comments heading without comments`() {
        assertEquals(
            listOf(
                ClipTextRow.Line(0, "Pie", title = true),
                ClipTextRow.Line(1, "3 apples"),
                ClipTextRow.Line(2, "Bake.")
            ),
            ClipTextRow.of(RedditPageText("Pie", listOf("3 apples", "Bake."), emptyList()))
        )
        // No title, no title row: the body's first line is line 0.
        assertEquals(listOf(ClipTextRow.Line(0, "Bake.")), ClipTextRow.of(RedditPageText(" ", listOf("Bake."), emptyList())))
    }

    @Test fun `comments follow under the heading, each under its author, lines numbered on, replies indented to six`() {
        val rows = ClipTextRow.of(
            RedditPageText(
                "", listOf("Pie"),
                listOf(RedditPageComment("baker", 0, listOf("Use 4.", "Or 5.")), RedditPageComment("op", 9, listOf("Thanks!")))
            )
        )
        assertEquals(
            listOf(
                ClipTextRow.Line(0, "Pie"),
                ClipTextRow.Comments,
                ClipTextRow.Author(0, "baker", 0),
                ClipTextRow.Line(1, "Use 4.", depth = 0),
                ClipTextRow.Line(2, "Or 5.", depth = 0),
                ClipTextRow.Author(1, "op", 6),
                ClipTextRow.Line(3, "Thanks!", depth = 6)
            ),
            rows
        )
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
