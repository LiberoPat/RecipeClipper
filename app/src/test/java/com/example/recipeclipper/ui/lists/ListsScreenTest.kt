package com.example.recipeclipper.ui.lists

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.fake.FakeListRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Lists screen only lists and creates: renaming and deleting happen on the list's own
 * screen (`ListDetailScreenTest`). Counts come from [FakeListRepository]'s membership, derived
 * the way the SQL derives them, so they are staged as membership rather than as numbers.
 */
@RunWith(AndroidJUnit4::class)
class ListsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeListRepository().apply {
        lists.value = listOf(
            RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0),
            RecipeList(2, "Dinner", isBuiltIn = true, isFavorites = false, recipeCount = 0),
            RecipeList(3, "Weeknights", isBuiltIn = false, isFavorites = false, recipeCount = 0)
        )
        membership.value = setOf(7L to 1L, 7L to 3L, 8L to 3L)
        coverPhotos.value = mapOf(3L to listOf("https://img/8.jpg", "https://img/7.jpg"))
    }

    private var opened: Long? = null

    private fun show() {
        val viewModel = ListsViewModel(repository)
        compose.setContent {
            ListsScreen(onBack = {}, onOpenList = { opened = it }, viewModel = viewModel)
        }
    }

    @Test
    fun everyListIsShownWithItsCount() {
        show()

        compose.onNodeWithText("Favorites").assertIsDisplayed()
        compose.onNodeWithText("1 recipe").assertIsDisplayed()
        compose.onNodeWithText("Dinner").assertIsDisplayed()
        compose.onNodeWithText("Empty").assertIsDisplayed()
        compose.onNodeWithText("Weeknights").assertIsDisplayed()
        compose.onNodeWithText("2 recipes").assertIsDisplayed()
    }

    @Test
    fun tappingAListOpensIt() {
        show()

        compose.onNodeWithText("Weeknights").performClick()

        assertEquals(3L, opened)
    }

    // --- Covers (#286) ---

    private fun photosOn(listId: Long) = compose.onAllNodes(
        hasTestTag("lists.coverPhoto") and hasAnyAncestor(hasTestTag("lists.cover.$listId")),
        useUnmergedTree = true
    ).fetchSemanticsNodes().size

    private fun plainTile(listId: Long) = compose.onAllNodes(
        hasTestTag("lists.coverPlain") and hasAnyAncestor(hasTestTag("lists.cover.$listId")),
        useUnmergedTree = true
    ).fetchSemanticsNodes().size

    @Test
    fun aCoverShowsItsListsPhotos() {
        repository.coverPhotos.value = mapOf(
            1L to (1..4).map { "https://img/$it.jpg" },
            3L to listOf("https://img/8.jpg", "https://img/7.jpg")
        )
        show()

        assertEquals(4, photosOn(1))
        assertEquals(2, photosOn(3))
        assertEquals(0, plainTile(3))
    }

    /** Dinner is empty and Favorites has a recipe with no photo: both get the plain tile. */
    @Test
    fun aListWithNoPhotoGetsAPlainTile() {
        show()

        assertEquals(0, photosOn(1))
        assertEquals(1, plainTile(1))
        assertEquals(1, plainTile(2))
    }

    /** Three lists fill a row and a half; "New list" takes the place beside the last. */
    @Test
    fun newListIsTheLastTile() {
        show()

        val last = compose.onNodeWithTag("lists.cover.3").getBoundsInRoot()
        val tile = compose.onNodeWithText("New list").getBoundsInRoot()
        assertEquals(last.top, tile.top)
        assertTrue(tile.left > last.left)
    }

    // --- Creating a list ---

    @Test
    fun newListOpensANameFieldWithCreateDisabledUntilThereIsAName() {
        show()

        compose.onNodeWithText("New list").performClick()

        compose.onNodeWithText("List name").assertIsDisplayed()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Soups")
        compose.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun creatingAListAddsItEmptyAndClosesTheField() {
        show()

        compose.onNodeWithText("New list").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Soups")
        compose.onNodeWithText("Create").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { repository.createCalls.isNotEmpty() }
        // No recipe in hand on this screen, so nothing is added to the new list.
        assertEquals(listOf("Soups" to null), repository.createCalls)
        compose.onNodeWithText("Soups").assertIsDisplayed()
        compose.onNodeWithText("List name").assertDoesNotExist()
        compose.onNodeWithText("New list").assertIsDisplayed()
    }

    @Test
    fun cancelClosesTheFieldWithoutCreating() {
        show()

        compose.onNodeWithText("New list").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Soups")
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("List name").assertDoesNotExist()
        compose.onNodeWithText("Soups").assertDoesNotExist()
        assertTrue(repository.createCalls.isEmpty())
    }
}
