package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.SavedTimer
import com.example.recipeclipper.data.model.StepAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CookSession] on its own (#169): cook mode's steps and timers over a clock the test moves. */
class CookSessionTest {

    private var now = 1_000_000L
    private val session = CookSession(Clock { now })

    private fun recipe(cook: CookProgress = CookProgress()) = Recipe(
        name = "Stew", image = null, ingredients = listOf("2 carrots", "1 onion", "2 cups stock"),
        instructions = listOf("Chop.", "Simmer 20 minutes.", "Serve."), prepTime = null, cookTime = null,
        totalTime = null, yield = "4", sourceUrl = "https://example.com/stew", id = 7, cook = cook
    )

    @Test fun `restoring resumes a running timer from its deadline and finishes one that ran out`() {
        val saved = CookProgress(
            active = true, currentStep = 9, doneSteps = setOf(0, 5),
            timers = mapOf(
                1 to SavedTimer(1200, 1200, endsAt = now + 90_500),
                0 to SavedTimer(60, 60, endsAt = now - 1),
                2 to SavedTimer(300, 120, endsAt = null),
                4 to SavedTimer(60, 60, endsAt = now + 5_000)
            )
        )

        val restored = session.restore(recipe(saved))

        assertEquals(2, restored.cook.currentStep)
        assertEquals(setOf(0), restored.cook.doneSteps)
        assertTrue(restored.cook.active)
        assertEquals(StepTimer(1200, 91, running = true), restored.cook.timers[1])
        assertEquals(StepTimer(60, 0, running = false, alerted = true), restored.cook.timers[0])
        assertEquals(StepTimer(300, 120, running = false), restored.cook.timers[2])
        assertNull("past the last step", restored.cook.timers[4])
        assertEquals(listOf(StepAlarm(7, "Stew", 1, now + 90_500)), restored.alarms)
        assertTrue(session.hasRunningTimers)
    }

    @Test fun `a finished run starts fresh and stops its timers, anything else resumes`() {
        val running = session.startTimer(CookState(), 1, 1200).cook
        val finished = running.copy(doneSteps = setOf(0, 1, 2), currentStep = 2)

        val fresh = session.start(finished, 3)
        assertEquals(CookState(active = true), fresh.cook)
        assertEquals(listOf(1), fresh.stopped)
        assertFalse(session.hasRunningTimers)

        val resumed = session.start(CookState(currentStep = 7, doneSteps = setOf(0)), 3)
        assertEquals(CookState(active = true, currentStep = 2, doneSteps = setOf(0)), resumed.cook)
        assertEquals(emptyList<Int>(), resumed.stopped)

        assertNull("no steps, nothing to cook", session.start(CookState(), 0).cook)
    }

    @Test fun `done moves to the next unfinished step, then the earliest skipped, then finishes`() {
        val first = session.done(CookState(active = true, currentStep = 0), 3)
        assertEquals(CookState(active = true, currentStep = 1, doneSteps = setOf(0)), first.cook)
        assertFalse(first.finished)

        // Step 1 was skipped by tapping step 2.
        val skipped = session.done(session.select(first.cook, 2), 3)
        assertEquals(1, skipped.cook.currentStep)

        val last = session.done(skipped.cook, 3)
        assertTrue(last.finished)
        assertEquals(setOf(0, 1, 2), last.cook.doneSteps)
        assertFalse(last.cook.active)
    }

    @Test fun `the end of cooking hands on the ticked lines as shown, in order`() {
        val content = RecipeContent.Success(
            recipe = recipe(), servings = null, ingredients = listOf("4 carrots", "2 onions", "480 ml stock"),
            instructions = recipe().instructions, stepTimerSeconds = listOf(null, 1200, null), sourceDomain = null,
            words = LanguageWords.ENGLISH
        )

        assertEquals(FinishedCook("en", listOf("4 carrots", "480 ml stock")), session.finishedCook(content, setOf(2, 0, 9)))
        assertNull(session.finishedCook(content, emptySet()))
    }

    @Test fun `exit keeps the progress and the ingredients bar toggles`() {
        val cook = CookState(active = true, currentStep = 1, doneSteps = setOf(0))
        assertEquals(cook.copy(active = false), session.exit(cook))
        assertTrue(session.toggleIngredients(cook).ingredientsExpanded)
        assertFalse(session.toggleIngredients(session.toggleIngredients(cook)).ingredientsExpanded)
    }

    @Test fun `a timer counts down from its deadline, pauses, resumes and resets`() {
        val started = session.startTimer(CookState(), 1, 60)
        assertEquals(now + 60_000, started.endsAt)
        assertEquals(StepTimer(60, 60, running = true), started.cook.timers[1])

        now += 20_000
        val ticked = session.tick(started.cook)!!
        assertEquals(40, ticked.timers[1]?.remainingSeconds)
        assertNull("nothing changed within the same second", session.tick(ticked))

        val paused = session.toggleTimer(ticked, 1)!!
        assertNull("pausing cancels the alarm", paused.endsAt)
        assertFalse(paused.cook.timers[1]!!.running)
        assertFalse(session.hasRunningTimers)
        now += 60_000
        assertNull("a paused timer doesn't tick", session.tick(paused.cook))

        val resumed = session.toggleTimer(paused.cook, 1)!!
        assertEquals(now + 40_000, resumed.endsAt)

        val reset = session.resetTimer(resumed.cook, 1)!!
        assertEquals(StepTimer(60, 60, running = false), reset.timers[1])
        assertFalse(session.hasRunningTimers)
        assertNull(session.resetTimer(reset, 2))
        assertNull(session.toggleTimer(reset, 2))
    }

    @Test fun `a timer that reaches zero stops running, and a finished one can't be resumed`() {
        val started = session.startTimer(CookState(), 0, 5).cook
        now += 5_000

        val done = session.tick(started)!!
        assertEquals(StepTimer(5, 0, running = false), done.timers[0])
        assertFalse(session.hasRunningTimers)
        assertNull(session.toggleTimer(done, 0))

        val alerted = session.alerted(done, 0)!!
        assertTrue(alerted.timers[0]!!.alerted)
        assertNull(session.alerted(done, 1))
    }

    @Test fun `progress saves a running timer by its deadline and a paused one by what it had left`() {
        val running = session.startTimer(CookState(active = true, currentStep = 1), 1, 1200).cook
        val paused = session.toggleTimer(session.startTimer(running, 2, 60).cook, 2)!!.cook

        val progress = session.progress(paused.copy(ingredientsExpanded = true))

        assertEquals(
            CookProgress(
                active = true, currentStep = 1,
                timers = mapOf(1 to SavedTimer(1200, 1200, now + 1_200_000), 2 to SavedTimer(60, 60, null))
            ),
            progress
        )
    }

    @Test fun `seconds left round up and never go below zero`() {
        assertEquals(1, CookSession.secondsUntil(1_001, 1_000))
        assertEquals(1, CookSession.secondsUntil(2_000, 1_000))
        assertEquals(0, CookSession.secondsUntil(1_000, 1_000))
        assertEquals(0, CookSession.secondsUntil(0, 1_000))
    }
}
