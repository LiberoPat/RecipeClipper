package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Group headings among the ingredients ("Für den Teig:") are small subheadings with no
 * checkbox, in the reading view and in cook mode's ingredients bar. Ticks stay keyed by line
 * index: ticking the line after a heading ticks that line, and a tick stored on a heading is
 * ignored.
 */
@RunWith(AndroidJUnit4::class)
class RecipeHeadingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val lines = listOf("Für den Teig:", "250 g Mehl", "Für die Füllung:", "200 g Quark")

    private fun show(checked: Set<Int> = emptySet()): RecipeScreenFixture {
        val recipe = RecipeScreenFixture.testRecipe().copy(
            ingredients = lines, language = "de", checkedIngredients = checked
        )
        return RecipeScreenFixture(recipe).also { it.show(compose) }
    }

    private val noCheckbox = SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState)
    private val isHeading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test
    fun aHeadingHasNoCheckboxAndTickingTheLineAfterItTicksThatLine() {
        val fixture = show()

        compose.onNodeWithText("Für den Teig:").assertIsDisplayed().assert(noCheckbox).assert(isHeading)
        compose.onNodeWithText("Für die Füllung:").assert(noCheckbox)
        compose.onNodeWithText("250 g Mehl").assertIsOff()

        compose.onNodeWithText("250 g Mehl").performClick()

        compose.onNodeWithText("250 g Mehl").assertIsOn()
        compose.onNodeWithText("200 g Quark").assertIsOff()
        assertEquals(listOf(RecipeScreenFixture.RECIPE_ID to setOf(1)), fixture.recipes.setCheckedCalls)
    }

    @Test
    fun tappingAHeadingTicksNothing() {
        val fixture = show()

        compose.onNodeWithText("Für den Teig:").performClick()

        assertEquals(emptyList<Pair<Long, Set<Int>>>(), fixture.recipes.setCheckedCalls)
    }

    /** Saved before headings lost their box: the tick stays stored, and nothing shows it. */
    @Test
    fun aTickStoredOnAHeadingIsIgnoredAndTheOthersStillShow() {
        show(checked = setOf(0, 3))

        compose.onNodeWithText("Für den Teig:").assert(noCheckbox)
        compose.onNodeWithText("250 g Mehl").assertIsOff()
        compose.onNodeWithText("200 g Quark").assertIsOn()
    }

    @Test
    fun cookModesIngredientsBarCountsOnlyTheLinesAndDrawsHeadingsWithoutABox() {
        show()
        compose.onNodeWithText("Start cooking").performClick()

        // Written "  ·  %1$d" in strings.xml; unquoted resources render single spaces.
        compose.onNodeWithText("· 2", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Ingredients").performClick()

        compose.onNodeWithText("Für die Füllung:").assertIsDisplayed().assert(noCheckbox)
        compose.onNodeWithText("200 g Quark").performClick()
        compose.onNodeWithText("200 g Quark").assertIsOn()
    }
}
