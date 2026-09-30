package com.example.recipeclipper.ui.edit

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.PhotoTextResult
import com.example.recipeclipper.data.remote.PhotoLine
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakePhotoTextReader
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.home.HomeScreen
import com.example.recipeclipper.ui.home.HomeViewModel
import com.example.recipeclipper.ui.recipes.RecipesScreen
import com.example.recipeclipper.ui.recipes.RecipesViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * "Scan a recipe" (#226): the entries on Home and in the Recipes + menu, which open #116's camera
 * or Photo Picker, and the review of the pages, over real ViewModels and fakes.
 */
@RunWith(AndroidJUnit4::class)
class ScanEntryScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val repository = FakeRecipeRepository()
    private var scanned: List<String>? = null

    private fun showRecipes(scanEnabled: Boolean) {
        val viewModel = RecipesViewModel(repository, FakeAppPreferences())
        compose.setContent {
            RecipesScreen(
                onBack = {}, onOpenRecipe = {}, onScan = { scanned = it },
                viewModel = viewModel, scanEnabled = scanEnabled
            )
        }
    }

    private fun showHome(scanEnabled: Boolean) {
        val viewModel = HomeViewModel(repository)
        compose.setContent {
            HomeScreen(
                onOpenUrl = {}, onOpenRecipe = {}, onOpenRecipes = {}, onOpenLists = {}, onOpenSettings = {},
                onScan = { scanned = it }, viewModel = viewModel, scanEnabled = scanEnabled
            )
        }
    }

    @Test
    fun theRecipesPlusMenuOffersAScanThatTurnsIntoTheCameraOrTheLibrary() {
        showRecipes(scanEnabled = true)

        compose.onNodeWithContentDescription("Add a recipe").performClick()
        compose.onNodeWithText("Type a recipe").assertIsDisplayed()
        compose.onNodeWithText("Scan a recipe").performClick()

        compose.onNodeWithText("Take a photo").assertIsDisplayed()
        compose.onNodeWithText("Choose from library").assertIsDisplayed()
        compose.onNodeWithText("Type a recipe").assertDoesNotExist()

        // The Photo Picker; the pages it hands back go to the review in the order picked.
        compose.onNodeWithText("Choose from library").performClick()
        compose.waitForIdle()
        val activity = shadowOf(compose.activity)
        val picker = activity.nextStartedActivityForResult
        assertNotNull(picker)
        val first = Uri.parse("content://media/picker/0/1")
        val second = Uri.parse("content://media/picker/0/2")
        val result = Intent().apply {
            clipData = ClipData.newRawUri(null, first).apply { addItem(ClipData.Item(second)) }
        }
        activity.receiveResult(picker.intent, Activity.RESULT_OK, result)
        compose.waitForIdle()

        assertEquals(listOf(first.toString(), second.toString()), scanned)
    }

    @Test
    fun takingAPhotoForAScanWritesAFileOfItsOwn() {
        showRecipes(scanEnabled = true)

        compose.onNodeWithContentDescription("Add a recipe").performClick()
        compose.onNodeWithText("Scan a recipe").performClick()
        compose.onNodeWithText("Take a photo").performClick()
        compose.waitForIdle()

        val camera = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, camera.intent.action)
        val output = IntentCompat.getParcelableExtra(camera.intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)
        assertTrue(output.toString(), output.toString().contains("/camera/scan-"))
    }

    @Test
    fun withTheFlagOffThereIsNoScan() {
        showRecipes(scanEnabled = false)

        compose.onNodeWithContentDescription("Add a recipe").performClick()
        compose.onNodeWithText("Type a recipe").assertIsDisplayed()
        compose.onNodeWithText("Scan a recipe").assertDoesNotExist()
    }

    @Test
    fun homeOffersTheScanBesideNewRecipe() {
        showHome(scanEnabled = true)

        compose.onNodeWithText("+ New recipe").assertIsDisplayed()
        compose.onNodeWithText("Scan a recipe").performClick()
        compose.onNodeWithText("Take a photo").assertIsDisplayed()
        compose.onNodeWithText("Choose from library").assertIsDisplayed()
    }

    @Test
    fun homeWithTheFlagOffHasNoScan() {
        showHome(scanEnabled = false)

        compose.onNodeWithText("+ New recipe").assertIsDisplayed()
        compose.onNodeWithText("Scan a recipe").assertDoesNotExist()
    }

    private fun showReview(result: PhotoTextResult, vararg pages: String, onSaved: (Long) -> Unit = {}) {
        val viewModel = EditRecipeViewModel(
            SavedStateHandle(mapOf(EditRecipeViewModel.SCAN_PAGES_ARG to pages.joinToString("\n"))),
            repository,
            photoReader = FakePhotoTextReader(result)
        )
        compose.setContent { EditRecipeScreen(onBack = {}, onSaved = onSaved, viewModel = viewModel) }
    }

    @Test
    fun theReviewShowsEachPageNamedForTalkBackAndTheRecipeToCheck() {
        showReview(
            PhotoTextResult.Read(
                listOf(
                    PhotoLine("Ingredients"), PhotoLine("11/2 cups flour"), PhotoLine("Directions"),
                    PhotoLine("1. Stir in the flour.")
                )
            ),
            "content://media/picker/0/1", "content://media/picker/0/2"
        )

        compose.onNodeWithText("Check the recipe").assertIsDisplayed()
        compose.onNodeWithContentDescription("Page 1 of 2").assertExists()
        compose.onNodeWithContentDescription("Page 2 of 2").assertExists()
        compose.onNodeWithText("Read from the photo", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("• 11/2 cups flour", substring = true).performScrollTo().assertIsDisplayed()
        // No "use this photo" switch (#226's decision): the photo link stays empty.
        compose.onNodeWithText("Use this photo", substring = true).assertDoesNotExist()
    }

    @Test
    fun aPageThatWontOpenSaysSoWithTryAgain() {
        showReview(PhotoTextResult.Failed, "content://media/picker/0/1")

        compose.onNodeWithText("Couldn't open the picture", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Try again").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theReaderNotReadyLeavesTheFieldsToFinishByHand() {
        showReview(PhotoTextResult.NotReady, "content://media/picker/0/1")

        compose.onNodeWithText("The photo reader isn't ready", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Name").performScrollTo().assertIsDisplayed()
    }
}
