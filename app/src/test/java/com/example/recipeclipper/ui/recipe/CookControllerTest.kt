package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.SavedTimer
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CookController] on its own (#234): cook mode's alarms, tick loop and saved progress around
 * [CookSession], over state the test holds as the ViewModel would.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CookControllerTest {

    private val alarms = FakeTimerAlarmScheduler()
    private val repository = FakeRecipeRepository()
    private var cook = CookState()
    private var content: RecipeContent.Success? = null

    private fun recipe(cook: CookProgress = CookProgress()) = Recipe(
        name = "Soup", image = null, ingredients = listOf("2 cups stock", "1 onion"),
        instructions = listOf("Chop.", "Simmer for 10 minutes.", "Serve."), prepTime = null, cookTime = null,
        totalTime = null, yield = "4 servings", sourceUrl = "https://example.com/soup", id = ID, cook = cook
    )

    // The clock follows the test's virtual time, so ticks see the time pass.
    private fun TestScope.controller(loaded: Recipe? = recipe()): CookController {
        content = loaded?.let { RecipeRenderer.content(it, RecipeRenderer.Settings()) }
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        return CookController(
            clock = Clock { START + testScheduler.currentTime },
            alarms = alarms,
            scope = scope,
            writes = OrderedWrites(scope),
            repository = repository,
            content = { content },
            cook = { cook },
            onCook = { cook = it }
        )
    }

    @Test fun `every action saves the cook's place, in order`() = runTest {
        val controller = controller()

        controller.start()
        controller.select(2)
        controller.exit()
        advanceUntilIdle()

        assertEquals(
            listOf(
                ID to CookProgress(active = true),
                ID to CookProgress(active = true, currentStep = 2),
                ID to CookProgress(active = false, currentStep = 2)
            ),
            repository.setCookProgressCalls
        )
    }

    @Test fun `opening the ingredients bar is not saved`() = runTest {
        val controller = controller()

        controller.toggleIngredients()
        advanceUntilIdle()

        assertTrue(cook.ingredientsExpanded)
        assertTrue(repository.setCookProgressCalls.isEmpty())
    }

    @Test fun `a timer schedules its alarm and counts down from the clock without writing`() = runTest {
        val controller = controller()

        controller.startTimer(1)
        runCurrent() // the start's own write; the loop waits for its first tick
        val writes = repository.setCookProgressCalls.size
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals(setOf(ID to 1), alarms.pending.keys)
        assertEquals(597, cook.timers.getValue(1).remainingSeconds)
        assertEquals(writes, repository.setCookProgressCalls.size)
        controller.close()
    }

    @Test fun `pausing a timer cancels its alarm and resetting stops it`() = runTest {
        val controller = controller()
        controller.startTimer(1)

        controller.toggleTimer(1)
        assertTrue(alarms.pending.isEmpty())
        assertFalse(cook.timers.getValue(1).running)

        controller.toggleTimer(1)
        controller.resetTimer(1)
        assertTrue(alarms.pending.isEmpty())
        assertEquals(600, cook.timers.getValue(1).remainingSeconds)
    }

    @Test fun `a step with no time starts no timer`() = runTest {
        val controller = controller()

        controller.startTimer(0)
        advanceUntilIdle()

        assertTrue(cook.timers.isEmpty())
        assertTrue(alarms.scheduleCalls.isEmpty())
        assertTrue(repository.setCookProgressCalls.isEmpty())
    }

    @Test fun `restoring reschedules a running timer, and stopping the timers cancels it`() = runTest {
        val saved = CookProgress(active = true, timers = mapOf(1 to SavedTimer(600, 600, endsAt = START + 60_000)))
        val controller = controller()

        controller.restore(recipe(saved))
        assertEquals(setOf(ID to 1), alarms.pending.keys)
        assertTrue(cook.active)

        controller.stopTimers(ID)
        assertTrue(alarms.pending.isEmpty())
    }

    @Test fun `the last step done finishes the cook with what was ticked`() = runTest {
        val controller = controller()
        val loaded = content!!
        val onLast = CookState(active = true, currentStep = 2, doneSteps = setOf(0, 1))

        val done = controller.done(onLast, loaded, checked = setOf(1))

        assertTrue(done.finished)
        assertFalse(done.cook.active)
        assertEquals(FinishedCook("en", listOf("1 onion")), done.finishedCook)
    }

    @Test fun `a step done before the last one finishes nothing`() = runTest {
        val controller = controller()

        val done = controller.done(CookState(active = true), content!!, checked = setOf(0, 1))

        assertFalse(done.finished)
        assertEquals(1, done.cook.currentStep)
        assertNull(done.finishedCook)
    }

    @Test fun `with nothing loaded cook mode does nothing`() = runTest {
        val controller = controller(loaded = null)

        controller.start()
        controller.startTimer(1)
        advanceUntilIdle()

        assertFalse(cook.active)
        assertTrue(repository.setCookProgressCalls.isEmpty())
    }

    private companion object {
        const val ID = 3L
        const val START = 1_000_000L
    }
}
