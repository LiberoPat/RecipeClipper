package com.example.recipeclipper.ui.recipe

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.SavedTimer
import com.example.recipeclipper.data.model.StepAlarm
import com.example.recipeclipper.fake.FakeAppInfo
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeConnectivity
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Issue #10: cook progress and servings are saved as they change and restored on opening,
 * and running timers are handed to the [FakeTimerAlarmScheduler] for their background alert.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecipeCookPersistenceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val alarms = FakeTimerAlarmScheduler()

    private fun recipe(
        cook: CookProgress = CookProgress(),
        servingsTarget: Int? = null,
        instructions: List<String> = listOf("Chop.", "Simmer for 10 minutes.", "Rest for 5 minutes.")
    ) = Recipe(
        name = "Soup",
        image = null,
        ingredients = listOf("2 cups stock"),
        instructions = instructions,
        prepTime = null,
        cookTime = null,
        totalTime = null,
        yield = "4 servings",
        sourceUrl = "https://example.com/soup",
        id = ID,
        cook = cook,
        servingsTarget = servingsTarget
    )

    private fun TestScope.open(
        recipe: Recipe,
        repository: FakeRecipeRepository = FakeRecipeRepository(),
        cookArg: Boolean = false,
        plannedServings: Int? = null
    ): RecipeViewModel {
        repository.openResult = recipe
        val args = mutableMapOf<String, Any?>(RecipeViewModel.RECIPE_ID_ARG to ID)
        if (cookArg) args[RecipeViewModel.COOK_ARG] = true
        if (plannedServings != null) args[RecipeViewModel.SERVINGS_ARG] = plannedServings
        return RecipeViewModel(
            SavedStateHandle(args), repository, FakeAppPreferences(), Clock { testScheduler.currentTime },
            FakeConnectivity(), FakeAppInfo(), alarms
        )
    }

    // --- Restoring ---

    @Test fun `opening restores cook mode, the current step, done steps and servings`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = open(recipe(CookProgress(active = true, currentStep = 2, doneSteps = setOf(0, 1)), servingsTarget = 8))
            advanceUntilIdle()

            val state = vm.uiState.value
            assertTrue(state.cook.active)
            assertEquals(2, state.cook.currentStep)
            assertEquals(setOf(0, 1), state.cook.doneSteps)
            assertEquals(8, (state.content as RecipeContent.Success).servings?.target)
        }

    @Test fun `opened from the Week, the planned servings win over the saved ones, unsaved`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val vm = open(recipe(servingsTarget = 8), repository, plannedServings = 2)
            advanceUntilIdle()

            assertEquals(2, (vm.uiState.value.content as RecipeContent.Success).servings?.target)
            // Only a change made here is saved (#49): the plan's figure is for this visit.
            assertTrue(repository.setServingsTargetCalls.isEmpty())
        }

    @Test fun `indexes past the last step are dropped`() = runTest(mainDispatcherRule.dispatcher) {
        val saved = CookProgress(
            active = true, currentStep = 7, doneSteps = setOf(0, 9),
            timers = mapOf(9 to SavedTimer(60, 60, null))
        )
        val vm = open(recipe(saved))
        advanceUntilIdle()

        val cook = vm.uiState.value.cook
        assertEquals(2, cook.currentStep)
        assertEquals(setOf(0), cook.doneSteps)
        assertTrue(cook.timers.isEmpty())
    }

    @Test fun `a timer still running resumes from its deadline and is rescheduled`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Started before the app closed; 90 seconds are left at the moment it reopens.
            val saved = CookProgress(active = true, timers = mapOf(1 to SavedTimer(600, 600, endsAt = 90_000L)))
            val vm = open(recipe(saved))
            runCurrent()

            val timer = vm.uiState.value.cook.timers.getValue(1)
            assertTrue(timer.running)
            assertEquals(90, timer.remainingSeconds)
            assertEquals(StepAlarm(ID, "Soup", 1, 90_000L), alarms.pending[ID to 1])

            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(60, vm.uiState.value.cook.timers.getValue(1).remainingSeconds)
        }

    @Test fun `a timer that ended while closed shows finished and already alerted`() =
        runTest(mainDispatcherRule.dispatcher) {
            advanceTimeBy(100_000)
            val saved = CookProgress(active = true, timers = mapOf(1 to SavedTimer(600, 600, endsAt = 50_000L)))
            val vm = open(recipe(saved))
            advanceUntilIdle()

            val timer = vm.uiState.value.cook.timers.getValue(1)
            assertTrue(timer.finished)
            assertTrue(timer.alerted) // the background alert announced it; no beep on reopening
            assertTrue(alarms.scheduleCalls.isEmpty())
        }

    @Test fun `a paused timer stays paused, and a finished one stays quiet`() = runTest(mainDispatcherRule.dispatcher) {
        val saved = CookProgress(
            timers = mapOf(1 to SavedTimer(600, 240, null), 2 to SavedTimer(300, 0, null))
        )
        val vm = open(recipe(saved))
        advanceUntilIdle()

        val timers = vm.uiState.value.cook.timers
        assertEquals(StepTimer(600, 240, running = false), timers[1])
        assertEquals(StepTimer(300, 0, running = false, alerted = true), timers[2])
        assertTrue(alarms.scheduleCalls.isEmpty())
    }

    @Test fun `the cook argument opens the recipe in cook mode`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = open(recipe(), cookArg = true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.cook.active)
    }

    // --- Saving ---

    @Test fun `every cook action saves the progress, last write last`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val vm = open(recipe(), repository)
        advanceUntilIdle()

        vm.onCookStart()
        vm.onStepDone()
        vm.onStepSelected(2)
        vm.onCookExit()
        advanceUntilIdle()

        assertEquals(
            listOf(
                CookProgress(active = true),
                CookProgress(active = true, currentStep = 1, doneSteps = setOf(0)),
                CookProgress(active = true, currentStep = 2, doneSteps = setOf(0)),
                CookProgress(active = false, currentStep = 2, doneSteps = setOf(0))
            ),
            repository.setCookProgressCalls.map { it.second }
        )
        assertTrue(repository.setCookProgressCalls.all { it.first == ID })
    }

    @Test fun `a running timer is saved by its deadline and its alarm follows start, pause, resume and reset`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val vm = open(recipe(), repository)
            advanceUntilIdle()

            vm.onTimerStart(1) // 10 minutes, from t = 0
            runCurrent()
            assertEquals(StepAlarm(ID, "Soup", 1, 600_000L), alarms.pending[ID to 1])
            assertEquals(SavedTimer(600, 600, 600_000L), repository.setCookProgressCalls.last().second.timers[1])

            advanceTimeBy(100_000)
            runCurrent()
            vm.onTimerToggle(1) // pause with 500 s left
            runCurrent()
            assertTrue(alarms.pending.isEmpty())
            assertEquals(SavedTimer(600, 500, null), repository.setCookProgressCalls.last().second.timers[1])

            vm.onTimerToggle(1) // resume at t = 100 s
            runCurrent()
            assertEquals(600_000L, alarms.pending[ID to 1]?.endsAt)

            vm.onTimerReset(1)
            runCurrent()
            assertTrue(alarms.pending.isEmpty())
            assertEquals(SavedTimer(600, 600, null), repository.setCookProgressCalls.last().second.timers[1])
        }

    @Test fun `a timer reaching zero in the app keeps its alarm, and ticks don't write`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val vm = open(recipe(), repository)
            advanceUntilIdle()

            vm.onTimerStart(2)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.cook.timers.getValue(2).finished)
            assertTrue(alarms.cancelCalls.isEmpty())
            assertEquals(1, repository.setCookProgressCalls.size)
        }

    @Test fun `restarting a finished run cancels the alarms of the timers it drops`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = open(recipe(instructions = listOf("Simmer for 10 minutes.")))
            advanceUntilIdle()
            vm.onCookStart()
            vm.onTimerStart(0)
            vm.onStepDone() // the only step: the run is finished, the timer still running

            vm.onCookStart()
            runCurrent()

            assertTrue(alarms.pending.isEmpty())
            assertTrue(vm.uiState.value.cook.timers.isEmpty())
        }

    @Test fun `deleting the recipe cancels its running timers`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = open(recipe())
        advanceUntilIdle()
        vm.onTimerStart(1)
        vm.onTimerStart(2)

        vm.onDelete()
        advanceUntilIdle()

        assertTrue(alarms.pending.isEmpty())
    }

    @Test fun `the chosen servings are saved, and the recipe's own yield as none`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeRecipeRepository()
            val vm = open(recipe(), repository)
            advanceUntilIdle()

            vm.onServingsChange(6)
            vm.onServingsChange(4)
            advanceUntilIdle()

            assertEquals(listOf(ID to 6, ID to null), repository.setServingsTargetCalls)
        }

    @Test fun `leaving the screen right after a tap still saves it`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeRecipeRepository()
        val store = ViewModelStore()
        val vm = open(recipe(), repository)
        store.put("recipe", vm)
        advanceUntilIdle()

        vm.onCookStart()
        store.clear() // viewModelScope is cancelled before the queued write has run
        advanceUntilIdle()

        assertEquals(CookProgress(active = true), repository.setCookProgressCalls.lastOrNull()?.second)
        assertFalse(alarms.pending.isNotEmpty())
    }

    private companion object {
        const val ID = 3L
    }
}
