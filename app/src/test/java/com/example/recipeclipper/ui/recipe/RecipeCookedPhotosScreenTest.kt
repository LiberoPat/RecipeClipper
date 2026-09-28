package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.fake.FakeCookedPhotoRepository
import com.example.recipeclipper.ui.assertInAppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "I made this" (#116) on the recipe screen: "Your cooks" at the foot of the reading view,
 * only behind its flag; the full-screen photo with its note and Undo for a delete; and the
 * recipe's delete confirmation naming the photos that go with it.
 */
@RunWith(AndroidJUnit4::class)
class RecipeCookedPhotosScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val photos = FakeCookedPhotoRepository()

    /** The section sits at the very foot, where "Start cooking" can cover a plain tap. */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollToNodeAction(), useUnmergedTree = false).onFirst().performScrollToNode(hasText(text))
    }

    @Test
    fun withTheFlagOffThereIsNoGallery() {
        RecipeScreenFixture().show(compose)
        compose.onNodeWithText("Your cooks").assertDoesNotExist()
    }

    @Test
    fun anEmptyGalleryOffersCameraAndLibrary() {
        RecipeScreenFixture(photos = photos).show(compose)
        scrollTo("I made this")
        compose.onNodeWithText("Your cooks").assertIsDisplayed()
        compose.onNodeWithText("I made this").tap()
        compose.onNodeWithText("Take a photo").assertExists()
        compose.onNodeWithText("Choose from library").assertExists()
        compose.onNodeWithText("Mark as cooked").assertExists()
    }

    /**
     * "Mark as cooked" (#173): one tap records today's cooking with no photo. It opens like a new
     * photo, "Cooked" in place of the picture, for its note; it has no Share; it then shows in the
     * row as a dated entry TalkBack reads as cooked with no photo; and its delete says so.
     */
    @Test
    fun markingAsCookedAddsADatedEntryWithoutAPicture() {
        RecipeScreenFixture(photos = photos).show(compose)
        scrollTo("I made this")
        compose.onNodeWithText("I made this").tap()
        compose.onNodeWithText("Mark as cooked").performClick()
        compose.waitUntil(5_000) { photos.photos.value.isNotEmpty() }
        assertEquals(null, photos.photos.value.single().fileName)

        assertTrue(compose.onAllNodesWithText("Cooked").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription("Share photo").assertDoesNotExist()
        compose.onNodeWithText("Add a short note").performTextInput("Doubled the garlic")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(5_000) { photos.edits.isNotEmpty() }
        assertEquals("Doubled the garlic", photos.photos.value.single().note)

        scrollTo("Your cooks")
        compose.onAllNodesWithContentDescription("Cooked, no photo", substring = true).onFirst().tap()
        compose.onNodeWithContentDescription("Remove from your cooks").performClick()
        compose.waitUntil(5_000) { photos.photos.value.isEmpty() }
        compose.onNodeWithText("Removed from your cooks").assertIsDisplayed()
    }

    @Test
    fun deletingTheRecipeCountsOnlyItsPhotos() {
        photos.photo(RecipeScreenFixture.RECIPE_ID)
        photos.mark(RecipeScreenFixture.RECIPE_ID)
        RecipeScreenFixture(photos = photos).show(compose)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("with your photo of it", substring = true).assertIsDisplayed()
    }

    @Test
    fun aPhotoOpensFullScreenItsNoteIsSavedAndADeleteCanBeUndone() {
        photos.photo(RecipeScreenFixture.RECIPE_ID)
        RecipeScreenFixture(photos = photos).show(compose)
        scrollTo("Your cooks")
        compose.onAllNodesWithContentDescription("Your photo", substring = true).onFirst().tap()

        compose.onNodeWithText("Add a short note").performTextInput("Less sugar next time")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitUntil(5_000) { photos.edits.isNotEmpty() }
        assertEquals("Less sugar next time", photos.edits.last().third)

        compose.onAllNodesWithContentDescription("Your photo", substring = true).onFirst().tap()
        compose.onNodeWithContentDescription("Delete photo").performClick()
        compose.waitUntil(5_000) { photos.photos.value.isEmpty() }
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { photos.photos.value.size == 1 }
        assertEquals(emptyList<String>(), photos.forgotten)
    }

    /**
     * #186: the full-screen photo was composed outside the recipe screen's theme, so it and its
     * date picker came up Material purple.
     */
    @Test
    fun theFullScreenPhotoAndItsDatePickerAreInTheAppsTheme() {
        photos.photo(RecipeScreenFixture.RECIPE_ID)
        RecipeScreenFixture(photos = photos).show(compose)
        scrollTo("Your cooks")
        compose.onAllNodesWithContentDescription("Your photo", substring = true).onFirst().tap()

        val changeDate = SemanticsMatcher("clicks to change the date") {
            it.config.getOrNull(SemanticsActions.OnClick)?.label?.startsWith("Change the date") == true
        }
        compose.onNode(changeDate).performClick()

        compose.onNodeWithText("Cancel", useUnmergedTree = true).assertInAppTheme()
    }

    /** A phone restored from Android's backup has the entry but not its file. */
    @Test
    fun aPhotoWhoseFileIsMissingSaysSoAndKeepsItsNoteWithNoShare() {
        photos.photo(RecipeScreenFixture.RECIPE_ID, note = "Less salt", hasPicture = false)
        RecipeScreenFixture(photos = photos).show(compose)
        scrollTo("Your cooks")
        compose.onNodeWithText("Photo not on this phone").assertExists()

        compose.onAllNodesWithContentDescription("Your photo", substring = true).onFirst().tap()

        compose.onAllNodesWithText("Photo not on this phone").assertCountEquals(2)
        compose.onNodeWithText("Less salt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete photo").assertIsDisplayed()
        compose.onNodeWithContentDescription("Share photo").assertDoesNotExist()
    }

    @Test
    fun deletingTheRecipeSaysItsPhotosGoToo() {
        photos.photo(RecipeScreenFixture.RECIPE_ID)
        photos.photo(RecipeScreenFixture.RECIPE_ID)
        RecipeScreenFixture(photos = photos).show(compose)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("with your 2 photos of it", substring = true).assertIsDisplayed()
    }
}
