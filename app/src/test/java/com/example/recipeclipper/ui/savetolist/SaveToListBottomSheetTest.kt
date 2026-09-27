package com.example.recipeclipper.ui.savetolist

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * The sheet is where list membership is actually edited, and its defining behaviour is that a
 * tap writes immediately — there is no Save button to press, so "did it stick?" can only be
 * answered by looking at what the tap did. These tests drive a real [SaveToListViewModel] over
 * [FakeListRepository], whose membership is real state, so a tick genuinely round-trips.
 */
@RunWith(AndroidJUnit4::class)
class SaveToListBottomSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeListRepository().apply {
        lists.value = listOf(
            RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0),
            RecipeList(2, "Lunch", isBuiltIn = true, isFavorites = false, recipeCount = 0)
        )
    }

    private val recipeId = 7L

    private fun show(): SaveToListViewModel {
        val viewModel = SaveToListViewModel(repository)
        viewModel.setRecipe(recipeId)
        compose.setContent { SaveToListBottomSheet(viewModel, onDismiss = {}) }
        // ModalBottomSheet slides in, and until it settles its lower rows are composed but not
        // placed on screen — the top list rows were tappable while "+ New list" at the bottom
        // was not, and which of those two a test saw varied between runs. Waiting on the
        // bottom-most control is waiting for the whole sheet to be real.
        return viewModel
    }

    @Test
    fun everyListIsShown() {
        show()

        compose.onNodeWithText("Save to").assertIsDisplayed()
        compose.onNodeWithText("Favorites").assertIsDisplayed()
        compose.onNodeWithText("Lunch").assertIsDisplayed()
    }

    /** Both lists are empty here, so the label appears once per row — hence onAllNodes. */
    @Test
    fun anEmptyListSaysEmptyRatherThanZeroRecipes() {
        show()

        compose.onAllNodesWithText("Empty").assertCountEquals(2)
    }

    @Test
    fun nothingIsTickedForARecipeInNoList() {
        show()

        compose.onNodeWithText("Favorites").assertIsOff()
        compose.onNodeWithText("Lunch").assertIsOff()
    }

    @Test
    fun tickingWritesStraightThroughAndTheBoxStaysTicked() {
        show()

        compose.onNodeWithText("Lunch").performClick()

        // The write happened with no Save button involved...
        assertEquals(setOf(recipeId to 2L), repository.membership.value)
        // ...and the sheet reflects it, which is the round trip that matters.
        compose.onNodeWithText("Lunch").assertIsOn()
        compose.onNodeWithText("Favorites").assertIsOff()
    }

    @Test
    fun tickingAgainUnticksAndRemovesTheMembership() {
        show()

        compose.onNodeWithText("Lunch").performClick()
        compose.onNodeWithText("Lunch").performClick()

        assertTrue(repository.membership.value.isEmpty())
        compose.onNodeWithText("Lunch").assertIsOff()
    }

    @Test
    fun aRecipeAlreadyInAListOpensTicked() {
        repository.membership.value = setOf(recipeId to 1L)
        show()

        compose.onNodeWithText("Favorites").assertIsOn()
        compose.onNodeWithText("Lunch").assertIsOff()
    }

    @Test
    fun theCountFollowsTheTick() {
        show()

        compose.onNodeWithText("Lunch").performClick()

        compose.onNodeWithText("1 recipe").assertIsDisplayed()
    }

    @Test
    fun newListExpandsInlineRatherThanOpeningADialog() {
        show()

        compose.onNodeWithText(NEW_LIST).performClick()

        compose.onNodeWithText("List name").assertIsDisplayed()
        compose.onNodeWithText("Create").assertIsDisplayed()
    }

    @Test
    fun createIsRefusedUntilSomethingIsTyped() {
        show()

        compose.onNodeWithText(NEW_LIST).performClick()

        compose.onNodeWithText("Create").assertIsNotEnabled()
    }

    @Test
    fun creatingAListPutsTheCurrentRecipeInIt() {
        show()

        compose.onNodeWithText(NEW_LIST).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Weeknights")
        compose.onNodeWithText("Create").performClick()

        assertEquals(listOf("Weeknights" to recipeId), repository.createCalls)
        compose.onNodeWithText("Weeknights").assertIsOn()
    }

    @Test
    fun creatingClosesTheFieldAgain() {
        show()

        compose.onNodeWithText(NEW_LIST).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Weeknights")
        compose.onNodeWithText("Create").performClick()

        compose.onNodeWithText("List name").assertDoesNotExist()
        compose.onNodeWithText(NEW_LIST).assertIsDisplayed()
    }

    @Test
    fun cancellingCreatesNothing() {
        show()

        compose.onNodeWithText(NEW_LIST).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Weeknights")
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(repository.createCalls.isEmpty())
        compose.onNodeWithText("Weeknights").assertDoesNotExist()
    }

    private companion object {
        /**
         * ONE space after the "+", though `action_new_list` is written with two. Android
         * collapses runs of whitespace in an unquoted string resource, so the extra space
         * never reaches the screen. Matching on the XML spelling finds nothing.
         */
        const val NEW_LIST = "+ New list"
    }
}
