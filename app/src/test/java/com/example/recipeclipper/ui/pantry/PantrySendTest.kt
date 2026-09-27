package com.example.recipeclipper.ui.pantry

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.backup.ShareFile
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.fake.FakeBackupFiles
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeShareFileRepository
import com.example.recipeclipper.ui.sharefile.SendFileViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The Pantry's menu sends what's in stock (#149), as text or as a file: a real PantryViewModel
 * and SendFileViewModel over the fakes.
 */
@RunWith(AndroidJUnit4::class)
class PantrySendTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val files = FakeBackupFiles()
    private val share = FakeShareFileRepository()

    private fun item(id: Long, name: String, quantity: String? = null, inStock: Boolean = true, aisle: Aisle = Aisle.OTHER) =
        PantryItem(id, name, quantity, "en", aisle, inStock, alwaysHave = false, purchasedDay = null, expiresDay = null)

    private fun show(vararg items: PantryItem) {
        val viewModel = PantryViewModel(FakePantryRepository(items.toList()), FakeGroceryRepository(), FakePlanCalendar())
        val send = SendFileViewModel(share, files)
        compose.setContent { PantryScreen(viewModel = viewModel, sendFileViewModel = send) }
    }

    private fun openMenu() = compose.onNodeWithContentDescription("More options").performClick()

    @Test
    fun sendListSharesWhatIsInStock() {
        show(
            item(1, "basmati rice", "half a bag", aisle = Aisle.GRAINS), item(2, "onions", aisle = Aisle.PRODUCE),
            item(3, "milk", inStock = false, aisle = Aisle.DAIRY)
        )

        openMenu()
        compose.onNodeWithText("Send list").performClick()

        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals("text/plain", send.type)
        assertEquals(
            "Pantry\n\nFruit & vegetables\n- onions\n\nPasta, rice & grains\n- basmati rice (half a bag)",
            send.getStringExtra(Intent.EXTRA_TEXT)
        )
    }

    @Test
    fun sendAsFileSharesThePantrysFile() {
        share.pantryFile = "{pantry}"
        show(item(1, "basmati rice"))

        openMenu()
        compose.onNodeWithText("Send as file").performClick()
        compose.waitForIdle()

        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(ShareFile.MIME_TYPE, send.type)
        @Suppress("DEPRECATION")
        assertEquals(Uri.parse(files.shareUri), send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(listOf("{pantry}" to "Pantry.recipeclipper"), files.shared)
    }

    @Test
    fun withNothingInStockNeitherCanBeSent() {
        show(item(1, "milk", inStock = false))

        openMenu()
        compose.onNodeWithText("Send list").assertIsNotEnabled()
        compose.onNodeWithText("Send as file").assertIsNotEnabled()
        compose.onNodeWithText("Send list").performClick()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }
}
