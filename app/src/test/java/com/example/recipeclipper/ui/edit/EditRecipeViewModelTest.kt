package com.example.recipeclipper.ui.edit

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditRecipeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val soup = Recipe(
        name = "Soup", image = "https://example.com/soup.jpg", ingredients = listOf("1 onion", "2 carrots"),
        instructions = listOf("Chop.", "Simmer."), prepTime = "10m", cookTime = null, totalTime = null,
        yield = "4", sourceUrl = "https://example.com/soup", id = 3L
    )

    private fun editing(id: Long) = SavedStateHandle(mapOf(EditRecipeViewModel.RECIPE_ID_ARG to id))

    @Test fun `editing opens on the recipe's content, one line per ingredient and step`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { openResult = soup }
            val vm = EditRecipeViewModel(editing(3L), repository)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertFalse(state.isNew)
            assertFalse(state.loading)
            assertEquals("1 onion\n2 carrots", state.draft.ingredientsText)
            assertEquals("Chop.\nSimmer.", state.draft.instructionsText)
            assertEquals("10m", state.draft.prepTime)
            assertEquals("", state.draft.cookTime)
        }

    @Test fun `saving an edit hands the draft to the repository and reports the id`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { openResult = soup; saveEditResult = soup }
            val vm = EditRecipeViewModel(editing(3L), repository)
            advanceUntilIdle()

            vm.onDraftChange(vm.uiState.value.draft.copy(name = "Better soup"))
            vm.onSave()
            advanceUntilIdle()

            assertEquals(3L, repository.saveEditCalls.single().first)
            assertEquals("Better soup", repository.saveEditCalls.single().second.name)
            assertEquals(3L, vm.uiState.value.savedId)
        }

    @Test fun `new recipe starts empty and saves as manual`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { addManualResult = soup.copy(id = 9L) }
        val vm = EditRecipeViewModel(SavedStateHandle(), repository)

        assertTrue(vm.uiState.value.isNew)
        assertFalse(vm.uiState.value.loading)
        vm.onDraftChange(RecipeDraft(name = "Toast", instructionsText = "Toast the bread."))
        vm.onSave()
        advanceUntilIdle()

        assertEquals("Toast", repository.addManualCalls.single().name)
        assertEquals(9L, vm.uiState.value.savedId)
    }

    @Test fun `a full library keeps the editor open with Unlock, which then saves (#107)`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply { addManualResult = soup.copy(id = 0L) }
            val entitlements = FakeEntitlements()
            val vm = EditRecipeViewModel(SavedStateHandle(), repository, entitlements)
            vm.onDraftChange(RecipeDraft(name = "Toast", instructionsText = "Toast the bread."))
            vm.onSave()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.libraryFull)
            assertNull(vm.uiState.value.savedId)

            repository.addManualResult = soup.copy(id = 9L)
            vm.onUnlock()
            advanceUntilIdle()
            assertEquals(1, entitlements.purchases)
            assertFalse(vm.uiState.value.libraryFull)
            assertEquals(9L, vm.uiState.value.savedId)
            assertEquals(2, repository.addManualCalls.size)
        }

    @Test fun `a pending purchase from the editor says so and saves nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository().apply { addManualResult = soup.copy(id = 0L) }
        val entitlements = FakeEntitlements().apply { purchaseOutcome = PurchaseOutcome.PENDING }
        val vm = EditRecipeViewModel(SavedStateHandle(), repository, entitlements)
        vm.onDraftChange(RecipeDraft(name = "Toast", instructionsText = "Toast the bread."))
        vm.onSave()
        advanceUntilIdle()
        vm.onUnlock()
        advanceUntilIdle()
        assertEquals(PurchaseOutcome.PENDING, vm.uiState.value.unlockNotice)
        assertEquals(1, repository.addManualCalls.size)
    }

    @Test fun `an invalid draft is not saved and the rule is shown`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = EditRecipeViewModel(SavedStateHandle(), repository)

        vm.onDraftChange(RecipeDraft(name = "Toast"))
        vm.onSave()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showInvalid)
        assertTrue(repository.addManualCalls.isEmpty())
        assertNull(vm.uiState.value.savedId)
    }

    @Test fun `a failed save says so and stays on the screen`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository() // addManual answers null
        val vm = EditRecipeViewModel(SavedStateHandle(), repository)

        vm.onDraftChange(RecipeDraft(name = "Toast", ingredientsText = "Bread"))
        vm.onSave()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.saveFailed)
        assertNull(vm.uiState.value.savedId)
    }

    @Test fun `a recipe deleted elsewhere shows as missing and can't be saved`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository() // open answers null
            val vm = EditRecipeViewModel(editing(3L), repository)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.missing)
            vm.onSave()
            assertTrue(repository.saveEditCalls.isEmpty())
        }
}
