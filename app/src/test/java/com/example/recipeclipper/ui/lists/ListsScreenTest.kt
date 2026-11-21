package com.example.recipeclipper.ui.lists

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
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

    private fun show(reorderEnabled: Boolean = false) {
        val viewModel = ListsViewModel(repository)
        compose.setContent {
            ListsScreen(
                onBack = {}, onOpenList = { opened = it }, viewModel = viewModel,
                reorderEnabled = reorderEnabled
            )
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

    /** #371: the "+" sits beside the title, above every cover, and there is no tile at the end. */
    @Test
    fun theNewListPlusIsBesideTheTitleNotAtTheEndOfTheGrid() {
        show()

        val plus = compose.onNodeWithContentDescription("New list").getBoundsInRoot()
        val title = compose.onNodeWithText("Lists").getBoundsInRoot()
        val firstCover = compose.onNodeWithTag("lists.cover.1").getBoundsInRoot()
        assertTrue(plus.left > title.right)
        assertTrue(plus.bottom <= firstCover.top)
        compose.onAllNodesWithText("New list").assertCountEquals(0)
    }

    // --- Creating a list ---

    @Test
    fun newListOpensANameFieldWithCreateDisabledUntilThereIsAName() {
        show()

        compose.onNodeWithContentDescription("New list").performClick()

        compose.onNodeWithText("List name").assertIsDisplayed()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Soups")
        compose.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun creatingAListAddsItEmptyAndClosesTheField() {
        show()

        compose.onNodeWithContentDescription("New list").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Soups")
        compose.onNodeWithText("Create").performClick()

        compose.waitUntil(timeoutMillis = 5_000) { repository.createCalls.isNotEmpty() }
        // No recipe in hand on this screen, so nothing is added to the new list.
        assertEquals(listOf("Soups" to null), repository.createCalls)
        compose.onNodeWithText("Soups").assertIsDisplayed()
        compose.onNodeWithText("List name").assertDoesNotExist()
        compose.onNodeWithContentDescription("New list").assertIsDisplayed()
    }

    @Test
    fun cancelClosesTheFieldWithoutCreating() {
        show()

        compose.onNodeWithContentDescription("New list").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Soups")
        compose.onNodeWithText("Cancel").performClick()

        compose.onNodeWithText("List name").assertDoesNotExist()
        compose.onNodeWithText("Soups").assertDoesNotExist()
        assertTrue(repository.createCalls.isEmpty())
    }

    // --- Reordering (#381) ---

    private fun actionLabels(listId: Long): List<String> =
        compose.onNodeWithTag("lists.cover.$listId").fetchSemanticsNode().config.let { config ->
            if (SemanticsActions.CustomActions in config) config[SemanticsActions.CustomActions].map { it.label } else emptyList()
        }

    private fun runAction(listId: Long, label: String) {
        val action = compose.onNodeWithTag("lists.cover.$listId").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
    }

    @Test
    fun coversOfferMoveActionsOnlyWhereTheMoveIsPossible() {
        show(reorderEnabled = true)

        assertEquals(listOf("Move later"), actionLabels(1))
        assertEquals(listOf("Move earlier", "Move later"), actionLabels(2))
        assertEquals(listOf("Move earlier"), actionLabels(3))
    }

    @Test
    fun withReorderingOffThereAreNoMoveActions() {
        show(reorderEnabled = false)

        assertEquals(emptyList<String>(), actionLabels(2))
    }

    @Test
    fun moveLaterStoresTheNewOrderAndTheScreenFollows() {
        show(reorderEnabled = true)

        runAction(1, "Move later")

        assertEquals(listOf(listOf(2L, 1L, 3L)), repository.reorderCalls)
        val first = compose.onNodeWithTag("lists.cover.2").getBoundsInRoot()
        val second = compose.onNodeWithTag("lists.cover.1").getBoundsInRoot()
        assertTrue(first.left < second.left && first.top == second.top)
    }

    @Test
    fun pressingAndHoldingACoverThenDraggingItOverAnotherMovesIt() {
        show(reorderEnabled = true)
        val second = compose.onNodeWithTag("lists.cover.2").getBoundsInRoot()
        val held = compose.onNodeWithTag("lists.cover.1").getBoundsInRoot()

        compose.onNodeWithTag("lists.cover.1").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            val over = (second.left - held.left).toPx()
            moveTo(center + Offset(over / 2, 0f))
            moveTo(center + Offset(over, 0f))
            up()
        }
        compose.waitForIdle()
        // The new order is on show once the stored one arrives, not at the instant of the drop.
        compose.waitUntil(5_000) {
            compose.onNodeWithTag("lists.cover.2").getBoundsInRoot().left <
                compose.onNodeWithTag("lists.cover.1").getBoundsInRoot().left
        }

        assertEquals(listOf(listOf(2L, 1L, 3L)), repository.reorderCalls)
        assertEquals(null, opened) // dropping a drag does not open the list
    }

    @Test
    fun aTapStillOpensTheListWithReorderingOn() {
        show(reorderEnabled = true)

        compose.onNodeWithText("Weeknights").performClick()

        assertEquals(3L, opened)
        assertTrue(repository.reorderCalls.isEmpty())
    }
}
