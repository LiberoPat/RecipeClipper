package com.example.recipeclipper.ui.groceries

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Locale

/**
 * Sending a grocery list and receiving one (#149), on the Groceries tab: a real
 * GroceriesViewModel and ReceiveListViewModel over the fake repositories.
 */
@RunWith(AndroidJUnit4::class)
class GroceriesSendReceiveTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val groceries = FakeGroceryRepository()
    private val pantry = FakePantryRepository()
    private val inbox = ReceivedListInbox()
    private var pantryOpened = false
    private val phoneLocale = Locale.getDefault()

    @Before fun englishPhone() = Locale.setDefault(Locale.ENGLISH)

    @After fun restoreLocale() = Locale.setDefault(phoneLocale)

    private fun show() {
        val calendar = FakePlanCalendar()
        val viewModel = GroceriesViewModel(groceries, pantry, calendar)
        val receive = ReceiveListViewModel(groceries, pantry, calendar, inbox)
        compose.setContent {
            GroceriesScreen(viewModel = viewModel, receiveViewModel = receive, onOpenPantry = { pantryOpened = true })
        }
    }

    private fun openMenu() = compose.onNodeWithContentDescription("More options").performClick()

    @Test
    fun sendListSharesEveryUntickedItemNamingItsRecipe() {
        runBlocking {
            groceries.add(
                listOf(
                    NewGroceryLine("2 lb chicken thighs", "en", recipeId = 1),
                    NewGroceryLine("2 onions", "en", recipeId = 1),
                    NewGroceryLine("2 onions", "en", recipeId = 2),
                    NewGroceryLine("1 cup milk", "en")
                )
            )
            groceries.setChecked(listOf(groceries.items.value.last().id), true)
        }
        groceries.recipeTitles.value = mapOf(1L to "Sheet-pan chicken", 2L to "Soup")
        show()

        openMenu()
        compose.onNodeWithText("Send list").performClick()

        val chooser = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(
            "Groceries\n\nFruit & vegetables\n- 4 onions (Sheet-pan chicken, Soup)\n\nMeat\n- 2 lb chicken thighs (Sheet-pan chicken)",
            send.getStringExtra(Intent.EXTRA_TEXT)
        )
    }

    @Test
    fun aListSharedInOpensTheSheetAndGoesOnTheList() {
        show()
        inbox.offer("Groceries\n\nMeat\n- 2 lb chicken thighs (Sheet-pan chicken)\n\nProduce\n- 2 onions\n- 1 lime")

        compose.onNodeWithText("Add this list").assertIsDisplayed()
        compose.onNodeWithTag("receiveLine-0").assertIsOn()
        compose.onNodeWithTag("receiveLine-2").performClick()
        compose.onNodeWithTag("receiveLine-2").assertIsOff()
        compose.onNodeWithTag("receiveToGroceries").performClick()

        compose.onNodeWithText("Add this list").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("2 lb chicken thighs (Sheet-pan chicken)", "2 onions"), groceries.items.value.map { it.text })
        }
        compose.onNodeWithText("2 lb chicken thighs (Sheet-pan chicken)").assertIsDisplayed()
    }

    @Test
    fun aPastedListCanGoInThePantry() {
        show()
        compose.activity.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("list", "milk\n2 eggs"))

        openMenu()
        compose.onNodeWithText("Paste a list").performClick()
        compose.onNodeWithText("milk").assertIsDisplayed()
        compose.onNodeWithTag("receiveToPantry").performClick()

        compose.runOnIdle {
            assertEquals(setOf("milk", "eggs"), pantry.items.value.map { it.name }.toSet())
            assertTrue(pantryOpened)
            assertTrue(groceries.items.value.isEmpty())
        }
    }

    @Test
    fun anEmptyClipboardSaysSo() {
        show()
        openMenu()
        compose.onNodeWithText("Paste a list").performClick()
        compose.onNodeWithText("There's no list on the clipboard", substring = true).assertIsDisplayed()
    }
}
