package com.example.recipeclipper.ui.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * One cookbook (#345, V4): under search a strip of cards, Lists first (#371) and then the views
 * (Not in a list, Cooked, Typed by you), each with its count; a view opens as a screen of its own; a recipe in no
 * list has an outlined bookmark that opens the save-to-list sheet. With the flag off, the
 * filter pills of step 1 stand and none of this shows.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1600dp")
class RecipesOneCookbookScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()
    private val lists = FakeListRepository()

    private val adobo = RecipeSummary(1, "Chicken Adobo", null, null, 0, isSaved = true)
    private val carbonara = RecipeSummary(2, "Spaghetti Carbonara", null, null, 0, isSaved = false, lastCookedDay = 20_000)
    private val bread = RecipeSummary(3, "Banana Bread", null, null, 0, isSaved = false, sourceUrl = "manual:abc")

    private var openedView: RecipeFilter? = null
    private var openedLists = 0

    init {
        repository.history.value = listOf(adobo, carbonara, bread)
        lists.lists.value = listOf(
            RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0),
            RecipeList(2, "Dinner", isBuiltIn = true, isFavorites = false, recipeCount = 0)
        )
        lists.membership.value = setOf(1L to 1L)
    }

    /** The list is the repository's answer behind the search's debounce: wait for it, as RecipesScreenTest does. */
    private fun waitForText(text: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun show(oneCookbook: Boolean = true, view: RecipeFilter? = null, save: SaveToListViewModel? = null) {
        val handle = SavedStateHandle(if (view != null) mapOf(RecipesViewModel.VIEW_ARG to view.name) else emptyMap())
        val viewModel = RecipesViewModel(repository, FakeAppPreferences(), lists = lists, savedStateHandle = handle)
        compose.setContent {
            RecipesScreen(
                onBack = {},
                onOpenRecipe = {},
                onOpenView = { openedView = it },
                onOpenLists = { openedLists++ },
                viewModel = viewModel,
                oneCookbook = oneCookbook,
                saveViewModel = save,
                scanEnabled = false
            )
        }
    }

    @Test
    fun theStripHoldsTheListsCardThenTheViewsWithTheirCounts() {
        show()
        waitForText("Banana Bread")
        compose.onNodeWithTag("recipesViewsStrip").assertIsDisplayed()
        // Lists first, with how many lists there are (#371); no strip of the lists themselves.
        compose.onNodeWithTag("recipesLists").assertIsDisplayed()
        compose.onNodeWithText("Lists").assertIsDisplayed()
        compose.onAllNodesWithTag("recipesList-1").assertCountEquals(0)
        compose.onAllNodesWithTag("recipesListsStrip").assertCountEquals(0)
        // Not in a list 2, Cooked 1, Typed by you 1: counted from what is stored.
        compose.onNodeWithTag("recipesView-NOT_IN_A_LIST").assertIsDisplayed()
        compose.onNodeWithTag("recipesView-COOKED").assertIsDisplayed()
        compose.onNodeWithTag("recipesView-TYPED").assertIsDisplayed()
        compose.onNodeWithText("Not in a list").assertIsDisplayed()
        compose.onNodeWithText("Typed by you").assertIsDisplayed()
    }

    /** #371: the Lists card opens the Lists screen (Back returns here). */
    @Test
    fun theListsCardOpensTheListsScreen() {
        show()
        waitForText("Banana Bread")
        compose.onNodeWithTag("recipesLists").performClick()

        assertEquals(1, openedLists)
    }

    @Test
    fun theStripReplacesTheFilterPills() {
        show()
        waitForText("Banana Bread")
        compose.onAllNodesWithTag("recipeFilter-ALL").assertCountEquals(0)
        compose.onAllNodesWithTag("recipeFilter-IN_A_LIST").assertCountEquals(0)
    }

    @Test
    fun withTheFlagOffThePillsStandAndThereIsNoStripNorBookmark() {
        show(oneCookbook = false)
        waitForText("Banana Bread")
        compose.onAllNodesWithTag("recipesViewsStrip").assertCountEquals(0)
        compose.onNodeWithTag("recipeFilter-ALL").assertIsDisplayed()
        compose.onAllNodesWithTag("saveToList-2").assertCountEquals(0)
    }

    @Test
    fun aViewOpensItsScreen() {
        show()
        waitForText("Banana Bread")
        compose.onNodeWithTag("recipesView-COOKED").performClick()
        assertEquals(RecipeFilter.COOKED, openedView)
    }

    @Test
    fun insideAViewTheTitleAndNoteSayWhatItHoldsAndThereIsNoStrip() {
        show(view = RecipeFilter.NOT_IN_A_LIST)
        waitForText("Banana Bread")
        compose.onAllNodesWithTag("recipesViewsStrip").assertCountEquals(0)
        compose.onNodeWithTag("recipesViewNote").assertIsDisplayed()
        compose.onNodeWithText("Not in a list").assertIsDisplayed()
        // The view asked the repository for its own recipes, search still applying.
        assertEquals(RecipeFilter.NOT_IN_A_LIST, repository.historyFilters.last())
        compose.onNodeWithText("Spaghetti Carbonara").assertIsDisplayed()
        compose.onAllNodesWithTag("saveToList-1").assertCountEquals(0)
    }

    @Test
    fun aRecipeInNoListHasAnOutlinedBookmarkThatOpensTheSheet() {
        val save = SaveToListViewModel(lists)
        show(save = save)
        waitForText("Banana Bread")
        // The filled bookmark of a listed recipe is not a button; the others have an outlined one.
        compose.onAllNodesWithTag("saveToList-1").assertCountEquals(0)
        compose.onNodeWithTag("saveToList-2").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Save to").assertIsDisplayed()
        compose.onNodeWithText("Dinner").assertIsDisplayed()
        compose.onNodeWithText("Dinner").performClick()
        compose.waitForIdle()
        // One tick writes at once, as in the reading view.
        assertEquals(setOf(1L to 1L, 2L to 2L), lists.membership.value)
    }

    @Test
    fun anEmptyCookedViewSaysSo() {
        repository.history.value = listOf(adobo)
        show(view = RecipeFilter.COOKED)
        waitForText("Nothing cooked yet. Mark a recipe as cooked and it shows up here.")
        compose.onNodeWithText("Nothing cooked yet. Mark a recipe as cooked and it shows up here.").assertIsDisplayed()
    }
}
