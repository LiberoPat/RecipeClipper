package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.fake.FakeDecisionRepository
import com.example.recipeclipper.fake.FakeGroceryRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Junk after an ingredient (#174) on the real [RecipeScreen]: once the model decides "(dfsafs -"
 * is junk, the reading view, cook mode's ingredients bar and "Add to groceries" show "2 eggs".
 */
@RunWith(AndroidJUnit4::class)
class RecipeJunkScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val recipe = RecipeScreenFixture.testRecipe().copy(ingredients = listOf("2 eggs (dfsafs -", "1 cup milk"))

    private fun show(answer: String) = RecipeScreenFixture(
        recipe,
        groceries = FakeGroceryRepository(),
        decisions = FakeDecisionRepository(mapOf(DecisionQuestion.trailingText("(dfsafs -", "en") to answer))
    ).apply { show(compose) }

    @Test
    fun theReadingViewCookModeAndAddToGroceriesShowTheLineWithoutItsJunk() {
        val fixture = show("junk")
        compose.onNodeWithText("2 eggs").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 eggs (dfsafs -").assertDoesNotExist()

        compose.onNodeWithText("Start cooking").performClick()
        compose.onNodeWithText("Ingredients").performClick()
        compose.onNodeWithText("2 eggs", useUnmergedTree = true).assertExists()
        // Written "✕  Exit" in strings.xml; unquoted resources render one space.
        compose.onNodeWithText("✕ Exit").performClick()

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Add to groceries").performClick()
        compose.onNodeWithTag("addToGroceriesButton").performClick()
        compose.runOnIdle {
            assertEquals(listOf("2 eggs", "1 cup milk"), fixture.groceries!!.items.value.map { it.text })
        }
    }

    @Test
    fun unsureKeepsTheLineAsWritten() {
        show("unsure")
        compose.onNodeWithText("2 eggs (dfsafs -").performScrollTo().assertIsDisplayed()
    }
}
