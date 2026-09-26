package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.ChefSupport
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultShortStepRepository
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.StepAmounts
import com.example.recipeclipper.data.model.TemperatureUnit
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeShortStepDao
import com.example.recipeclipper.fake.FakeStepShortener
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Chef mode (#100) in the recipe screen's ViewModel, with the model faked. */
class RecipeChefModeTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val oven = "Preheat the oven to 350°F and butter a 9-inch round cake tin."
    private val bake = "Bake for 25 to 30 minutes, until the top is golden and springy."
    private val shortOven = "Oven to 350°F; butter a 9-inch tin."
    private val model = FakeStepShortener(written = mutableMapOf(oven to shortOven, bake to "Bake 25 min."))
    private val preferences = FakeAppPreferences(chefMode = true)
    private val flagStore = FakeFeatureFlagStore(mapOf("chefMode" to true))

    private fun recipe(language: String = "en") = Recipe(
        name = "Cake", image = null, ingredients = listOf("2 eggs"), instructions = listOf(oven, bake),
        prepTime = null, cookTime = null, totalTime = null, yield = "8", sourceUrl = "https://example.com/cake",
        id = 1, language = language
    )

    private fun TestScope.open(recipe: Recipe = recipe()): RecipeViewModel {
        val repository = FakeRecipeRepository().apply { openResult = recipe }
        val flags = FeatureFlags(flagStore, FlagRegistry.definitions, isDebug = false)
        return RecipeViewModel(
            SavedStateHandle(mapOf(RecipeViewModel.RECIPE_ID_ARG to 1L)), repository, preferences,
            Clock { testScheduler.currentTime }, FakeConnectivity(), FakeAppInfo(), FakeTimerAlarmScheduler(),
            DefaultShortStepRepository(FakeShortStepDao(), model, Clock { 0 }) { _, e -> throw e }, flags
        )
    }

    private fun RecipeViewModel.content() = uiState.value.content as RecipeContent.Success

    @Test fun `a short step that passes shows, one that doesn't stays as written`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = open()
        advanceUntilIdle()

        assertEquals(listOf(shortOven, null), vm.content().shortInstructions)
        assertEquals(shortOven, vm.content().shownStep(0, vm.uiState.value.asWrittenSteps))
        assertEquals(bake, vm.content().shownStep(1, vm.uiState.value.asWrittenSteps))
        // Timers still come from the steps as written.
        assertEquals(listOf(null, 25 * 60), vm.content().stepTimerSeconds)
    }

    @Test fun `tapping shows a step as written, and again shows it short`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = open()
        advanceUntilIdle()
        vm.onStepAsWrittenToggle(0)
        assertEquals(oven, vm.content().shownStep(0, vm.uiState.value.asWrittenSteps))
        vm.onStepAsWrittenToggle(0)
        assertEquals(shortOven, vm.content().shownStep(0, vm.uiState.value.asWrittenSteps))
    }

    @Test fun `short steps render temperatures like the steps do`() = runTest(mainDispatcherRule.dispatcher) {
        preferences.temperatureUnit = TemperatureUnit.CELSIUS
        val vm = open()
        advanceUntilIdle()
        assertEquals("Oven to 180°C; butter a 9-inch tin.", vm.content().shortInstructions[0])
    }

    // Each case: the steps show as written and the model is never asked.
    private fun TestScope.assertAsWritten(vm: RecipeViewModel) {
        advanceUntilIdle()
        assertTrue(model.asked.isEmpty())
        assertTrue(vm.content().shortInstructions.all { it == null })
    }

    @Test fun `the setting off shows steps as written`() = runTest(mainDispatcherRule.dispatcher) {
        preferences.chefMode = false
        assertAsWritten(open())
    }

    @Test fun `the flag off shows steps as written`() = runTest(mainDispatcherRule.dispatcher) {
        flagStore.setOverride("chefMode", false)
        assertAsWritten(open())
    }

    @Test fun `a language the model can't write shows steps as written`() = runTest(mainDispatcherRule.dispatcher) {
        assertAsWritten(open(recipe(language = "pt")))
    }

    @Test fun `a phone that can't shows steps as written`() = runTest(mainDispatcherRule.dispatcher) {
        model.support = ChefSupport.Unsupported
        assertAsWritten(open())
    }

    @Test fun `amounts in steps (#101) follow the step as shown, short or as written`() =
        runTest(mainDispatcherRule.dispatcher) {
            val carrots = "Add the carrots to the pot, stir well and let everything cook gently."
            model.written[carrots] = "Add the carrots; stir."
            preferences.amountsInSteps = true
            val vm = open(recipe().copy(ingredients = listOf("2 carrots, diced"), instructions = listOf(carrots)))
            advanceUntilIdle()

            assertEquals("Add ⟦2⟧ carrots; stir.", StepAmounts.marked(vm.content().shownStepAmounts(0, emptySet())!!))
            vm.onStepAsWrittenToggle(0)
            assertEquals(
                "Add ⟦2⟧ carrots to the pot, stir well and let everything cook gently.",
                StepAmounts.marked(vm.content().shownStepAmounts(0, vm.uiState.value.asWrittenSteps)!!)
            )
        }

    @Test fun `turning Chef mode off clears the short steps`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = open()
        advanceUntilIdle()
        preferences.chefMode = false
        advanceUntilIdle()
        assertTrue(vm.content().shortInstructions.isEmpty())
    }

    @Test fun `a recipe the free tier didn't keep shows as written until Unlock keeps it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository().apply {
                importResult = ParseResult.Success(recipe().copy(id = 0), kept = false)
            }
            val vm = RecipeViewModel(
                SavedStateHandle(mapOf(RecipeViewModel.URL_ARG to "https://example.com/cake")), repository,
                preferences, Clock { testScheduler.currentTime }, FakeConnectivity(), FakeAppInfo(),
                FakeTimerAlarmScheduler(),
                shortSteps = DefaultShortStepRepository(FakeShortStepDao(), model, Clock { 0 }) { _, e -> throw e },
                featureFlags = FeatureFlags(flagStore, FlagRegistry.definitions, isDebug = false),
                entitlements = FakeEntitlements()
            )
            assertAsWritten(vm) // no row to cache short steps against (#107)

            vm.onUnlock()
            advanceUntilIdle()
            assertEquals(listOf(shortOven, null), vm.content().shortInstructions)
        }
}
