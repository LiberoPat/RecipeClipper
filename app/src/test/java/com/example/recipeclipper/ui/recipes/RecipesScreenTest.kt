package com.example.recipeclipper.ui.recipes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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
import com.example.recipeclipper.passTheSearchDebounce
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
class RecipesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()

    private val adobo = summary(1, "Chicken Adobo")
    private val carbonara = summary(2, "Spaghetti Carbonara")
    private val bread = summary(3, "Banana Bread")

    private fun summary(id: Long, title: String) =
        RecipeSummary(id, title, imageUrl = null, totalTime = null, lastViewedAt = 0, isSaved = false)

    // RecipeEntity's uid defaults to a fresh random one per construction, so the same recipe
    // must always map to the same captured instance: cached by id rather than rebuilt on every
    // call, or the entity built for the fake's delete result would never equal the one built for
    // an assertion.
    private val capturedById = mutableMapOf<Long, RecipeRepository.DeletedRecipe>()

    private fun captured(recipe: RecipeSummary) = capturedById.getOrPut(recipe.id) {
        RecipeRepository.DeletedRecipe(
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
    }

    private var opened: Long? = null
    private var newRecipe = 0
    private var openedUrl: String? = null

    private fun show(vararg recipes: RecipeSummary) {
        repository.history.value = recipes.toList()
        recipes.forEach { repository.deleteResults[it.id] = captured(it) }
        val viewModel = RecipesViewModel(repository)
        compose.setContent {
            RecipesScreen(
                onBack = {},
                onOpenRecipe = { opened = it },
                onNewRecipe = { newRecipe++ },
                onOpenUrl = { openedUrl = it },
                viewModel = viewModel
            )
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
    fun aClippedRecipeSaysSoAndOthersDoNot() {
        show(adobo.copy(isClipped = true), carbonara)

        waitFor("Chicken Adobo")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("Clipped by you", substring = true)).fetchSemanticsNodes().size == 1
        }
    }

    @Test
    fun anEmptyLibrarySaysSo() {
        show()

        waitFor("Nothing yet. Recipes you open are kept here, and + adds one by hand or from a link.")
    }

    // --- The + menu (#102) ---

    @Test
    fun plusTypeARecipeOpensTheEditor() {
        show(adobo)

        compose.onNodeWithContentDescription("Add a recipe").performClick()
        compose.onNodeWithText("Type a recipe").performClick()

        assertEquals(1, newRecipe)
    }

    @Test
    fun plusPasteALinkOpensItOnlyOnceItIsALink() {
        show(adobo)

        compose.onNodeWithContentDescription("Add a recipe").performClick()
        compose.onNodeWithText("Paste a link").performClick()
        compose.onNodeWithText("Go").assertIsNotEnabled()

        val field = compose.onNode(hasSetTextAction() and hasText("Recipe URL"))
        field.performTextInput("not a link")
        compose.onNodeWithText("Go").assertIsNotEnabled()
        field.performTextClearance()
        field.performTextInput("seriouseats.com/adobo")
        compose.onNodeWithText("Go").assertIsEnabled().performClick()

        assertEquals("https://seriouseats.com/adobo", openedUrl)
        compose.onNodeWithText("Paste a link").assertDoesNotExist()
    }

    // --- Sort ---

    @Test
    fun sortingByNameReordersTheRows() {
        show(carbonara, adobo) // carbonara was viewed last, so it leads

        waitFor("Chicken Adobo")
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Name").performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText("Chicken Adobo") or hasText("Spaghetti Carbonara"))
                .fetchSemanticsNodes().map { it.boundsInRoot.top }.let { it.size == 2 && it[0] < it[1] }
        }
    }

    // --- Search ---

    @Test
    fun typingSearchesTheRepositoryWithTheQuery() {
        show(adobo)
        waitFor("Chicken Adobo")

        compose.onNode(hasSetTextAction()).performTextInput("soy")

        passTheSearchDebounce()
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
        passTheSearchDebounce()
        compose.waitUntil(timeoutMillis = 5_000) { repository.historyQueries.lastOrNull() == "soy" }

        compose.onNodeWithContentDescription("Clear search").performClick()
        passTheSearchDebounce()

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
