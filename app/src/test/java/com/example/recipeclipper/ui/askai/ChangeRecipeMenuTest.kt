package com.example.recipeclipper.ui.askai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.recipe.RecipeScreenFixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Change this recipe" (#353) in the recipe screen's overflow menu, behind the askAi flag. */
@RunWith(AndroidJUnit4::class)
class ChangeRecipeMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private fun askAi() = AskAiViewModel(
        FakePantryRepository(), FakeListRepository(), FakeRecipeRepository(), FakeAppPreferences(), Clock { 0L }
    )

    @Test fun `the menu opens the sheet for the request`() {
        RecipeScreenFixture(askAi = askAi()).show(compose)

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNode(hasText("Change this recipe") and hasText("A prompt for any AI chat")).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("changeRecipe").assertIsDisplayed()
        compose.onNodeWithText("What should change?").assertIsDisplayed()
    }

    @Test fun `without the flag the menu has no Change this recipe`() {
        RecipeScreenFixture().show(compose)

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Change this recipe")).fetchSemanticsNodes().size)
    }
}
