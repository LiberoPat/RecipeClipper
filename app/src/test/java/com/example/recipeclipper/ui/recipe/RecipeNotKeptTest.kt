package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A shared recipe the full free library couldn't keep (#107): shown, with Unlock. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeNotKeptTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val shown = Recipe(
        name = "Soup", image = null, ingredients = listOf("1 leek"), instructions = listOf("Simmer."),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "https://a.com/soup"
    )
    private val repository = FakeRecipeRepository().apply {
        importResult = ParseResult.Success(shown, kept = false)
    }
    private val entitlements = FakeEntitlements()

    private fun viewModel() = RecipeViewModel(
        SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to "https://a.com/soup")), repository, FakeAppPreferences(),
        Clock { 0 }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler(), entitlements
    )

    @Test fun `a recipe that wasn't kept is shown, marked not kept`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.notKept)
        assertEquals("Soup", (vm.uiState.value.content as RecipeContent.Success).recipe.name)
    }

    @Test fun `unlocking keeps the recipe on screen`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onUnlock()
        advanceUntilIdle()
        assertEquals(1, entitlements.purchases)
        assertEquals(listOf(shown), repository.keepCalls)
        assertFalse(vm.uiState.value.notKept)
        assertEquals(1L, (vm.uiState.value.content as RecipeContent.Success).recipe.id)
    }

    @Test fun `a failed purchase keeps nothing and says so`() = runTest(mainDispatcherRule.dispatcher) {
        entitlements.purchaseOutcome = PurchaseOutcome.FAILED
        val vm = viewModel()
        advanceUntilIdle()
        vm.onUnlock()
        advanceUntilIdle()
        assertTrue(repository.keepCalls.isEmpty())
        assertTrue(vm.uiState.value.notKept)
        assertEquals(PurchaseOutcome.FAILED, vm.uiState.value.unlockNotice)
        vm.onUnlockNoticeShown()
        assertNull(vm.uiState.value.unlockNotice)
    }

    @Test fun `a cancelled purchase says nothing`() = runTest(mainDispatcherRule.dispatcher) {
        entitlements.purchaseOutcome = PurchaseOutcome.CANCELLED
        val vm = viewModel()
        advanceUntilIdle()
        vm.onUnlock()
        advanceUntilIdle()
        assertNull(vm.uiState.value.unlockNotice)
        assertTrue(vm.uiState.value.notKept)
    }
}
