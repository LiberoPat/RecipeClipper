package com.example.recipeclipper.ui.pantry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Pantry tab (#51), a real PantryViewModel over the fakes. */
@RunWith(AndroidJUnit4::class)
class PantryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val groceries = FakeGroceryRepository()
    private val calendar = FakePlanCalendar()

    private fun show(vararg items: PantryItem): FakePantryRepository {
        val pantry = FakePantryRepository(items.toList())
        val viewModel = PantryViewModel(pantry, groceries, calendar)
        compose.setContent { PantryScreen(viewModel = viewModel) }
        return pantry
    }

    private fun item(id: Long, name: String, inStock: Boolean = true, expires: Long? = null, aisle: Aisle = Aisle.OTHER) =
        PantryItem(id, name, null, "en", aisle, inStock, alwaysHave = false, purchasedDay = null, expiresDay = expires)

    @Test
    fun anEmptyPantrySaysHowToFillIt() {
        show()
        compose.onNodeWithText("Nothing in the pantry yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun aTypedItemLandsInItsAisleInStock() {
        val pantry = show()
        compose.onNodeWithTag("pantryDraft").performTextInput("milk")
        compose.onNodeWithTag("pantryDraft").performImeAction()

        compose.onNodeWithText("Dairy & eggs").assertIsDisplayed()
        compose.onNodeWithText("milk").assertIsDisplayed()
        compose.onNodeWithTag("inStock-1").assertIsOn()
        compose.runOnIdle { assertTrue(pantry.items.value.single().inStock) }
    }

    @Test
    fun runningOutOffersGroceries() {
        val pantry = show(item(1, "milk", aisle = Aisle.DAIRY))
        compose.onNodeWithTag("inStock-1").performClick()
        compose.onNodeWithTag("inStock-1").assertIsOff()

        compose.onNodeWithText("milk is out").assertIsDisplayed()
        compose.onNodeWithText("Add to groceries").performClick()
        compose.waitUntil(5_000) { groceries.items.value.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf("milk"), groceries.items.value.map { it.text })
            assertTrue(!pantry.items.value.single().inStock)
        }
    }

    @Test
    fun expiryBadgesShowOnTheRow() {
        val today = calendar.today()
        show(item(1, "yogurt", expires = today - 1), item(2, "cream", expires = today + 1))
        compose.onNodeWithTag("expiry-1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Expired").assertIsDisplayed()
        compose.onNodeWithText("Use by", substring = true).assertIsDisplayed()
    }

    @Test
    fun theEditSheetMakesAStapleAndDeleteCanBeUndone() {
        val pantry = show(item(1, "salt"), item(2, "rice"))
        compose.onNodeWithText("salt").performClick()
        compose.onNodeWithTag("pantryEditAlwaysHave").performClick()
        compose.onNodeWithTag("pantryEditSave").performClick()
        compose.runOnIdle { assertTrue(pantry.items.value.first().alwaysHave) }
        compose.onNodeWithTag("pantryList").performScrollToNode(hasTestTag("pantry-1"))
        compose.onNodeWithText("Always have").assertIsDisplayed()

        compose.onNodeWithText("rice").performClick()
        compose.onNodeWithTag("pantryEditDelete").performClick()
        compose.onNodeWithText("Deleted rice").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { pantry.items.value.size == 2 }
    }

    @Test
    fun searchNarrowsAndSaysWhenNothingMatches() {
        show(item(1, "plain flour"), item(2, "rice"))
        compose.onNodeWithTag("pantrySearch").performTextInput("flour")
        compose.onNodeWithText("plain flour").assertIsDisplayed()
        compose.onNodeWithText("rice").assertDoesNotExist()
        compose.onNodeWithTag("pantrySearch").performTextInput("zzz")
        compose.onNodeWithText("Nothing matches", substring = true).assertIsDisplayed()
    }
}
