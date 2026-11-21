package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cook progress across a change of steps (#432). iOS: `CookProgressTests`. */
class CookProgressTest {

    private val timer = SavedTimer(totalSeconds = 600, remainingSeconds = 300, endsAt = 9_000L)

    @Test fun `the old last steps now being the tips keeps the progress`() {
        assertTrue(CookProgress.keptWhenTipsSplitOff(listOf("Bake.", "Cool.", "A tip."), listOf("Bake.", "Cool."), listOf("A tip.")))
        assertTrue(CookProgress.keptWhenTipsSplitOff(listOf("Bake.", "A.", "B."), listOf("Bake."), listOf("A.", "B.")))
    }

    @Test fun `any other change is not a split`() {
        // A real step changed too.
        assertFalse(CookProgress.keptWhenTipsSplitOff(listOf("Bake.", "Cool.", "A tip."), listOf("Bake well.", "Cool."), listOf("A tip.")))
        // A tip that was never a step.
        assertFalse(CookProgress.keptWhenTipsSplitOff(listOf("Bake.", "Cool."), listOf("Bake.", "Cool."), listOf("A tip.")))
        // The tips in another order.
        assertFalse(CookProgress.keptWhenTipsSplitOff(listOf("Bake.", "A.", "B."), listOf("Bake."), listOf("B.", "A.")))
        // Compared exactly, as stored.
        assertFalse(CookProgress.keptWhenTipsSplitOff(listOf("Bake.", "a tip."), listOf("Bake."), listOf("A tip.")))
    }

    @Test fun `no tips is never a split, even when steps were only dropped`() {
        assertFalse(CookProgress.keptWhenTipsSplitOff(listOf("Bake.", "Cool."), listOf("Bake."), emptyList()))
    }

    @Test fun `clamping drops marks and timers past the last step and moves the current step back`() {
        val progress = CookProgress(active = true, currentStep = 3, doneSteps = setOf(0, 2, 3), timers = mapOf(1 to timer, 3 to timer))
        assertEquals(
            CookProgress(active = true, currentStep = 2, doneSteps = setOf(0, 2), timers = mapOf(1 to timer)),
            progress.clampedTo(3)
        )
    }

    @Test fun `clamping leaves progress inside the steps as it is`() {
        val progress = CookProgress(active = true, currentStep = 1, doneSteps = setOf(0), timers = mapOf(1 to timer))
        assertEquals(progress, progress.clampedTo(2))
    }

    @Test fun `no steps left, no progress`() {
        assertTrue(CookProgress(active = true, currentStep = 1).clampedTo(0).isEmpty)
    }
}
