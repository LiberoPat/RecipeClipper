package com.example.recipeclipper.ui.recipes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Saving a recipe turns its outlined bookmark into a filled one, and the glyph must not move. The
 * outlined one is a 48dp button, so the filled one takes the same slot, and the title's column keeps
 * its width. (Owner's report: "the bookmark moves to the right when it turns red".)
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1600dp")
class BookmarkSlotTest {

    @get:Rule
    val compose = createComposeRule()

    private val unsaved = RecipeSummary(1, "Unsaved Adobo", null, "45m", 0, isSaved = false)
    private val saved = RecipeSummary(2, "Saved Adobo", null, "45m", 0, isSaved = true)

    /** The glyph itself (not the button around it), whichever of the two is showing. */
    private val glyph: SemanticsMatcher =
        hasContentDescription("Saved to a list") or hasContentDescription("Save to a list")

    @Test
    fun a_row_keeps_its_bookmark_and_its_text_where_they_were_when_the_recipe_is_saved() {
        compose.setContent {
            RecipeClipperTheme {
                Column(Modifier.fillMaxWidth()) {
                    LibraryRow(unsaved, onClick = {}, onSaveToList = {})
                    LibraryRow(saved, onClick = {}, onSaveToList = {})
                }
            }
        }
        val glyphs = compose.onAllNodes(glyph, useUnmergedTree = true)
        glyphs.assertCountEquals(2)
        val outlined = glyphs[0].getUnclippedBoundsInRoot()
        val filled = glyphs[1].getUnclippedBoundsInRoot()
        assertEquals("left edge", outlined.left, filled.left)
        assertEquals("width", outlined.width, filled.width)
        // The title's column is as wide beside either glyph.
        val titles = compose.onAllNodes(hasText("Unsaved Adobo") or hasText("Saved Adobo"))
        titles.assertCountEquals(2)
        assertEquals(titles[0].getUnclippedBoundsInRoot().right, titles[1].getUnclippedBoundsInRoot().right)
    }

    @Test
    fun a_saved_row_keeps_its_bookmark_while_selecting() {
        // Selecting hides the outlined button but the filled glyph keeps its slot, so ticking
        // never nudges it.
        compose.setContent {
            RecipeClipperTheme {
                Column(Modifier.fillMaxWidth()) {
                    LibraryRow(saved, onClick = {}, onSaveToList = {})
                    LibraryRow(saved.copy(id = 3, title = "Saved Again"), onClick = {}, onSaveToList = {}, selecting = true)
                }
            }
        }
        val glyphs = compose.onAllNodes(hasContentDescription("Saved to a list"), useUnmergedTree = true)
        glyphs.assertCountEquals(2)
        assertEquals(glyphs[0].getUnclippedBoundsInRoot().left, glyphs[1].getUnclippedBoundsInRoot().left)
    }

    @Test
    fun a_grid_cell_keeps_its_bookmark_where_it_was_when_the_recipe_is_saved() {
        compose.setContent {
            RecipeClipperTheme {
                Row(Modifier.fillMaxWidth()) {
                    LibraryGridCell(unsaved, onClick = {}, onSaveToList = {}, modifier = Modifier.width(180.dp))
                    LibraryGridCell(saved, onClick = {}, onSaveToList = {}, modifier = Modifier.width(180.dp))
                }
            }
        }
        val glyphs = compose.onAllNodes(glyph, useUnmergedTree = true)
        glyphs.assertCountEquals(2)
        val outlined = glyphs[0].getUnclippedBoundsInRoot()
        val filled = glyphs[1].getUnclippedBoundsInRoot()
        // Both cells are 180dp wide: compare each glyph's distance from its own cell's trailing edge.
        assertEquals("from the right", 180.dp - outlined.right, 360.dp - filled.right)
        assertEquals("top", outlined.top, filled.top)
        assertEquals("width", outlined.width, filled.width)
    }
}
