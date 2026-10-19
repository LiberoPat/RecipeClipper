package com.example.recipeclipper.ui.edit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.PhotoTextResult
import com.example.recipeclipper.data.remote.PhotoLine
import com.example.recipeclipper.fake.FakePhotoTextReader
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.Dispatchers
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
            photoReader = FakePhotoTextReader(result),
            // Sorted where the read finishes, so the compose rule's idling sees it.
            sorting = Dispatchers.Unconfined
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
    fun aFractionReadAsALookAlikeIsFixedWithOneTap() {
        // ML Kit's reading of card-fractions-hand.jpg (#245): "2 % cups oats" was "2 ⅔ cups oats".
        show(
            PhotoTextResult.Read(
                listOf(
                    PhotoLine("Ingredients"), PhotoLine("2 % cups oats"), PhotoLine("1 cup 2% milk"),
                    PhotoLine("Directions"), PhotoLine("1. Mix.")
                )
            )
        )

        compose.onNodeWithText("A fraction? Tap the one in the photo:", substring = true).performScrollTo().assertIsDisplayed()
        // The percentage isn't offered chips: only one line has them.
        compose.onNodeWithTag("edit.fraction.1.⅔").assertDoesNotExist()
        compose.onNodeWithContentDescription("Use ⅔ in 2 % cups oats").performScrollTo().performClick()

        compose.onNodeWithText("• 2 ⅔ cups oats", substring = true).assertExists()
        compose.onNodeWithText("A fraction? Tap the one in the photo:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf("2 ⅔ cups oats", "1 cup 2% milk"), repository.saveClipCalls.single().ingredients)
    }

    @Test
    fun aLineFlaggedOnlyForItsSizeSaysSo() {
        // Vision's "34 tsp salt" for ¾ (#249) is plainly read, just too much: its own reason (#281).
        show(
            PhotoTextResult.Read(
                listOf(
                    PhotoLine("Ingredients"), PhotoLine("34 tsp salt"), PhotoLine("1 cup flour"),
                    PhotoLine("Directions"), PhotoLine("1. Mix.")
                )
            )
        )

        compose.onNodeWithText("• 34 tsp salt", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This amount looks large: check it").assertExists()
        compose.onNodeWithText("Check these lines", substring = true).assertDoesNotExist()
    }

    @Test
    fun aLargeAmountBesideAHardToReadLineKeepsBothReasons() {
        show(
            PhotoTextResult.Read(
                listOf(
                    PhotoLine("Ingredients"), PhotoLine("34 tsp salt"), PhotoLine("1 1/2 cups flour", 0.3f),
                    PhotoLine("Directions"), PhotoLine("1. Mix.")
                )
            )
        )

        compose.onNodeWithText("Check these lines", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("This amount looks large: check it").assertExists()
    }

    @Test
    fun aLineIsListedWithoutItsListMarkup() {
        // #283: the review listed "• ▢ 34 tsp salt"; the box keeps the line as read.
        show(PhotoTextResult.Read(listOf(PhotoLine("Grandma's card"), PhotoLine("▢ 34 tsp salt"), PhotoLine("• 1 oup sugar"))))

        compose.onNodeWithText("• 34 tsp salt", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("• ▢", substring = true).assertDoesNotExist()
        compose.onNodeWithText("• •", substring = true).assertDoesNotExist()
        compose.onNodeWithText("▢ 34 tsp salt", substring = true, useUnmergedTree = true).assertExists()
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
