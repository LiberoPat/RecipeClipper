package com.example.recipeclipper.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.PhotoTextResult
import com.example.recipeclipper.data.remote.PhotoLine
import com.example.recipeclipper.fake.FakePhotoTextReader
import com.example.recipeclipper.fake.FakeRecipeRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Read the photo" (#198): the editor as the review, over a real ViewModel and a fake reader. */
@RunWith(AndroidJUnit4::class)
class EditRecipePhotoScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeRecipeRepository()
    private val postUrl = "https://www.reddit.com/r/Old_Recipes/comments/1f6d4ef/aunt_junes_oatmeal_cookies/"

    private fun show(result: PhotoTextResult, onSaved: (Long) -> Unit = {}) {
        val viewModel = EditRecipeViewModel(
            SavedStateHandle(
                mapOf(
                    EditRecipeViewModel.PHOTO_URL_ARG to postUrl,
                    EditRecipeViewModel.PHOTO_TITLE_ARG to "Aunt June's oatmeal cookies",
                    EditRecipeViewModel.PHOTO_IMAGES_ARG to "https://preview.redd.it/front.jpg"
                )
            ),
            repository,
            photoReader = FakePhotoTextReader(result)
        )
        compose.setContent { EditRecipeScreen(onBack = {}, onSaved = onSaved, viewModel = viewModel) }
    }

    @Test
    fun theReadRecipeIsShownToCheckWithUnsureLinesMarked() {
        var saved: Long? = null
        show(
            PhotoTextResult.Read(
                listOf(
                    PhotoLine("Ingredients"), PhotoLine("1 cup butter"), PhotoLine("1 1/2 cups flour", 0.3f),
                    PhotoLine("Directions"), PhotoLine("1. Cream the butter."), PhotoLine("2. Stir in the flour.")
                )
            )
        ) { saved = it }

        compose.onNodeWithText("Check the recipe").assertIsDisplayed()
        compose.onNodeWithText("Read from the photo", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Check these lines", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("• 1 1/2 cups flour", substring = true).assertExists()
        compose.onNodeWithText("Aunt June's oatmeal cookies").performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf("1 cup butter", "1 1/2 cups flour"), repository.saveClipCalls.single().ingredients)
        assertEquals(1L, saved)
    }

    @Test
    fun nothingSortedOpensTheEditorToFinishByHand() {
        show(PhotoTextResult.Read(listOf(PhotoLine("Cream butter and sugar,"), PhotoLine("add the oats. Bake."))))

        compose.onNodeWithText("Couldn't read a recipe from the photo: finish it by hand.").assertIsDisplayed()
        compose.onNodeWithText("Cream butter and sugar,", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aPhotoThatWontLoadOffersTryAgain() {
        show(PhotoTextResult.Failed)

        compose.onNodeWithText("Couldn't load the photo", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun aReaderThatIsntReadyOffersTryAgainAndTheEditor() {
        show(PhotoTextResult.NotReady)

        compose.onNodeWithText("The photo reader isn't ready", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
        compose.onNodeWithText("Aunt June's oatmeal cookies").performScrollTo().assertIsDisplayed()
    }
}
