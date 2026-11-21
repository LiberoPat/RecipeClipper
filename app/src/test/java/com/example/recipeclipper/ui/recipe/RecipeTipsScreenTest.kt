package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A recipe's tips (#432) on the recipe screen: below the method under a quiet "Tips" heading,
 * unnumbered and as written; never in cook mode, whose steps and count leave them out.
 */
@RunWith(AndroidJUnit4::class)
class RecipeTipsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val tip = "Bake on a stone at 350°F for the crispest bottoms."

    private val fixture = RecipeScreenFixture(RecipeScreenFixture.testRecipe().copy(tips = listOf(tip, "Or use a steel.")))

    @Test
    fun theTipsShowBelowTheMethodUnderTheirHeadingAsWritten() {
        fixture.show(compose)

        compose.onNode(hasScrollAction()).performScrollToNode(hasText(tip))
        compose.onNodeWithText("Tips").assertIsDisplayed()
        // As written: the °F is not converted, and no number is put before it.
        compose.onNodeWithText(tip).assertIsDisplayed()
        compose.onNodeWithText("Or use a steel.").assertIsDisplayed()
        compose.onAllNodesWithText("5").assertCountEquals(0)
    }

    @Test
    fun cookModeLeavesTheTipsOut() {
        fixture.show(compose)
        compose.onNodeWithText("Start cooking").performClick()

        compose.onNodeWithText("Step 1 of 4").assertIsDisplayed()
        compose.onAllNodesWithText(tip).assertCountEquals(0)
        compose.onAllNodesWithText("Tips").assertCountEquals(0)
    }
}
