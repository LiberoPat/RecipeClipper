package com.example.recipeclipper.ui.recipes

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import com.example.recipeclipper.data.model.RecipeFilter
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.passTheSearchDebounce
import com.example.recipeclipper.ui.edit.PasteTarget
import com.example.recipeclipper.ui.edit.PastedTextFixtures
import com.example.recipeclipper.ui.edit.PastedTextRouter
import com.example.recipeclipper.ui.assertInAppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Recipes screen: every recipe, newest viewed first, searchable, and deleted by a swipe with an undo
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
    private var openedPasted: PasteTarget? = null

    private val snackbars = SnackbarHostState()

    private fun show(vararg recipes: RecipeSummary, pasted: PastedTextRouter? = null) {
        repository.history.value = recipes.toList()
        recipes.forEach { repository.deleteResults[it.id] = captured(it) }
        val viewModel = RecipesViewModel(repository, FakeAppPreferences(), pasted = pasted)
        compose.setContent {
            RecipesScreen(
                onBack = {},
                onOpenRecipe = { opened = it },
                onNewRecipe = { newRecipe++ },
                onOpenUrl = { openedUrl = it },
                onOpenPasted = { openedPasted = it },
                viewModel = viewModel,
                snackbarHostState = snackbars
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

    /** #286: the site and total time under the name, credited as the reading view credits it. */
    @Test
    fun aRowCreditsItsSiteAndTotalTime() {
        show(
            adobo.copy(sourceUrl = "https://www.sundaysupper.blog/adobo/", totalTime = "1h 15m"),
            carbonara.copy(sourceUrl = "https://cucinafacile.net/carbonara", isClipped = true),
            bread.copy(sourceUrl = "manual:6f1c0d2e-1b7a-4f4e-9a55-0d3c1a2b3c4d", totalTime = "1h")
        )

        waitFor("Chicken Adobo")
        compose.onNodeWithText("sundaysupper.blog").assertIsDisplayed()
        compose.onNodeWithText(" · 1h 15m").assertIsDisplayed()
        compose.onNodeWithText("Clipped by you · cucinafacile.net").assertIsDisplayed()
        // A typed recipe has no site: only its time, and never its manual: key.
        compose.onNodeWithText("1h").assertIsDisplayed()
        compose.onAllNodes(hasText("manual", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("ago", substring = true)).assertCountEquals(0)
    }

    /** #286: a filled bookmark, not the word "Saved", and only on a recipe in a list. */
    @Test
    fun aSavedRecipeShowsTheBookmarkInsteadOfTheWord() {
        show(adobo.copy(isSaved = true), carbonara)

        waitFor("Chicken Adobo")
        compose.onAllNodesWithContentDescription("Saved to a list").assertCountEquals(1)
        compose.onNodeWithText("Saved").assertDoesNotExist()
    }

    /** #286: the count on the left of the line under search, the order on the right. */
    @Test
    fun theLineUnderSearchCountsTheRecipesBesideTheSort() {
        show(adobo, carbonara, bread)

        waitFor("3 recipes")
        compose.onNodeWithContentDescription("Sort").assert(hasText("Recently viewed"))
        compose.onNodeWithContentDescription("Sort").performClick()
        compose.onNodeWithText("Date added").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasContentDescription("Sort") and hasText("Date added"))
                .fetchSemanticsNodes().size == 1
        }
    }

    @Test
    fun anEmptyLibrarySaysSo() {
        show()

        waitFor("Nothing yet. Recipes you open are kept here, and + adds one by hand or from a link.")
    }

    // --- The filter (#345) ---

    private val pillAll get() = compose.onNodeWithTag("recipeFilter-ALL")
    private val pillIn get() = compose.onNodeWithTag("recipeFilter-IN_A_LIST")
    private val pillNot get() = compose.onNodeWithTag("recipeFilter-NOT_IN_A_LIST")

    @Test
    fun theFilterShowsThreeChoicesWithTheirCountsAndAllChosen() {
        show(adobo.copy(isSaved = true), carbonara, bread)

        waitFor("All 3")
        pillAll.assertIsSelected()
        pillIn.assertIsNotSelected().assert(hasText("In a list 1"))
        pillNot.assertIsNotSelected().assert(hasText("Not in a list 2"))
        // The words: no "history" anywhere on the screen.
        compose.onAllNodes(hasText("history", substring = true, ignoreCase = true)).assertCountEquals(0)
    }

    @Test
    fun aChoiceListsOnlyItsRecipesAndTheSearchStaysInsideIt() {
        show(adobo.copy(isSaved = true), carbonara, bread)
        waitFor("All 3")
        waitFor("Spaghetti Carbonara") // the rows come after the search debounce, the counts before

        pillIn.performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Spaghetti Carbonara").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        pillIn.assertIsSelected()
        compose.onNodeWithText("Chicken Adobo").assertIsDisplayed()
        compose.onNodeWithText("Banana Bread").assertDoesNotExist()
        // The counts don't follow the filter.
        pillNot.assert(hasText("Not in a list 2"))

        pillNot.performClick()
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText("Chicken Adobo").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Spaghetti Carbonara").assertIsDisplayed()
        compose.onNodeWithText("Banana Bread").assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performTextInput("bread")
        passTheSearchDebounce()
        assertEquals(RecipeFilter.NOT_IN_A_LIST, repository.historyFilters.last())
        assertEquals("bread", repository.historyQueries.last())
        pillNot.assertIsSelected()
    }

    @Test
    fun anEmptyChoiceSaysWhatItIsAndKeepsTheFilter() {
        show(carbonara, bread)
        waitFor("All 2")
        waitFor("Banana Bread")

        pillIn.performClick()
        waitFor("No recipes are in a list yet. Save one to a list with its bookmark.")
        pillAll.assertIsDisplayed()

        repository.history.value = listOf(adobo.copy(isSaved = true))
        waitFor("All 1")
        pillNot.performClick()
        waitFor("Every recipe is in a list.")
    }

    @Test
    fun theCleanUpNoteIsUnderNotInAListOnly() {
        show(adobo.copy(isSaved = true), carbonara)
        waitFor("All 2")
        waitFor("Chicken Adobo")
        compose.onNodeWithTag("recipesCullNote").assertDoesNotExist()

        pillIn.performClick()
        compose.onNodeWithTag("recipesCullNote").assertDoesNotExist()

        pillNot.performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("recipesCullNote").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            "Recipes in no list are kept up to 50. Past that, the ones you opened longest ago go first. " +
                "Save one to a list to keep it."
        ).assertIsDisplayed()
    }

    @Test
    fun anEmptyLibraryHasNoFilter() {
        show()
        waitFor("Nothing yet. Recipes you open are kept here, and + adds one by hand or from a link.")
        pillAll.assertDoesNotExist()
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
    fun plusPasteALinkImportsALinkAndOpensTheEditorForAnyOtherText() {
        val fixtures = PastedTextFixtures()
        show(adobo, pasted = fixtures.router)

        compose.onNodeWithContentDescription("Add a recipe").performClick()
        compose.onNodeWithText("Paste a link").performClick()
        compose.onNodeWithText("Go").assertIsNotEnabled()

        // #372: any text goes where a share would, here a recipe written out opens the editor.
        val field = compose.onNode(hasSetTextAction() and hasText("Link or recipe text"))
        field.performTextInput(PastedTextFixtures.prose)
        compose.onNodeWithText("Go").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { openedPasted != null }
        assertTrue(openedPasted is PasteTarget.Review)
        assertNull(openedUrl)
        compose.waitForIdle()
        compose.onNodeWithText("Paste a link or recipe").assertDoesNotExist()

        compose.onNodeWithContentDescription("Add a recipe").performClick()
        compose.onNodeWithText("Paste a link").performClick()
        compose.onNode(hasSetTextAction() and hasText("Link or recipe text")).performTextInput("seriouseats.com/adobo")
        compose.onNodeWithText("Go").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { openedUrl != null }
        assertEquals("https://seriouseats.com/adobo", openedUrl)
    }

    /** #186: it was composed outside the screen's theme, so it came up Material purple. */
    @Test
    fun thePasteALinkDialogIsInTheAppsTheme() {
        show(adobo)

        compose.onNodeWithContentDescription("Add a recipe").performClick()
        compose.onNodeWithText("Paste a link").performClick()

        compose.onNodeWithText("Cancel", useUnmergedTree = true).assertInAppTheme()
    }

    // --- Sort ---

    @Test
    fun sortingByNameReordersTheRows() {
        show(carbonara, adobo) // carbonara was viewed last, so it leads

        waitFor("Chicken Adobo")
        // #286: the sort is the quiet control under search, naming the order it is in.
        compose.onNodeWithContentDescription("Sort").assertIsDisplayed().performClick()
        compose.onNodeWithText("Name").performClick()
        compose.onNodeWithContentDescription("More options").assertDoesNotExist()

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

    /** One cookbook (#345): the free library is full, so Undo restores nothing and says so. */
    @Test
    fun anUndoTheFullFreeLibraryRefusesSaysSo() {
        repository.restoreOutcome = RecipeRepository.RestoreOutcome.LIBRARY_FULL
        show(adobo, carbonara)
        waitFor("Chicken Adobo")
        swipeAway(adobo)
        waitFor("Undo")

        compose.onNodeWithText("Undo").performClick()

        waitFor("Couldn't restore: your library is full")
        assertTrue(repository.restoreCalls.isEmpty())
        compose.onNodeWithText("Chicken Adobo").assertDoesNotExist()
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

    /**
     * #268: a burst keeps the one snackbar it started with, counting in place, rather than
     * taking it down and showing another per swipe (which flashed). Undo then hides it for good.
     */
    @Test
    fun aBurstOfThreeKeepsOneSnackbarAndUndoHidesIt() {
        show(adobo, carbonara, bread)
        waitFor("Chicken Adobo")
        swipeAway(adobo)
        waitFor("Deleted \"Chicken Adobo\"")
        val first = snackbars.currentSnackbarData

        swipeAway(carbonara)
        waitFor("2 recipes deleted")
        assertSame(first, snackbars.currentSnackbarData)
        swipeAway(bread)
        waitFor("3 recipes deleted")
        assertSame(first, snackbars.currentSnackbarData)
        assertEquals(1, compose.onAllNodesWithText("Undo").fetchSemanticsNodes().size)

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { repository.restoreCalls.size == 3 }
        repository.history.value = listOf(adobo, carbonara, bread)
        waitFor("Banana Bread")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertNull(snackbars.currentSnackbarData)
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.onNodeWithText("3 recipes deleted").assertDoesNotExist()
    }

    /**
     * #268: the restored rows come back under the same key, and the lazy list used to hand
     * them the swipe state they were saved with, dismissed: the row deleted itself again and
     * the snackbar popped back.
     */
    @Test
    fun undoneRowsComeBackAndAreNotDeletedAgain() {
        show(adobo, carbonara, bread)
        waitFor("Chicken Adobo")
        swipeAway(adobo)
        swipeAway(carbonara)
        waitFor("2 recipes deleted")

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { repository.restoreCalls.size == 2 }
        // The database would list them again; the fake needs telling.
        repository.history.value = listOf(adobo, carbonara, bread)
        waitFor("Chicken Adobo")
        waitFor("Spaghetti Carbonara")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        assertEquals(listOf(adobo.id, carbonara.id), repository.deleteCalls)
        compose.onNodeWithText("Undo").assertDoesNotExist()
        compose.onNodeWithText("Chicken Adobo").assertIsDisplayed()
    }
}
