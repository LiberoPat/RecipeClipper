package com.example.recipeclipper.ui.tour

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.FirstRunTour
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Tip
import com.example.recipeclipper.data.model.WelcomeState
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTourPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The welcome's cards and ways out (#151; iOS: WelcomeViewModelTests). */
@OptIn(ExperimentalCoroutinesApi::class)
class WelcomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val preferences = FakeTourPreferences(welcome = WelcomeState.PENDING)
    private val recipes = FakeRecipeRepository()
    private val flagStore = FakeFeatureFlagStore()
    private val flags = FeatureFlags(flagStore, FlagRegistry.definitions, isDebug = false)

    private fun viewModel(again: Boolean = false) = WelcomeViewModel(
        FirstRunTour(preferences, recipes), flags, SavedStateHandle(mapOf(WelcomeViewModel.AGAIN_ARG to again))
    )

    @Test
    fun `four cards with the meal plan on, and the daily one names Chef mode with its flag`() {
        flags.set(Flag.MEAL_PLAN, true)
        flags.set(Flag.CHEF_MODE, true)
        val state = viewModel().uiState.value
        assertEquals(listOf(WelcomeCard.APP, WelcomeCard.CLIP, WelcomeCard.DAILY, WelcomeCard.WEEKLY), state.cards)
        assertTrue(state.chefMode)
    }

    @Test
    fun `a flag that is off hides its card and its line`() {
        flags.set(Flag.MEAL_PLAN, false)
        flags.set(Flag.CHEF_MODE, false)
        val state = viewModel().uiState.value
        assertEquals(listOf(WelcomeCard.APP, WelcomeCard.CLIP, WelcomeCard.DAILY), state.cards)
        assertFalse(state.chefMode)
    }

    @Test
    fun `Next and Back move through the cards, never past either end`() {
        flags.set(Flag.MEAL_PLAN, false)
        val vm = viewModel()
        vm.onPrevious()
        assertEquals(0, vm.uiState.value.page)
        repeat(5) { vm.onNext() }
        assertEquals(2, vm.uiState.value.page)
        assertTrue(vm.uiState.value.isLast)
        vm.onPrevious()
        assertEquals(WelcomeCard.CLIP, vm.uiState.value.card)
    }

    @Test
    fun `showing it adds the sample the first time`() = runTest(mainDispatcherRule.dispatcher) {
        viewModel()
        advanceUntilIdle()
        assertEquals(1, recipes.addSampleCalls.size)
        assertTrue(preferences.sampleAdded)
    }

    @Test
    fun `Skip and Start mark it seen and leave`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.onDone()
        assertEquals(WelcomeState.SEEN, preferences.welcome)
        assertEquals(WelcomeExit.Done, vm.uiState.value.exit)
        vm.onExitHandled()
        assertNull(vm.uiState.value.exit)
    }

    @Test
    fun `Try it opens the sample and marks the welcome seen`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val id = recipes.sampleId()!!

        vm.onTrySample()
        advanceUntilIdle()

        assertEquals(WelcomeExit.OpenRecipe(id), vm.uiState.value.exit)
        assertEquals(WelcomeState.SEEN, preferences.welcome)
        assertEquals("the sample there is opened, not added again", 1, recipes.addSampleCalls.size)
    }

    @Test
    fun `from Settings, the deleted sample comes back with Try it, and every tip shows again`() =
        runTest(mainDispatcherRule.dispatcher) {
            preferences.welcome = WelcomeState.SEEN
            preferences.sampleAdded = true
            Tip.entries.forEach { preferences.setTipSeen(it, true) }

            val vm = viewModel(again = true)
            advanceUntilIdle()
            assertTrue("no sample added by itself", recipes.addSampleCalls.isEmpty())
            assertEquals(emptySet<Tip>(), preferences.seenTips.first())

            vm.onTrySample()
            advanceUntilIdle()
            assertEquals(1, recipes.addSampleCalls.size)
            assertEquals(WelcomeExit.OpenRecipe(99), vm.uiState.value.exit)
        }

    @Test
    fun `if the sample can't be saved, Try it still leaves the welcome`() = runTest(mainDispatcherRule.dispatcher) {
        recipes.addSampleResult = null
        val vm = viewModel()
        vm.onTrySample()
        advanceUntilIdle()
        assertEquals(WelcomeExit.Done, vm.uiState.value.exit)
        assertEquals(WelcomeState.SEEN, preferences.welcome)
    }
}
