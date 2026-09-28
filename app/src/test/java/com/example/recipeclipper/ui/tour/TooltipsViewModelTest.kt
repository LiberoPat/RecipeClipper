package com.example.recipeclipper.ui.tour

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Tooltip
import com.example.recipeclipper.data.model.TooltipScreen
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeTourPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The app's one TooltipsViewModel (#190): visits, what a screen reports, dismissing, Show tips again (iOS: TooltipsViewModelTests). */
@OptIn(ExperimentalCoroutinesApi::class)
class TooltipsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val preferences = FakeTourPreferences()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
    private val home = setOf(Tooltip.HOME_LINK, Tooltip.HOME_NEW_RECIPE)

    @Test
    fun `a ready screen shows its first tooltip, on that appearance only`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = TooltipsViewModel(preferences, flags)
        vm.onVisit("a", TooltipScreen.HOME)
        vm.onReport("a", home, ready = false)
        advanceUntilIdle()
        assertNull("not while settling or covered", vm.uiState.value.current)

        vm.onReport("a", home, ready = true)
        advanceUntilIdle()
        assertEquals(TooltipsUiState(Tooltip.HOME_LINK, "a"), vm.uiState.value)
    }

    @Test
    fun `a report from a screen that has been left counts for nothing`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = TooltipsViewModel(preferences, flags)
        vm.onVisit("new", TooltipScreen.SETTINGS)
        vm.onReport("old", home, ready = true)
        advanceUntilIdle()
        assertNull(vm.uiState.value.current)
    }

    @Test
    fun `Got it marks it seen, and the next one waits for the next visit`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = TooltipsViewModel(preferences, flags)
        vm.onVisit("a", TooltipScreen.HOME)
        vm.onReport("a", home, ready = true)
        advanceUntilIdle()

        vm.onDismiss(Tooltip.HOME_LINK)
        advanceUntilIdle()
        assertEquals(setOf(Tooltip.HOME_LINK), preferences.seenTooltips.first())
        assertNull("never chained", vm.uiState.value.current)

        vm.onLeave("a")
        vm.onVisit("b", TooltipScreen.HOME)
        vm.onReport("b", home, ready = true)
        advanceUntilIdle()
        assertEquals(Tooltip.HOME_NEW_RECIPE, vm.uiState.value.current)
    }

    @Test
    fun `scrolled away it hides, and nothing else shows in its place`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = TooltipsViewModel(preferences, flags)
        vm.onVisit("a", TooltipScreen.RECIPE)
        vm.onReport("a", setOf(Tooltip.RECIPE_SERVINGS, Tooltip.RECIPE_START_COOKING), ready = true)
        advanceUntilIdle()
        assertEquals(Tooltip.RECIPE_SERVINGS, vm.uiState.value.current)

        vm.onReport("a", setOf(Tooltip.RECIPE_START_COOKING), ready = true)
        advanceUntilIdle()
        assertNull(vm.uiState.value.current)

        vm.onReport("a", setOf(Tooltip.RECIPE_SERVINGS, Tooltip.RECIPE_START_COOKING), ready = true)
        advanceUntilIdle()
        assertEquals("back in view, back again", Tooltip.RECIPE_SERVINGS, vm.uiState.value.current)
    }

    @Test
    fun `a flagged screen's tooltips wait for the flag`() = runTest(mainDispatcherRule.dispatcher) {
        flags.set(Flag.MEAL_PLAN, false)
        val vm = TooltipsViewModel(preferences, flags)
        vm.onVisit("a", TooltipScreen.PANTRY)
        vm.onReport("a", setOf(Tooltip.PANTRY_ADD), ready = true)
        advanceUntilIdle()
        assertNull(vm.uiState.value.current)

        flags.set(Flag.MEAL_PLAN, true)
        advanceUntilIdle()
        assertEquals(Tooltip.PANTRY_ADD, vm.uiState.value.current)
    }

    @Test
    fun `Show tips again brings every tooltip back`() = runTest(mainDispatcherRule.dispatcher) {
        Tooltip.entries.forEach { preferences.setTooltipSeen(it, true) }
        val vm = TooltipsViewModel(preferences, flags)
        vm.onReplay()
        assertEquals(emptySet<Tooltip>(), preferences.seenTooltips.first())

        vm.onVisit("a", TooltipScreen.SETTINGS)
        vm.onReport("a", setOf(Tooltip.SETTINGS_UNITS), ready = true)
        advanceUntilIdle()
        assertEquals(Tooltip.SETTINGS_UNITS, vm.uiState.value.current)
    }
}
