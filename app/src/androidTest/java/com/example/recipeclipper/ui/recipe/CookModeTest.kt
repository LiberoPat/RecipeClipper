package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cook mode is a highlighted scroll: each step is done (struck, dimmed), current (a ringed
 * card with the timer and the only button that advances) or upcoming. Tapping a step makes it
 * current without marking anything done; only "Done — next step" advances.
 *
 * Steps: 1 "Preheat the oven to 350°F.", 2 "Simmer for 20 minutes." (the one with a timer),
 * 3 "Stir in the milk.", 4 "Serve."
 */
@RunWith(AndroidJUnit4::class)
class CookModeTest {

    @get:Rule
    val compose = createComposeRule()

    private val fixture = RecipeScreenFixture()

    private fun startCooking() {
        fixture.show(compose)
        compose.onNodeWithText("Start cooking").performClick()
        compose.onNodeWithText("Step 1 of 4").assertIsDisplayed()
    }

    /** The step's own text node, unmerged, so its drawn style can be read. */
    private fun stepText(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)

    private fun doneNext() = compose.onNodeWithText("Done — next step").performClick()

    // --- Step states ---

    @Test
    fun cookingStartsOnTheFirstStepWithEveryOtherStepUpcoming() {
        startCooking()

        compose.onNodeWithText("STEP 1").assertIsDisplayed()
        compose.onNodeWithText("Done — next step").assertIsDisplayed()
        for (step in listOf("Simmer for 20 minutes.", "Stir in the milk.", "Serve.")) {
            stepText(step).assertIsDisplayed()
            assertFalse("$step should not be struck", stepText(step).isStruckThrough())
        }
    }

    @Test
    fun doneAdvancesAndStrikesTheStepItLeaves() {
        startCooking()

        doneNext()

        compose.onNodeWithText("Step 2 of 4").assertIsDisplayed()
        compose.onNodeWithText("STEP 2").assertIsDisplayed()
        compose.onNodeWithText("STEP 1").assertDoesNotExist()
        assertTrue(stepText("Preheat the oven to 350°F.").isStruckThrough())
        assertFalse(stepText("Stir in the milk.").isStruckThrough())
    }

    /** Jumping is not progress: nothing is struck by a tap, and the step jumped from stays open. */
    @Test
    fun tappingAStepMakesItCurrentWithoutMarkingAnythingDone() {
        startCooking()

        compose.onNodeWithText("Stir in the milk.").performClick()

        compose.onNodeWithText("Step 3 of 4").assertIsDisplayed()
        compose.onNodeWithText("STEP 3").assertIsDisplayed()
        assertFalse(stepText("Preheat the oven to 350°F.").isStruckThrough())
        assertFalse(stepText("Simmer for 20 minutes.").isStruckThrough())
    }

    /** Cooks scroll back to re-check a step; "Done" from there carries on where they were. */
    @Test
    fun tappingADoneStepGoesBackToItAndDoneCarriesOn() {
        startCooking()
        doneNext()

        compose.onNodeWithText("Preheat the oven to 350°F.").performClick()

        compose.onNodeWithText("Step 1 of 4").assertIsDisplayed()
        compose.onNodeWithText("STEP 1").assertIsDisplayed()
        doneNext()
        compose.onNodeWithText("Step 2 of 4").assertIsDisplayed()
    }

    /** After a jump to the end, "Done" goes back to the earliest step still to do. */
    @Test
    fun theLastStepSaysFinishAndDoneThenReturnsToTheFirstSkippedStep() {
        startCooking()

        compose.onNodeWithText("Serve.").performClick()
        compose.onNodeWithText("Done — finish").performClick()

        compose.onNodeWithText("Step 1 of 4").assertIsDisplayed()
        assertTrue(stepText("Serve.").isStruckThrough())
    }

    @Test
    fun finishingEveryStepLeavesCookMode() {
        startCooking()

        repeat(3) { doneNext() }
        compose.onNodeWithText("Done — finish").performClick()

        compose.onNodeWithText("Start cooking").assertIsDisplayed()
        compose.onNodeWithText("Ingredients").assertIsDisplayed()
    }

    /** Exit is not "abandon": coming back resumes on the same step. */
    @Test
    fun exitKeepsYourPlace() {
        startCooking()
        doneNext()

        // Written "✕  Exit" in strings.xml; unquoted resources render one space.
        compose.onNodeWithText("✕ Exit").performClick()
        compose.onNodeWithText("Start cooking").performClick()

        compose.onNodeWithText("Step 2 of 4").assertIsDisplayed()
        assertTrue(stepText("Preheat the oven to 350°F.").isStruckThrough())
    }

    /** The reading view's bookmark and share icons don't follow you into cook mode. */
    @Test
    fun cookModeHasNoShareOrBookmark() {
        startCooking()

        compose.onNodeWithContentDescription("Share recipe").assertDoesNotExist()
        compose.onNodeWithContentDescription("Save to a list").assertDoesNotExist()
    }

    // --- Timers ---

    @Test
    fun onlyAStepThatStatesATimeOffersATimer() {
        startCooking()

        compose.onNodeWithText("⏱ Start", substring = true).assertDoesNotExist()

        doneNext()

        compose.onNodeWithText("⏱ Start 20 min timer").assertIsDisplayed()
    }

    @Test
    fun aTimerStartsAtTheStatedTimeAndCanBePausedAndResumed() {
        startCooking()
        doneNext()

        compose.onNodeWithText("⏱ Start 20 min timer").performClick()

        compose.onNodeWithText("20:00").assertIsDisplayed()
        compose.onNodeWithText("Pause").performClick()
        compose.onNodeWithText("Resume").performClick()
        compose.onNodeWithText("Pause").assertIsDisplayed()
        compose.onNodeWithText("Reset").assertIsDisplayed()
    }

    /** The clock is moved on, not waited out: the ViewModel reads the time from the fixture. */
    @Test
    fun aTimerCountsDownAndSaysTimesUpWhenItFinishes() {
        startCooking()
        doneNext()
        compose.onNodeWithText("⏱ Start 20 min timer").performClick()

        fixture.advanceClockBy(5 * 60_000L)
        waitForText("15:00")

        fixture.advanceClockBy(15 * 60_000L)
        waitForText("Time's up")

        compose.onNodeWithText("0:00").assertIsDisplayed()
        compose.onNodeWithText("Pause").assertDoesNotExist()
        compose.onNodeWithText("Reset").assertIsDisplayed()
    }

    @Test
    fun resetPutsAFinishedTimerBackToItsFullTime() {
        startCooking()
        doneNext()
        compose.onNodeWithText("⏱ Start 20 min timer").performClick()
        fixture.advanceClockBy(20 * 60_000L)
        waitForText("Time's up")

        compose.onNodeWithText("Reset").performClick()

        compose.onNodeWithText("20:00").assertIsDisplayed()
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNodeWithText("Time's up").assertDoesNotExist()
    }

    /** Steps overlap: a timer keeps running, and stays visible, on a step you've moved past. */
    @Test
    fun aTimerKeepsRunningOnAStepYouHaveMovedPast() {
        startCooking()
        doneNext()
        compose.onNodeWithText("⏱ Start 20 min timer").performClick()

        doneNext()
        compose.onNodeWithText("Step 3 of 4").assertIsDisplayed()
        compose.onNodeWithText("⏱ 20:00").assertIsDisplayed()

        fixture.advanceClockBy(20 * 60_000L)
        waitForText("⏱ Time's up")
    }

    private fun waitForText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
