package com.example.recipeclipper.ui.recipes

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.RecipesLayout
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.plan.AddToPlanViewModel
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The photo grid (#380) with the long-press menu (#376) and selecting several (#377): the two
 * landed together, so the grid's cells must do what a row does. A long press opens the same menu,
 * Select ticks cells through the same ViewModel state, and what an action acts on is what the chips
 * leave in sight.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w420dp-h1600dp")
class RecipesGridRowActionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()
    private val preferences = FakeAppPreferences().apply { recipesLayout = RecipesLayout.GRID }
    private val lists = FakeListRepository().apply {
        lists.value = listOf(RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0))
    }
    private val snackbars = SnackbarHostState()

    private fun summary(id: Long, title: String, total: String? = null) =
        RecipeSummary(id, title, null, total, 0, isSaved = false, language = "en")

    private val adobo = summary(1, "Chicken Adobo", total = "45m")
    private val salad = summary(2, "Quick Salad", total = "15m")
    private val bread = summary(3, "Banana Bread", total = "1h 10m")

    // One per recipe: an entity gets a fresh uid each time it is built.
    private val capturedById = mutableMapOf<Long, RecipeRepository.DeletedRecipe>()

    private fun captured(recipe: RecipeSummary) = capturedById.getOrPut(recipe.id) { deleted(recipe) }

    private fun deleted(recipe: RecipeSummary) = RecipeRepository.DeletedRecipe(
        RecipeEntity(
            id = recipe.id, sourceUrl = "https://example.com/${recipe.id}", title = recipe.title, imageUrl = null,
            ingredients = emptyList(), instructions = emptyList(), prepTime = null, cookTime = null,
            totalTime = null, servings = null, sourceType = "BLOG", lastViewedAt = 0
        ),
        crossRefs = emptyList()
    )

    private var opened: Long? = null

    private fun show(rowActions: Boolean = true, multiSelect: Boolean = true) {
        val recipes = listOf(adobo, salad, bread)
        repository.history.value = recipes
        recipes.forEach { repository.deleteResults[it.id] = captured(it) }
        val viewModel = RecipesViewModel(repository, preferences, lists = lists)
        val saveViewModel = SaveToListViewModel(lists)
        val planViewModel = AddToPlanViewModel(FakeMealPlanRepository(), FakePlanCalendar())
        compose.setContent {
            RecipesScreen(
                onBack = {},
                onOpenRecipe = { opened = it },
                viewModel = viewModel,
                rowActions = rowActions,
                multiSelect = multiSelect,
                quickFilters = true,
                gridEnabled = true,
                oneCookbook = false,
                saveViewModel = saveViewModel,
                planViewModel = planViewModel,
                scanEnabled = false,
                snackbarHostState = snackbars
            )
        }
        waitFor("Chicken Adobo")
        compose.onNodeWithTag("recipesGrid").assertIsDisplayed()
    }

    private fun waitFor(text: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitGone(text: String) = compose.waitUntil(timeoutMillis = 5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }

    private fun longPress(title: String) {
        compose.onNodeWithText(title).performTouchInput { longClick() }
    }

    @Test
    fun aLongPressOnACellOpensTheRowMenuAndDoesNotOpenTheRecipe() {
        show()

        longPress("Chicken Adobo")

        compose.onNodeWithTag("rowMenuAddToList").assertIsDisplayed()
        compose.onNodeWithTag("rowMenuAddToPlan").assertIsDisplayed()
        compose.onNodeWithTag("rowMenuShare").assertIsDisplayed()
        compose.onNodeWithTag("rowMenuSelect").assertIsDisplayed()
        compose.onNodeWithTag("rowMenuDelete").assertIsDisplayed()
        assertEquals("a long press does not open the recipe", null, opened)
    }

    @Test
    fun aTapStillOpensTheRecipeFromTheGrid() {
        show()
        compose.onNodeWithText("Quick Salad").performClick()
        assertEquals(2L, opened)
    }

    @Test
    fun withRowActionsOffACellHasNoMenu() {
        show(rowActions = false)
        longPress("Chicken Adobo")
        compose.onAllNodesWithTag("rowMenuShare").assertCountEquals(0)
    }

    @Test
    fun addToListFromACellOpensTheSheetForThatRecipe() {
        show()
        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuAddToList").performClick()

        waitFor("Save to")
        compose.onNodeWithText("Favorites").performClick()
        compose.waitUntil(5_000) { (1L to 1L) in lists.membership.value }
    }

    @Test
    fun selectFromACellTicksItAndATapOnAnotherTicksThatOneTooInTheGrid() {
        show()

        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuSelect").performClick()
        waitFor("1 selected")
        compose.onNodeWithText("Chicken Adobo").assertIsOn()
        compose.onNodeWithText("Quick Salad").assertIsOff()

        compose.onNodeWithText("Quick Salad").performClick()
        waitFor("2 selected")
        compose.onNodeWithText("Quick Salad").assertIsOn()
        assertEquals("a tap while selecting ticks, it does not open", null, opened)
        // Still the grid: selecting never changes the layout.
        compose.onNodeWithTag("recipesGrid").assertIsDisplayed()
        // The long press only ticks while selecting: no menu.
        compose.onAllNodesWithTag("rowMenuShare").assertCountEquals(0)
    }

    @Test
    fun theHeadersSelectWorksInTheGridToo() {
        show()
        compose.onNodeWithTag("selectRecipes").performClick()
        waitFor("0 selected")
        compose.onNodeWithText("Banana Bread").performClick()
        waitFor("1 selected")
        compose.onNodeWithText("Banana Bread").assertIsOn()
    }

    @Test
    fun aSelectionActsOnlyOnWhatTheChipsLeaveInSight() {
        show()
        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuSelect").performClick()
        compose.onNodeWithText("Quick Salad").performClick()
        waitFor("2 selected")

        // "30 min or less" hides Chicken Adobo (45m): it leaves the count and every action.
        compose.onNodeWithTag("recipeQuick-THIRTY_OR_LESS").performClick()
        waitGone("Chicken Adobo")
        waitFor("1 selected")
        compose.onNodeWithTag("deleteSelected").performClick()
        waitFor("Delete \"Quick Salad\"?")
        compose.onNodeWithTag("confirmDeleteRecipes").performClick()

        waitFor("Deleted \"Quick Salad\"")
        assertEquals(listOf(listOf(2L)), repository.deleteAllCalls)
    }

    @Test
    fun deleteFromACellAsksFirstThenOffersOneUndo() {
        show()

        longPress("Banana Bread")
        compose.onNodeWithTag("rowMenuDelete").performClick()
        waitFor("Delete \"Banana Bread\"?")
        compose.onNodeWithTag("confirmDeleteRecipes").performClick()

        waitFor("Deleted \"Banana Bread\"")
        assertEquals(listOf(listOf(3L)), repository.deleteAllCalls)
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { repository.restoreCalls.isNotEmpty() }
        assertEquals(listOf(captured(bread)), repository.restoreCalls)
    }
}
