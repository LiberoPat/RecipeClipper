package com.example.recipeclipper.ui.listdetail

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.RecipeList
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.plan.AddToPlanViewModel
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A list's detail (#376, #377): the long-press menu with its own Remove from this list (leaving a
 * list is not a deletion), Select from the overflow menu, and a selection's Remove and Delete.
 */
@RunWith(AndroidJUnit4::class)
class ListDetailRowActionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val weeknights = RecipeList(3, "Weeknights", isBuiltIn = false, isFavorites = false, recipeCount = 2)

    private fun summary(id: Long, title: String) =
        RecipeSummary(id, title, imageUrl = null, totalTime = null, lastViewedAt = 0, isSaved = true)

    private val adobo = summary(1, "Chicken Adobo")
    private val carbonara = summary(2, "Spaghetti Carbonara")

    private val lists = FakeListRepository().apply {
        this.lists.value = listOf(weeknights)
        membership.value = setOf(1L to 3L, 2L to 3L)
        recipesIn.value = listOf(adobo, carbonara)
    }
    private val recipes = FakeRecipeRepository().apply {
        listOf(adobo, carbonara).forEach {
            deleteResults[it.id] = RecipeRepository.DeletedRecipe(
                RecipeEntity(
                    id = it.id, sourceUrl = "https://example.com/${it.id}", title = it.title, imageUrl = null,
                    ingredients = emptyList(), instructions = emptyList(), prepTime = null, cookTime = null,
                    totalTime = null, servings = null, sourceType = "BLOG", lastViewedAt = 0
                ),
                crossRefs = emptyList()
            )
        }
    }
    private var opened: Long? = null

    private fun show(rowActions: Boolean = true, multiSelect: Boolean = true) {
        val viewModel = ListDetailViewModel(
            lists, SavedStateHandle(mapOf(ListDetailViewModel.LIST_ID_ARG to 3L)), recipes, FakeAppPreferences()
        )
        val saveViewModel = SaveToListViewModel(lists)
        val planViewModel = AddToPlanViewModel(FakeMealPlanRepository(), FakePlanCalendar())
        compose.setContent {
            ListDetailScreen(
                onBack = {},
                onOpenRecipe = { opened = it },
                viewModel = viewModel,
                rowActions = rowActions,
                multiSelect = multiSelect,
                saveViewModel = saveViewModel,
                planViewModel = planViewModel,
                snackbarHostState = SnackbarHostState()
            )
        }
        waitFor("Chicken Adobo")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun longPress(title: String) {
        compose.onNodeWithText(title).performTouchInput { longClick() }
    }

    @Test
    fun theMenuHasRemoveFromThisList_andItIsNotADeletion() {
        show()

        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuRemoveFromList").assertIsDisplayed()
        compose.onNodeWithTag("rowMenuRemoveFromList").performClick()

        compose.waitUntil(5_000) { lists.bulkCalls.isNotEmpty() }
        assertEquals(listOf(Triple(listOf(1L), 3L, false)), lists.bulkCalls)
        assertEquals(setOf(2L to 3L), lists.membership.value)
        assertTrue("the recipe is not deleted", recipes.deleteAllCalls.isEmpty())
        compose.onAllNodesWithText("Undo").let { assertEquals(0, it.fetchSemanticsNodes().size) }
    }

    @Test
    fun withTheFlagOffALongPressDoesNothing() {
        show(rowActions = false)

        longPress("Chicken Adobo")

        assertEquals(0, compose.onAllNodesWithText("Share").fetchSemanticsNodes().size)
    }

    @Test
    fun deleteFromTheMenuAsksThenDeletesWithOneUndo() {
        show()

        longPress("Spaghetti Carbonara")
        compose.onNodeWithTag("rowMenuDelete").performClick()
        waitFor("Delete \"Spaghetti Carbonara\"?")
        compose.onNodeWithTag("confirmDeleteRecipes").performClick()

        waitFor("Deleted \"Spaghetti Carbonara\"")
        assertEquals(listOf(listOf(2L)), recipes.deleteAllCalls)
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { recipes.restoreAllCalls.isNotEmpty() }
    }

    @Test
    fun theOverflowMenuSelectsSeveral_removeFromThisListActsOnAllOfThem() {
        show()

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithTag("selectRecipes").performClick()
        waitFor("0 selected")
        compose.onNodeWithText("Chicken Adobo").performClick()
        compose.onNodeWithText("Spaghetti Carbonara").performClick()
        waitFor("2 selected")
        compose.onNodeWithText("Chicken Adobo").assertIsOn()

        compose.onNodeWithTag("removeSelectedFromList").performClick()

        compose.waitUntil(5_000) { lists.bulkCalls.isNotEmpty() }
        assertEquals(listOf(Triple(listOf(1L, 2L), 3L, false)), lists.bulkCalls)
        assertTrue(lists.membership.value.isEmpty())
        // Selecting is over.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("2 selected").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aSelectionsDeleteIsOneConfirmationAndOneUndo() {
        show()
        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuSelect").performClick()
        compose.onNodeWithText("Spaghetti Carbonara").performClick()
        waitFor("2 selected")

        compose.onNodeWithTag("deleteSelected").performClick()
        waitFor("Delete 2 recipes?")
        compose.onNodeWithTag("confirmDeleteRecipes").performClick()

        waitFor("2 recipes deleted")
        assertEquals(listOf(listOf(1L, 2L)), recipes.deleteAllCalls)
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { recipes.restoreAllCalls.isNotEmpty() }
        assertEquals(1, recipes.restoreAllCalls.size)
    }

    @Test
    fun aTapWhileSelectingTicksAndDoesNotOpen() {
        show()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithTag("selectRecipes").performClick()
        waitFor("0 selected")

        compose.onNodeWithText("Chicken Adobo").performClick()

        waitFor("1 selected")
        assertEquals(null, opened)
        compose.onNodeWithText("Spaghetti Carbonara").assertIsOff()
    }
}
