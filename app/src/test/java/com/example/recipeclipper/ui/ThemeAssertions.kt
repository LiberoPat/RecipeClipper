package com.example.recipeclipper.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals

/** The theme's `primary` in light and dark alike: what a TextButton's label is drawn in. */
private val Paprika = Color(0xFFBF4A2B)

/**
 * Asserts this TextButton label (an unmerged Text node) is paprika, the app's primary. A dialog,
 * sheet or menu composed outside its screen's `RecipeClipperTheme` gets Material's baseline scheme
 * instead, and the label comes out purple (#6750A4): #142 and #186. Read from the text's own
 * layout, since colour isn't a semantics property.
 */
fun SemanticsNodeInteraction.assertInAppTheme() {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
    val color = results.firstOrNull()?.layoutInput?.style?.color
        ?: throw AssertionError("No text layout on this node; is it an unmerged Text?")
    assertEquals("Not the app's theme: Material's baseline purple is #FF6750A4", Paprika, color)
}
