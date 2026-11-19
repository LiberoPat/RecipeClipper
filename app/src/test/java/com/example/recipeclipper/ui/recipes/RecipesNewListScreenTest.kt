package com.example.recipeclipper.ui.recipes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #371: with one cookbook, the Recipes strip ends in a "New list" card that makes an empty list
 * as the Lists screen's New list does (its own class: a dialog does not settle under
 * `RecipesOneCookbookScreenTest`'s tall window).
 */
@RunWith(AndroidJUnit4::class)
class RecipesNewListScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()
    private val lists = FakeListRepository()

    init {
        repository.history.value = listOf(RecipeSummary(1, "Chicken Adobo", null, null, 0, isSaved = true))
        lists.lists.value = listOf(RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0))
    }

    private fun show(oneCookbook: Boolean = true) {
        val viewModel = RecipesViewModel(repository, FakeAppPreferences(), lists = lists, savedStateHandle = SavedStateHandle())
        compose.setContent {
            RecipesScreen(
                onBack = {}, onOpenRecipe = {}, viewModel = viewModel,
                oneCookbook = oneCookbook, saveViewModel = null, scanEnabled = false
            )
        }
    }

    @Test
    fun theNewListCardMakesAnEmptyList() {
        show()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Chicken Adobo").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("recipesViewsStrip").performScrollToNode(hasTestTag("recipesNewList"))
        compose.onNodeWithTag("recipesNewList").performClick()

        compose.onNodeWithText("List name").assertIsDisplayed()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNodeWithText("List name").performTextInput("  Weeknights ")
        compose.onNodeWithText("Create").performClick()

        compose.waitForIdle()
        assertEquals(listOf<Pair<String, Long?>>("Weeknights" to null), lists.createCalls)
        compose.onAllNodesWithText("List name").assertCountEquals(0)
    }

    @Test
    fun cancellingMakesNoList() {
        show()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Chicken Adobo").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("recipesViewsStrip").performScrollToNode(hasTestTag("recipesNewList"))
        compose.onNodeWithTag("recipesNewList").performClick()
        compose.onNodeWithText("Cancel").performClick()

        compose.waitForIdle()
        assertEquals(emptyList<Pair<String, Long?>>(), lists.createCalls)
        compose.onAllNodesWithText("List name").assertCountEquals(0)
    }

    @Test
    fun withTheFlagOffThereIsNoNewListCard() {
        show(oneCookbook = false)
        compose.onAllNodesWithText("New list").assertCountEquals(0)
    }
}
