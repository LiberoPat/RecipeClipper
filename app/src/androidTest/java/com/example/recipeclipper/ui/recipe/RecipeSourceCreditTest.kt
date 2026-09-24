package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reading view credits the site a recipe came from, under the title, with a way back to
 * the original page. Drives the real [RecipeScreen] and [RecipeViewModel] over
 * [FakeRecipeRepository]. The browser itself isn't opened here: that would leave the app.
 */
@RunWith(AndroidJUnit4::class)
class RecipeSourceCreditTest {

    @get:Rule
    val compose = createComposeRule()

    private val recipe = Recipe(
        name = "Tomato Soup",
        image = null,
        ingredients = listOf("2 cups tomatoes"),
        instructions = listOf("Simmer."),
        prepTime = null,
        cookTime = null,
        totalTime = null,
        yield = "4 servings",
        sourceUrl = "https://www.smittenkitchen.com/2024/01/tomato-soup/",
        id = 1L
    )

    private fun show() {
        val viewModel = RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to recipe.id)),
            FakeRecipeRepository().apply { openResult = recipe },
            FakeAppPreferences(),
            Clock { System.currentTimeMillis() },
            FakeConnectivity(),
            FakeAppInfo()
        )
        val saveViewModel = SaveToListViewModel(FakeListRepository())
        compose.setContent {
            RecipeScreen(
                onBack = {},
                viewModel = viewModel,
                saveViewModel = saveViewModel
            )
        }
        // The recipe loads in a coroutine; wait for the reading view rather than the spinner.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Tomato Soup")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theDomainShowsUnderTheTitleWithoutWww() {
        show()

        compose.onNodeWithText("smittenkitchen.com").assertIsDisplayed()
    }

    @Test
    fun openOriginalIsOfferedAsAnAction() {
        show()

        compose.onNodeWithText("Open original").assertIsDisplayed().assertHasClickAction()
    }

    @Test
    fun cookModeLeavesTheCreditOut() {
        show()

        compose.onNodeWithText("Start cooking").performClick()
        compose.onNodeWithText("smittenkitchen.com").assertDoesNotExist()
        compose.onNodeWithText("Open original").assertDoesNotExist()
    }
}
