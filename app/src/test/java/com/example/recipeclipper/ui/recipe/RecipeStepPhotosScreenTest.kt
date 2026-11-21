package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagValues
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step photos on the recipe screen (#433, option B): a thumbnail beside each step the source gave
 * photos, none beside a step without, the full-screen viewer on a tap, cook mode's smaller ones
 * and the current card's, and a recipe with no step photos (or the flag off) exactly as before.
 */
@RunWith(AndroidJUnit4::class)
class RecipeStepPhotosScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val steps = listOf("Mix the flour (1).", "Peel the apples (2) (3).", "Heat the oven.", "Bake for 20 minutes (4).")

    private fun recipe(photos: List<List<String>> = listOf(
        listOf(FakeImages.ok("1")), listOf(FakeImages.ok("2"), FakeImages.ok("3")), emptyList(), listOf(FakeImages.ok("4"))
    )): Recipe = RecipeScreenFixture.testRecipe(steps).copy(stepPhotos = photos)

    private fun show(recipe: Recipe = recipe(), flagOn: Boolean = true): RecipeScreenFixture =
        RecipeScreenFixture(
            recipe,
            flags = if (flagOn) FlagValues(setOf(Flag.STEP_PHOTOS)) else FlagValues.ALL_OFF,
            imageLoader = FakeImages.loader(ApplicationProvider.getApplicationContext())
        ).also { it.show(compose) }

    private fun scrollTo(tag: String) {
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasTestTag(tag))
    }

    private fun count(tagPrefix: String): Int =
        compose.onAllNodes(SemanticsMatcher("tag starts with $tagPrefix") { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(tagPrefix) == true
        }, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun aStepWithPhotosHasAThumbnailBadgedByItsMarkerAndAStepWithoutHasNone() {
        show()
        scrollTo("stepPhoto.thumbnail.3")
        compose.onNodeWithTag("stepPhoto.thumbnail.0").assertContentDescriptionEquals("Photo 1 of step 1")
        // Two photos stack into one thumbnail.
        compose.onNodeWithTag("stepPhoto.thumbnail.1").assertContentDescriptionEquals("2 photos of step 2")
        compose.onNodeWithTag("stepPhoto.thumbnail.3").assertIsDisplayed().assertContentDescriptionEquals("Photo 4 of step 4")
        assertEquals(0, compose.onAllNodesWithTag("stepPhoto.thumbnail.2").fetchSemanticsNodes().size)
        // The markers stay in the text as written.
        compose.onNodeWithText("Peel the apples (2) (3).").assertIsDisplayed()
    }

    @Test
    fun aThumbnailOpensTheViewerWhichPagesThroughEveryStepPhoto() {
        val fixture = show()
        scrollTo("stepPhoto.thumbnail.1")
        compose.onNodeWithTag("stepPhoto.thumbnail.1").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("stepPhoto.viewer").assertIsDisplayed()
        compose.onNodeWithText("Photo 2 of 4").assertIsDisplayed()
        compose.onNodeWithText("Step 2").assertIsDisplayed()
        compose.onNodeWithTag("stepPhoto.caption", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, fixture.viewModel.uiState.value.stepPhotoViewer)

        compose.onNodeWithTag("stepPhoto.viewer").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Photo 3 of 4").assertIsDisplayed()
        // Still step 2: its second photo.
        compose.onNodeWithText("Step 2").assertIsDisplayed()
        assertEquals(2, fixture.viewModel.uiState.value.stepPhotoViewer)

        compose.onNodeWithTag("stepPhoto.viewer").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithText("Photo 4 of 4").assertIsDisplayed()
        compose.onNodeWithText("Step 4").assertIsDisplayed()

        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag("stepPhoto.viewer").fetchSemanticsNodes().size)
        assertEquals(null, fixture.viewModel.uiState.value.stepPhotoViewer)
    }

    @Test
    fun cookModeShowsSmallPhotosOnOtherStepsAndTheCurrentCardsBesideItsTimer() {
        show()
        compose.onNodeWithText("Start cooking").performClick()
        compose.waitForIdle()

        // Step 1 is current: its thumbnail, badged and tappable.
        compose.onNodeWithTag("stepPhoto.thumbnail.0").assertContentDescriptionEquals("Photo 1 of step 1")
        assertEquals(1, count("stepPhoto.mini.1"))
        assertEquals(0, count("stepPhoto.mini.2"))
        assertEquals(0, count("stepPhoto.thumbnail.1"))

        // Step 4 has a timer: the thumbnail sits beside it.
        compose.onNodeWithText("Bake for 20 minutes (4).").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("⏱ Start 20 min timer").assertIsDisplayed()
        compose.onNodeWithTag("stepPhoto.thumbnail.3").assertIsDisplayed()
        assertEquals(1, count("stepPhoto.mini.0"))

        compose.onNodeWithTag("stepPhoto.thumbnail.3").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Photo 4 of 4").assertIsDisplayed()
    }

    @Test
    fun withTipsTooTheThumbnailsSitBesideTheStepsAndTheTipsFollowWithNone() {
        RecipeScreenFixture(
            recipe().copy(tips = listOf("Use tart apples.")),
            flags = FlagValues(setOf(Flag.STEP_PHOTOS, Flag.RECIPE_TIPS)),
            imageLoader = FakeImages.loader(ApplicationProvider.getApplicationContext())
        ).show(compose)
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText("Use tart apples."))
        val lastThumb = compose.onNodeWithTag("stepPhoto.thumbnail.3").fetchSemanticsNode().boundsInRoot
        val tips = compose.onNodeWithText("Tips").fetchSemanticsNode().boundsInRoot
        assertTrue("the method's photos, then the tips", lastThumb.bottom <= tips.top)
        // A tip is no step: four thumbnails' worth of photos, no more.
        assertEquals(0, count("stepPhoto.thumbnail.4"))
    }

    @Test
    fun cookModesCurrentStepWithNoTimerHasItsThumbnailBesideItsText() {
        show()
        compose.onNodeWithText("Start cooking").performClick()
        compose.waitForIdle()

        // Step 1 has no timer: the thumbnail sits at the top right, beside the text, not in a row of its own.
        val text = compose.onNodeWithText("Mix the flour (1).").fetchSemanticsNode().boundsInRoot
        val thumb = compose.onNodeWithTag("stepPhoto.thumbnail.0").fetchSemanticsNode().boundsInRoot
        assertTrue("beside, not below", thumb.top < text.bottom)
        assertTrue("to the right", thumb.left >= text.right)
        compose.onNodeWithTag("stepPhoto.thumbnail.0").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Photo 1 of 4").assertIsDisplayed()
    }

    @Test
    fun cookModesCurrentStepWhosePhotoFailsLeavesNoGap() {
        show(recipe(listOf(listOf(FakeImages.failing("1")), emptyList(), emptyList(), emptyList())))
        compose.onNodeWithText("Start cooking").performClick()
        compose.waitUntil(5_000) { count("stepPhoto.thumbnail.0") == 0 }
        val withFailed = gapUnderText("Mix the flour (1).")

        // The same card for a step with no photo at all: the gap under the text is the same.
        compose.onNodeWithText("Peel the apples (2) (3).").performClick()
        compose.waitForIdle()
        assertEquals(gapUnderText("Peel the apples (2) (3)."), withFailed, 0.5f)
    }

    /** The space between a current step's text and its "Done — next step" button. */
    private fun gapUnderText(step: String): Float {
        val text = compose.onNodeWithText(step).fetchSemanticsNode().boundsInRoot
        val done = compose.onNodeWithText("Done — next step").fetchSemanticsNode().boundsInRoot
        return done.top - text.bottom
    }

    @Test
    fun aPhotoThatFailsToLoadShowsNoThumbnail() {
        show(recipe(listOf(listOf(FakeImages.failing("1")), emptyList(), emptyList(), listOf(FakeImages.ok("4")))))
        compose.waitUntil(5_000) { count("stepPhoto.thumbnail.0") == 0 }
        scrollTo("stepPhoto.thumbnail.3")
        compose.onNodeWithTag("stepPhoto.thumbnail.3").assertIsDisplayed()
    }

    @Test
    fun aRecipeWithNoStepPhotosIsAsBeforeInTheReadingViewAndCookMode() {
        show(recipe(emptyList()))
        assertEquals(0, count("stepPhoto."))
        compose.onNodeWithText("Start cooking").performClick()
        compose.waitForIdle()
        assertEquals(0, count("stepPhoto."))
    }

    @Test
    fun withTheFlagOffNoStepPhotoShows() {
        show(flagOn = false)
        assertEquals(0, count("stepPhoto."))
        compose.onNodeWithText("Start cooking").performClick()
        compose.waitForIdle()
        assertEquals(0, count("stepPhoto."))
    }
}
