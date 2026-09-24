package com.example.recipeclipper.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeRecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * History: every recipe opened, newest first, searchable, and deleted by a swipe with an undo
 * snackbar. A burst of swipes shares one snackbar, and its Undo restores the whole burst.
 *
 * [FakeRecipeRepository] hands back whatever [FakeRecipeRepository.history] holds whatever the
 * query, so the search tests prove what reaches the repository and what the screen says about
 * the answer; the matching itself is SQL, covered by `RecipeDaoTest`.
 */
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()

    private val adobo = summary(1, "Chicken Adobo")
    private val carbonara = summary(2, "Spaghetti Carbonara")
    private val bread = summary(3, "Banana Bread")

    private fun summary(id: Long, title: String) =
        RecipeSummary(id, title, imageUrl = null, totalTime = null, lastViewedAt = 0, isSaved = false)

    private fun captured(recipe: RecipeSummary) = RecipeRepository.DeletedRecipe(
        RecipeEntity(
            id = recipe.id,
            sourceUrl = "https://example.com/${recipe.id}",
            title = recipe.title,
            imageUrl = null,
            ingredients = emptyList(),
            instructions = emptyList(),
            prepTime = null,
            cookTime = null,
            totalTime = null,
            servings = null,
            sourceType = "BLOG",
            lastViewedAt = 0
        ),
        crossRefs = emptyList()
    )

    private var opened: Long? = null

    private fun show(vararg recipes: RecipeSummary) {
        repository.history.value = recipes.toList()
        recipes.forEach { repository.deleteResults[it.id] = captured(it) }
        val viewModel = HistoryViewModel(repository)
        compose.setContent {
            HistoryScreen(onBack = {}, onOpenRecipe = { opened = it }, viewModel = viewModel)
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Swipes a row away and waits for the repository to see it, as the database would. */
    private fun swipeAway(recipe: RecipeSummary, left: Boolean = true) {
        compose.onNodeWithText(recipe.title).performTouchInput { if (left) swipeLeft() else swipeRight() }
        compose.waitUntil(timeoutMillis = 5_000) { recipe.id in repository.deleteCalls }
        // The real history query stops returning a deleted row; the fake needs telling.
        repository.history.value = repository.history.value - recipe
    }

    // --- Listing ---

    @Test
    fun everyRecipeIsListedAndOpensOnTap() {
        show(adobo, carbonara)

        waitFor("Chicken Adobo")
        compose.onNodeWithText("Spaghetti Carbonara").assertIsDisplayed()
        compose.onNodeWithText("Chicken Adobo").performClick()

        assertEquals(1L, opened)
    }

    @Test
    fun anEmptyHistorySaysSo() {
        show()

        waitFor("Nothing yet. Recipes you open are kept here automatically.")
    }

    // --- Search ---

    @Test
    fun typingSearchesTheRepositoryWithTheQuery() {
        show(adobo)
        waitFor("Chicken Adobo")

        compose.onNode(hasSetTextAction()).performTextInput("soy")

        // Debounced by 250 ms of real time, so wait rather than assert straight away.
        compose.waitUntil(timeoutMillis = 5_000) { repository.historyQueries.lastOrNull() == "soy" }
    }

    @Test
    fun aSearchThatMatchesNothingNamesTheQuery() {
        show(adobo)
        waitFor("Chicken Adobo")

        repository.history.value = emptyList()
        compose.onNode(hasSetTextAction()).performTextInput("zzz")

        waitFor("No recipes match \"zzz\".")
    }

    @Test
    fun clearingTheSearchEmptiesTheFieldAndSearchesEverything() {
        show(adobo)
        waitFor("Chicken Adobo")
        compose.onNode(hasSetTextAction()).performTextInput("soy")
        compose.waitUntil(timeoutMillis = 5_000) { repository.historyQueries.lastOrNull() == "soy" }

        compose.onNodeWithContentDescription("Clear search").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { repository.historyQueries.lastOrNull() == "" }
        compose.onNodeWithContentDescription("Clear search").assertDoesNotExist()
    }

    // --- Swipe to delete, and undo ---

    @Test
    fun swipingARowDeletesItAndOffersUndoNamingIt() {
        show(adobo, carbonara)
        waitFor("Chicken Adobo")

        swipeAway(adobo)

        waitFor("Deleted \"Chicken Adobo\"")
        compose.onNodeWithText("Undo").assertIsDisplayed()
        compose.onNodeWithText("Chicken Adobo").assertDoesNotExist()
    }

    /** Either direction deletes: the row has no other swipe action. */
    @Test
    fun swipingRightDeletesToo() {
        show(adobo)
        waitFor("Chicken Adobo")

        swipeAway(adobo, left = false)

        waitFor("Deleted \"Chicken Adobo\"")
    }

    @Test
    fun undoRestoresTheDeletedRecipe() {
        show(adobo, carbonara)
        waitFor("Chicken Adobo")
        swipeAway(adobo)
        waitFor("Undo")

        compose.onNodeWithText("Undo").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { repository.restoreCalls.isNotEmpty() }
        assertEquals(listOf(captured(adobo)), repository.restoreCalls)
        compose.onNodeWithText("Undo").assertDoesNotExist()
    }

    /** A burst of swipes shares one snackbar, which counts them instead of naming one. */
    @Test
    fun aBurstOfSwipesSharesOneSnackbar() {
        show(adobo, carbonara, bread)
        waitFor("Chicken Adobo")

        swipeAway(adobo)
        waitFor("Deleted \"Chicken Adobo\"")
        swipeAway(carbonara)

        waitFor("2 recipes deleted")
        compose.onNodeWithText("Deleted \"Chicken Adobo\"").assertDoesNotExist()
        assertEquals(1, compose.onAllNodesWithText("Undo").fetchSemanticsNodes().size)
    }

    /** All or nothing: one Undo restores the whole burst, oldest first. */
    @Test
    fun undoAfterABurstRestoresEveryRecipeInIt() {
        show(adobo, carbonara, bread)
        waitFor("Chicken Adobo")
        swipeAway(adobo)
        waitFor("Deleted \"Chicken Adobo\"")
        swipeAway(carbonara)
        waitFor("2 recipes deleted")

        compose.onNodeWithText("Undo").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { repository.restoreCalls.size == 2 }
        assertEquals(listOf(captured(adobo), captured(carbonara)), repository.restoreCalls)
        assertTrue(bread.id !in repository.deleteCalls)
    }
}
