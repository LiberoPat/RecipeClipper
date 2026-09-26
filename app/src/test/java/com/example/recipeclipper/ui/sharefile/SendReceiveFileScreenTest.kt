package com.example.recipeclipper.ui.sharefile

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.backup.PantryDestination
import com.example.recipeclipper.data.backup.ShareChoice
import com.example.recipeclipper.data.backup.ShareFile
import com.example.recipeclipper.data.backup.fixture
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeShareFileRepository
import com.example.recipeclipper.ui.groceries.GroceriesScreen
import com.example.recipeclipper.ui.groceries.GroceriesViewModel
import com.example.recipeclipper.ui.recipe.RecipeScreenFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * "Send as file" from the recipe screen and Groceries, and the sheet a received file opens
 * (#149, phase 2): real ViewModels over the fakes.
 */
@RunWith(AndroidJUnit4::class)
class SendReceiveFileScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val files = FakeBackupFiles()
    private val share = FakeShareFileRepository()

    /** The file handed to the share sheet: only the picked app may read it. */
    private fun assertSharedFile(name: String) {
        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(ShareFile.MIME_TYPE, send.type)
        @Suppress("DEPRECATION")
        assertEquals(Uri.parse(files.shareUri), send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(name, files.shared.single().second)
    }

    @Test
    fun theRecipeMenuSendsTheRecipeAsAFile() {
        share.recipeFiles[RecipeScreenFixture.RECIPE_ID] = "{recipe}"
        val fixture = RecipeScreenFixture(sendFile = SendFileViewModel(share, files))
        fixture.show(compose)

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Send as file").performClick()
        compose.waitForIdle()

        assertSharedFile("${RecipeScreenFixture.testRecipe().name}.recipeclipper")
        assertEquals("{recipe}", files.shared.single().first)
    }

    @Test
    fun groceriesSendTheUntickedItemsAsAFile() {
        val groceries = FakeGroceryRepository()
        runBlocking { groceries.add(listOf(NewGroceryLine("2 onions", "en"))) }
        share.groceriesFile = "{groceries}"
        val viewModel = GroceriesViewModel(groceries, FakePantryRepository(), FakePlanCalendar())
        compose.setContent { GroceriesScreen(viewModel = viewModel, sendFileViewModel = SendFileViewModel(share, files)) }

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Send as file").performClick()
        compose.waitForIdle()

        assertSharedFile("Groceries.recipeclipper")
    }

    @Test
    fun aReceivedFileOpensTheSheetAndAddsWhatIsTicked() {
        val uri = "content://messages/attachment/1"
        files.files[uri] = fixture("share-v1.recipeclipper")
        val inbox = ReceivedFileInbox()
        val flags = FeatureFlags(FakeFeatureFlagStore(mapOf("mealPlan" to true)), FlagRegistry.definitions, isDebug = false)
        val viewModel = ReceiveFileViewModel(files, share, flags, inbox)
        var shown: ReceivedWhere? = null
        compose.setContent { ReceiveFileHost(viewModel) { shown = it } }

        inbox.offer(uri)

        compose.onNodeWithText("Add from this file").assertIsDisplayed()
        compose.onNodeWithTag("receiveRow-r:r-chicken").assertIsOn()
        compose.onNodeWithTag("receiveRow-r:r-cake").performClick()
        compose.onNodeWithTag("receiveRow-r:r-cake").assertIsOff()
        compose.onNodeWithTag("receivePantryTo-GROCERIES").performScrollTo().performClick()
        compose.onNodeWithTag("receivePantryTo-GROCERIES").assertIsSelected()
        compose.onNodeWithTag("receiveFileAdd").performScrollTo().performClick()

        compose.onNodeWithText("Add from this file").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    ShareChoice(setOf("r-chicken"), setOf("g-thighs", "g-lemon", "g-paper"), setOf("p-rice"), PantryDestination.GROCERIES)
                ),
                share.received
            )
            assertEquals(ReceivedWhere.GROCERIES, shown)
        }
    }
}
