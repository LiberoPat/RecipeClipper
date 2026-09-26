package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.fake.FakeCookedPhotoRepository
import org.junit.Assert.assertEquals
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
