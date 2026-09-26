package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.fake.FakeStepShortener
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chef mode (#100) on the recipe screen (Robolectric): the reading view shows a step's short
 * version and a tap shows it as written; in cook mode, where a tap makes a step current, the
 * current card's small "As written" button does it.
 */
@RunWith(AndroidJUnit4::class)
class ChefModeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val oven = "Preheat the oven to 350°F and butter a 9-inch round cake tin."
    private val shortOven = "Preheat oven to 350°F; butter a 9-inch tin."
    private val fixture = RecipeScreenFixture(
        recipe = RecipeScreenFixture.testRecipe(instructions = listOf(oven, "Serve.")),
        chef = FakeStepShortener(written = mutableMapOf(oven to shortOven))
    )

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun readingViewShowsTheShortStepAndATapShowsItAsWritten() {
        fixture.show(compose)
        scrollTo(shortOven)
        compose.onNodeWithText(shortOven).assertIsDisplayed().performClick()
        compose.onNodeWithText(oven).assertIsDisplayed().performClick()
        compose.onNodeWithText(shortOven).assertIsDisplayed()
    }

    @Test
    fun cookModeHasAnAsWrittenButtonOnTheCurrentStep() {
        fixture.show(compose)
        compose.onNodeWithText("Start cooking").performClick()
        compose.onNodeWithText(shortOven).assertIsDisplayed()

        compose.onNodeWithText("As written").performClick()
        compose.onNodeWithText(oven).assertIsDisplayed()
        compose.onNodeWithText("Short version").performClick()
        compose.onNodeWithText(shortOven).assertIsDisplayed()
    }
}
