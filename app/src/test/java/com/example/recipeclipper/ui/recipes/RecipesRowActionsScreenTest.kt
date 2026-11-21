package com.example.recipeclipper.ui.recipes

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
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
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.plan.AddToPlanViewModel
import com.example.recipeclipper.ui.savetolist.SaveToListViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The long-press menu on a Recipes row (#376) and selecting several recipes (#377), over real
 * ViewModels with hand-written fakes: what the menu offers, what each entry opens, the one confirmation
 * for a selection's Delete, the one Undo after it, and that Back and rotation keep their promises.
 */
@RunWith(AndroidJUnit4::class)
class RecipesRowActionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()
    private val lists = FakeListRepository().apply {
        lists.value = listOf(
            RecipeList(1, "Favorites", isBuiltIn = true, isFavorites = true, recipeCount = 0),
            RecipeList(2, "Lunch", isBuiltIn = true, isFavorites = false, recipeCount = 0)
        )
    }
    private val snackbars = SnackbarHostState()

    private fun summary(id: Long, title: String) =
        RecipeSummary(id, title, imageUrl = null, totalTime = null, lastViewedAt = 0, isSaved = false)

    private val adobo = summary(1, "Chicken Adobo")
    private val carbonara = summary(2, "Spaghetti Carbonara")
    private val bread = summary(3, "Banana Bread")

    private val capturedById = mutableMapOf<Long, RecipeRepository.DeletedRecipe>()

    private fun captured(recipe: RecipeSummary) = capturedById.getOrPut(recipe.id) {
        RecipeRepository.DeletedRecipe(
            RecipeEntity(
                id = recipe.id, sourceUrl = "https://example.com/${recipe.id}", title = recipe.title, imageUrl = null,
                ingredients = emptyList(), instructions = emptyList(), prepTime = null, cookTime = null,
                totalTime = null, servings = null, sourceType = "BLOG", lastViewedAt = 0
            ),
            crossRefs = emptyList()
        )
    }

    private var opened: Long? = null
    private var backs = 0
    private lateinit var viewModel: RecipesViewModel
    private var onBackDispatcher: (() -> Unit)? = null

    private fun show(
        vararg recipes: RecipeSummary,
        rowActions: Boolean = true,
        multiSelect: Boolean = true,
        restoration: StateRestorationTester? = null
    ) {
        repository.history.value = recipes.toList()
        recipes.forEach { repository.deleteResults[it.id] = captured(it) }
        viewModel = RecipesViewModel(repository, FakeAppPreferences())
        // Built here, not in the composition (lint: a ViewModel constructed in a composable).
        val saveViewModel = SaveToListViewModel(lists)
        val planViewModel = AddToPlanViewModel(FakeMealPlanRepository(), FakePlanCalendar())
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            val owner = LocalOnBackPressedDispatcherOwner.current
            onBackDispatcher = { owner?.onBackPressedDispatcher?.onBackPressed() }
            RecipesScreen(
                onBack = { backs++ },
                onOpenRecipe = { opened = it },
                viewModel = viewModel,
                rowActions = rowActions,
                multiSelect = multiSelect,
                oneCookbook = false,
                saveViewModel = saveViewModel,
                planViewModel = planViewModel,
                snackbarHostState = snackbars
            )
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
        // The rows arrive once the ViewModel's query has run.
        recipes.firstOrNull()?.let { waitFor(it.title) }
    }

    private fun longPress(title: String) {
        compose.onNodeWithText(title).performTouchInput { longClick() }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // --- The long-press menu (#376) ---

    @Test
    fun aLongPressOffersAddToListPlanShareSelectAndDelete() {
        show(adobo, carbonara)

        longPress("Chicken Adobo")

        compose.onNodeWithTag("rowMenuAddToList").assertIsDisplayed()
        compose.onNodeWithTag("rowMenuAddToPlan").assertIsDisplayed()
        compose.onNodeWithTag("rowMenuShare").assertIsDisplayed()
        compose.onNodeWithTag("rowMenuSelect").assertIsDisplayed()
        compose.onNodeWithTag("rowMenuDelete").assertIsDisplayed()
        // "Remove from this list" is a list's own screen's.
        compose.onAllNodesWithText("Remove from this list").assertCountEqualsZero()
        assertEquals("a long press does not open the recipe", null, opened)
    }

    @Test
    fun aTapStillOpensTheRecipe() {
        show(adobo)
        compose.onNodeWithText("Chicken Adobo").performClick()
        assertEquals(1L, opened)
    }

    @Test
    fun withTheFlagOffALongPressDoesNothing() {
        show(adobo, rowActions = false)

        longPress("Chicken Adobo")

        compose.onAllNodesWithText("Share").assertCountEqualsZero()
        compose.onAllNodesWithText("Add to list").assertCountEqualsZero()
    }

    @Test
    fun selectIsLeftOutOfTheMenuWithMultiSelectOff() {
        show(adobo, multiSelect = false)

        longPress("Chicken Adobo")

        compose.onNodeWithTag("rowMenuShare").assertIsDisplayed()
        compose.onAllNodesWithText("Select").assertCountEqualsZero()
    }

    @Test
    fun addToListOpensTheSaveToListSheetForThatRecipe() {
        show(adobo, carbonara)

        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuAddToList").performClick()

        waitFor("Save to")
        compose.onNodeWithText("Favorites").assertIsOff()
        compose.onNodeWithText("Favorites").performClick()
        compose.waitUntil(5_000) { (1L to 1L) in lists.membership.value }
    }

    @Test
    fun addToPlanOpensThePlanSheet() {
        show(adobo)

        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuAddToPlan").performClick()

        waitFor("Add to plan")
    }

    @Test
    fun deleteAsksFirstNamingTheRecipe_andCancelKeepsIt() {
        show(adobo, carbonara)

        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuDelete").performClick()

        waitFor("Delete \"Chicken Adobo\"?")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5_000) { onAllNodesWithTextCount("Delete \"Chicken Adobo\"?") == 0 }
        assertTrue(repository.deleteAllCalls.isEmpty())
        compose.onNodeWithText("Chicken Adobo").assertIsDisplayed()
    }

    @Test
    fun confirmingDeleteRemovesItAndOffersUndo() {
        show(adobo, carbonara)

        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuDelete").performClick()
        waitFor("Delete \"Chicken Adobo\"?")
        compose.onNodeWithTag("confirmDeleteRecipes").performClick()

        waitFor("Deleted \"Chicken Adobo\"")
        assertEquals(listOf(listOf(1L)), repository.deleteAllCalls)
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { repository.restoreCalls.isNotEmpty() }
        assertEquals(listOf(captured(adobo)), repository.restoreCalls)
    }

    // --- Selecting several (#377) ---

    @Test
    fun theMenuSelectStartsSelectingWithThatRowTicked() {
        show(adobo, carbonara)

        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuSelect").performClick()

        waitFor("1 selected")
        compose.onNodeWithText("Chicken Adobo").assertIsOn()
        compose.onNodeWithText("Spaghetti Carbonara").assertIsOff()
        compose.onNodeWithTag("doneSelecting").assertIsDisplayed()
    }

    @Test
    fun theHeadersSelectStartsSelectingWithNothingTicked_andActionsWaitForATick() {
        show(adobo, carbonara)

        compose.onNodeWithTag("selectRecipes").performClick()

        waitFor("0 selected")
        compose.onNodeWithTag("deleteSelected").assertIsNotEnabled()
        compose.onNodeWithTag("addSelectedToList").assertIsNotEnabled()
        compose.onNodeWithText("Chicken Adobo").performClick()
        waitFor("1 selected")
        compose.onNodeWithTag("deleteSelected").assertIsEnabled()
        assertEquals("a tap while selecting ticks, it does not open", null, opened)
        // No Remove from this list on Recipes: a list's own screen has it.
        compose.onAllNodesWithText("Remove from this list").assertCountEqualsZero()
    }

    @Test
    fun theHeadersSelectIsLeftOutWithMultiSelectOff() {
        show(adobo, multiSelect = false)
        compose.onAllNodesWithText("Select").assertCountEqualsZero()
    }

    @Test
    fun aLongPressMenuIsOffWhileSelecting() {
        show(adobo, carbonara)
        compose.onNodeWithTag("selectRecipes").performClick()
        waitFor("0 selected")

        longPress("Chicken Adobo")

        // The long press only ticks, as a tap does: no menu.
        compose.onAllNodesWithText("Share").assertCountEqualsZero()
    }

    @Test
    fun doneEndsSelecting() {
        show(adobo)
        compose.onNodeWithTag("selectRecipes").performClick()
        waitFor("0 selected")

        compose.onNodeWithTag("doneSelecting").performClick()

        compose.waitUntil(5_000) { onAllNodesWithTextCount("0 selected") == 0 }
        compose.onNodeWithTag("selectRecipes").assertIsDisplayed()
    }

    @Test
    fun backLeavesSelectingFirst_thenTheScreen() {
        show(adobo)
        compose.onNodeWithTag("selectRecipes").performClick()
        waitFor("0 selected")

        compose.runOnUiThread { onBackDispatcher!!() }
        compose.waitUntil(5_000) { onAllNodesWithTextCount("0 selected") == 0 }
        assertEquals("the first Back only leaves selecting", 0, backs)
        assertFalse(viewModel.uiState.value.selecting)
    }

    @Test
    fun selectingSurvivesRotation_itIsTheViewModelsState() {
        val restoration = StateRestorationTester(compose)
        show(adobo, carbonara, bread, restoration = restoration)
        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuSelect").performClick()
        compose.onNodeWithText("Banana Bread").performClick()
        waitFor("2 selected")

        // A rotation recreates the composition, and the ViewModel carries on.
        restoration.emulateSavedInstanceStateRestore()

        waitFor("2 selected")
        compose.onNodeWithText("Chicken Adobo").assertIsOn()
        compose.onNodeWithText("Banana Bread").assertIsOn()
        compose.onNodeWithText("Spaghetti Carbonara").assertIsOff()
    }

    @Test
    fun deletingASelectionIsOneConfirmationAndOneUndo() {
        show(adobo, carbonara, bread)
        longPress("Chicken Adobo")
        compose.onNodeWithTag("rowMenuSelect").performClick()
        compose.onNodeWithText("Banana Bread").performClick()
        waitFor("2 selected")

        compose.onNodeWithTag("deleteSelected").performClick()

        waitFor("Delete 2 recipes?")
        compose.onNodeWithTag("confirmDeleteRecipes").performClick()
        waitFor("2 recipes deleted")
        assertEquals("one transaction", listOf(listOf(1L, 3L)), repository.deleteAllCalls)
        // The real history query stops returning the deleted rows.
        repository.history.value = listOf(carbonara)
        compose.onAllNodesWithText("Undo").assertCountEquals(1)
        compose.onNodeWithTag("selectRecipes").assertIsDisplayed() // selecting is over

        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { repository.restoreAllCalls.isNotEmpty() }
        assertEquals(listOf(listOf(1L, 3L)), repository.restoreAllCalls.map { burst -> burst.map { it.entity.id } })
    }

    @Test
    fun aSelectionsUndoTheFullFreeLibraryRefusesSaysSo() {
        repository.restoreOutcome = RecipeRepository.RestoreOutcome.LIBRARY_FULL
        show(adobo, carbonara)
        compose.onNodeWithTag("selectRecipes").performClick()
        compose.onNodeWithText("Chicken Adobo").performClick()
        compose.onNodeWithText("Spaghetti Carbonara").performClick()
        waitFor("2 selected")
        compose.onNodeWithTag("deleteSelected").performClick()
        waitFor("Delete 2 recipes?")
        compose.onNodeWithTag("confirmDeleteRecipes").performClick()
        waitFor("Undo")

        compose.onNodeWithText("Undo").performClick()

        waitFor("Couldn't restore: your library is full")
        assertTrue(repository.restoreCalls.isEmpty())
    }

    @Test
    fun addToListForASelectionTicksAllOfThem_andShowsAMixedListIndeterminate() {
        lists.membership.value = setOf(1L to 2L) // Adobo is already in Lunch
        show(adobo, carbonara)
        compose.onNodeWithTag("selectRecipes").performClick()
        compose.onNodeWithText("Chicken Adobo").performClick()
        compose.onNodeWithText("Spaghetti Carbonara").performClick()
        waitFor("2 selected")

        compose.onNodeWithTag("addSelectedToList").performClick()

        waitFor("Save to")
        // Lunch holds one of the two: indeterminate, which a tap fills.
        compose.onNodeWithText("Lunch").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Indeterminate)
        )
        compose.onNodeWithText("Lunch").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Some of them"))
        compose.onNodeWithText("Favorites").assertIsOff()
        compose.onNodeWithText("Favorites").performClick()
        compose.waitUntil(5_000) { lists.bulkCalls.isNotEmpty() }
        assertEquals(listOf(Triple(listOf(1L, 2L), 1L, true)), lists.bulkCalls)
        compose.onNodeWithText("Lunch").performClick()
        compose.waitUntil(5_000) { lists.bulkCalls.size == 2 }
        assertEquals(Triple(listOf(1L, 2L), 2L, true), lists.bulkCalls.last())
    }

    private fun onAllNodesWithTextCount(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() =
        assertEquals(0, fetchSemanticsNodes().size)
}
