package com.example.recipeclipper.ui.pantry

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PantryStock
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Pantry tab (#51), a real PantryViewModel over the fakes. */
@OptIn(ExperimentalTestApi::class)
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

    private fun item(
        id: Long,
        name: String,
        inStock: Boolean = true,
        expires: Long? = null,
        aisle: Aisle = Aisle.OTHER,
        quantity: String? = null,
        runningLow: Boolean = false
    ) = PantryItem(id, name, quantity, "en", aisle, inStock, alwaysHave = false, purchasedDay = null, expiresDay = expires, runningLow = runningLow)

    /** The Run out section's heading, not the Run out count above the list (#286). */
    private val runOutHeading = hasText("Run out") and isHeading()

    /** The row's TalkBack actions are exactly [expected], then Select (#258). */
    private fun actionsOffer(id: Long, vararg expected: String) {
        compose.onNodeWithTag("pantry-$id").assert(SemanticsMatcher("offers ${expected.toList()}") { node ->
            node.config.getOrNull(SemanticsActions.CustomActions)?.map { it.label } == expected.toList() + "Select"
        })
    }

    @Test
    fun anEmptyPantrySaysHowToFillIt() {
        show()
        compose.onNodeWithText("Nothing in the pantry yet", substring = true).assertIsDisplayed()
        // No counts while there's nothing to count (#286).
        compose.onNodeWithTag("pantryCount-IN_STOCK").assertDoesNotExist()
    }

    // #286: three counts under the title, each read as one ("2, Run out"); a tap scrolls to the
    // first of its rows, or to the heading of the section it heads. A zero does nothing.
    @Test
    fun theCountsSumThePantryAndJumpToTheirRows() {
        val shelf = (1L..20L).map { item(it, "item %02d".format(it), runningLow = it == 15L) }
        show(*(shelf + item(21, "milk", inStock = false, aisle = Aisle.DAIRY) + item(22, "eggs", inStock = false)).toTypedArray())
        compose.onNodeWithTag("pantryCount-IN_STOCK").assertTextEquals("19", "In stock")
        compose.onNodeWithTag("pantryCount-RUNNING_LOW").assertTextEquals("1", "Running low")
        compose.onNodeWithTag("pantryCount-RUN_OUT").assertTextEquals("2", "Run out").assertIsEnabled()
        compose.onNode(runOutHeading).assertIsNotDisplayed()

        compose.onNodeWithTag("pantryCount-RUN_OUT").performClick()
        compose.onNode(runOutHeading).assertIsDisplayed()
        compose.onNodeWithTag("pantry-22").assertIsDisplayed()

        compose.onNodeWithTag("pantryList").performScrollToNode(hasTestTag("pantryCount-RUNNING_LOW"))
        compose.onNodeWithTag("pantryCount-RUNNING_LOW").performClick()
        compose.onNodeWithTag("pantry-15").assertIsDisplayed()
        compose.onNodeWithTag("low-15", useUnmergedTree = true).assertIsDisplayed()
    }

    // #286: a search narrows the counts to what it shows; a zero is off. While selecting (#258)
    // they stay put, off, so the rows don't move.
    @Test
    fun theCountsFollowTheSearchAndAreOffWhileSelecting() {
        show(item(1, "salt"), item(2, "rice"), item(3, "oats", inStock = false))
        compose.onNodeWithTag("pantryCount-RUNNING_LOW").assertTextEquals("0", "Running low").assertIsNotEnabled()
        compose.onNodeWithTag("pantrySearch").performTextInput("rice")
        compose.onNodeWithTag("pantryCount-IN_STOCK").assertTextEquals("1", "In stock")
        compose.onNodeWithTag("pantryCount-RUN_OUT").assertTextEquals("0", "Run out").assertIsNotEnabled()

        compose.onNodeWithTag("pantry-2").performTouchInput { longClick() }
        compose.onNodeWithTag("selectedCount").assertTextEquals("1 selected")
        compose.onNodeWithTag("pantryCount-IN_STOCK").assertIsNotEnabled()
        compose.onNodeWithTag("exitSelection").performClick()
        compose.onNodeWithTag("pantryCount-IN_STOCK").assertIsEnabled()
    }

    @Test
    fun aTypedItemLandsInItsAisleInStock() {
        val pantry = show()
        compose.onNodeWithTag("pantryDraft").performTextInput("milk")
        compose.onNodeWithTag("pantryDraft").performImeAction()

        compose.onNodeWithText("Dairy & eggs").assertIsDisplayed()
        compose.onNodeWithText("milk").assertIsDisplayed()
        compose.onNode(hasContentDescription("milk, In stock")).assertIsDisplayed()
        compose.runOnIdle { assertTrue(pantry.items.value.single().inStock) }
    }

    // #302: the field's example, and an amount typed before the name shown on the right at once.
    @Test
    fun anAmountTypedWithTheNameShowsOnTheRight() {
        val pantry = show()
        compose.onNodeWithTag("pantryDraft").performClick()
        compose.onNodeWithText("e.g. 3 onions").assertIsDisplayed()
        compose.onNodeWithTag("pantryDraft").performTextInput("3 onions")
        compose.onNodeWithTag("pantryDraft").performImeAction()

        val id = compose.runOnIdle { pantry.items.value.single().id }
        compose.onNodeWithText("onions").assertIsDisplayed()
        compose.onNodeWithTag("quantity-$id", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("3")
        compose.onNode(hasContentDescription("onions, 3, In stock")).assertIsDisplayed()
    }

    // Owner, 2026-09-29: no per-row button; the quantity as written sits on the right instead of
    // under the name, and TalkBack reads it after the name.
    @Test
    fun theRowShowsItsQuantityOnTheRightAndNoButton() {
        show(item(1, "chicken thighs", aisle = Aisle.MEAT, quantity = "2 lb"))
        compose.onNodeWithTag("quantity-1", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("2 lb")
        compose.onNodeWithTag("stockAction-1").assertDoesNotExist()
        compose.onNodeWithText("Ran out").assertDoesNotExist()
        compose.onNode(hasContentDescription("chicken thighs, 2 lb, In stock")).assertIsDisplayed()
    }

    // #146: running out puts it on the list silently; the row wears the Groceries tab's basket
    // tag (owner, 2026-09-29; it said "On list"), and tapping that takes it off again. No snackbar
    // either way. #194: a swipe towards the start runs it out, into the Run out section.
    @Test
    fun runningOutPutsItOnTheListAndTheTagTakesItOff() {
        val pantry = show(item(1, "milk", aisle = Aisle.DAIRY))
        compose.onNodeWithTag("onList-1").assertDoesNotExist()
        compose.onNodeWithTag("pantry-1").performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { !pantry.items.value.single().inStock }
        compose.onNode(runOutHeading).assertIsDisplayed()
        compose.onNodeWithText("Dairy & eggs").assertDoesNotExist()

        compose.waitUntil(5_000) { groceries.items.value.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(listOf("milk"), groceries.items.value.map { it.text })
            assertTrue(!pantry.items.value.single().inStock)
        }
        compose.onNodeWithTag("onList-1").assertIsDisplayed()
            .assert(hasContentDescription("On your grocery list"))
            .assert(SemanticsMatcher("its action is Remove from grocery list") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Remove from grocery list"
            })
        compose.onNodeWithText("On list").assertDoesNotExist()
        compose.onNodeWithText("Undo").assertDoesNotExist()

        compose.onNodeWithTag("onList-1").performClick()
        compose.waitUntil(5_000) { groceries.items.value.isEmpty() }
        compose.onNodeWithTag("onList-1").assertDoesNotExist()
        compose.runOnIdle { assertTrue(!pantry.items.value.single().inStock) }
    }

    // #194: the row's action marks it running low: a "Low" tag, still in its aisle, onto the
    // list; TalkBack reads the state and the tag, and offers the other states. (A touch and hold
    // opened a menu of these until #258 made it start selecting.)
    @Test
    fun theRowActionMarksItRunningLow() {
        val pantry = show(item(1, "garlic", aisle = Aisle.PRODUCE))
        compose.onNodeWithTag("pantry-1").performCustomAccessibilityActionWithLabel("Running low")

        compose.waitUntil(5_000) { groceries.items.value.isNotEmpty() }
        compose.runOnIdle { assertEquals(PantryStock.RUNNING_LOW, pantry.items.value.single().stock) }
        compose.onNodeWithTag("low-1", useUnmergedTree = true).assertIsDisplayed().assertTextEquals("Low")
        compose.onNodeWithText("Fruit & vegetables").assertIsDisplayed()
        compose.onNode(hasContentDescription("garlic, Running low, On your grocery list")).assertIsDisplayed()
            .assert(SemanticsMatcher("offers Restock and Ran out") { node ->
                node.config.getOrNull(SemanticsActions.CustomActions)?.map { it.label } == listOf("Restock", "Ran out", "Select")
            })
    }

    // Every state is reachable from something visible: the edit sheet's stock control, applied at
    // once through the row's own path (Running low goes on the list, and Restock brings it back).
    @Test
    fun theEditSheetSetsTheStock() {
        val pantry = show(item(1, "garlic", aisle = Aisle.PRODUCE))
        compose.onNodeWithText("garlic").performClick()
        compose.onNodeWithTag("pantryEditStock-IN_STOCK").assertIsSelected()
        compose.onNodeWithTag("pantryEditStock-RUNNING_LOW").performClick()

        compose.waitUntil(5_000) { groceries.items.value.isNotEmpty() }
        compose.runOnIdle { assertEquals(PantryStock.RUNNING_LOW, pantry.items.value.single().stock) }
        compose.onNodeWithTag("pantryEditStock-RUNNING_LOW").assertIsSelected()
        compose.onNodeWithTag("pantryEditStock-IN_STOCK").assertIsNotSelected()

        compose.onNodeWithTag("pantryEditStock-RUN_OUT").performClick()
        compose.waitUntil(5_000) { pantry.items.value.single().stock == PantryStock.RUN_OUT }
        compose.onNodeWithTag("pantryEditStock-IN_STOCK").performClick()
        compose.waitUntil(5_000) { pantry.items.value.single().stock == PantryStock.IN_STOCK }
        compose.runOnIdle { assertEquals(listOf("garlic"), groceries.items.value.map { it.text }) }
    }

    // With no row button (owner, 2026-09-29), the row's actions offer both other states: In stock →
    // Running low, Ran out; Running low → Restock, Ran out; Run out → Restock, Running low.
    @Test
    fun theRowActionsOfferBothOtherStates() {
        val pantry = show(item(1, "garlic", aisle = Aisle.PRODUCE))
        actionsOffer(1, "Running low", "Ran out")
        compose.onNodeWithTag("pantry-1").performCustomAccessibilityActionWithLabel("Running low")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("low-1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        actionsOffer(1, "Restock", "Ran out")
        compose.onNodeWithTag("pantry-1").performCustomAccessibilityActionWithLabel("Ran out")
        compose.waitUntil(5_000) { pantry.items.value.single().stock == PantryStock.RUN_OUT }
        actionsOffer(1, "Restock", "Running low")
    }

    // #258: a touch and hold starts selecting with that row checked; a tap then toggles rather
    // than opening the sheet; Remove takes them all with one Undo that puts them all back.
    @Test
    fun aTouchAndHoldSelectsThenRemoveAndUndo() {
        val pantry = show(item(1, "salt"), item(2, "rice"), item(3, "oats"), item(4, "flour"))
        val before = compose.runOnIdle { pantry.items.value }
        compose.onNodeWithText("rice").performTouchInput { longClick() }
        compose.onNodeWithTag("selectedCount").assertTextEquals("1 selected")
        compose.onNodeWithTag("pantry-2").assertIsOn()
        compose.onNodeWithText("salt").performClick()
        compose.onNodeWithText("oats").performClick()
        compose.onNodeWithTag("pantry-1").assertIsOn()
        compose.onNodeWithTag("pantry-4").assertIsOff()
        compose.onNodeWithTag("selectedCount").assertTextEquals("3 selected")
        compose.onNodeWithTag("pantryEditName").assertDoesNotExist()

        compose.onNodeWithTag("removeSelected").performClick()
        compose.waitUntil(5_000) { pantry.items.value.size == 1 }
        compose.onNodeWithTag("selectedCount").assertDoesNotExist()
        compose.onNodeWithText("3 items removed").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { pantry.items.value == before }
    }

    // #258: the menu's "Select items", Select all, Mark run out, and ✕.
    @Test
    fun theMenuSelectsAllAndMarksThemRunOut() {
        val pantry = show(item(1, "salt"), item(2, "rice"))
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithTag("selectItems").performClick()
        compose.onNodeWithTag("selectedCount").assertTextEquals("0 selected")
        compose.onNodeWithTag("removeSelected").assertIsNotEnabled()
        compose.onNodeWithTag("selectAll").performClick()
        compose.onNodeWithTag("selectedCount").assertTextEquals("2 selected")
        compose.onNodeWithTag("markRunOutSelected").performClick()
        compose.waitUntil(5_000) { pantry.items.value.none { it.inStock } }
        compose.onNodeWithText("2 items marked run out").assertIsDisplayed()

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithTag("selectItems").performClick()
        compose.onNodeWithTag("exitSelection").performClick()
        compose.onNodeWithTag("selectedCount").assertDoesNotExist()
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

    // Owner, 2026-09-29: an in-stock item shows the basket tag for a typed "2 onions" or a
    // recipe's "1 onion, sliced", not for "red onion"; tapping it removes both, with Undo.
    @Test
    fun theTagShowsForAnyLineNamingTheItemAndItsRemovalCanBeUndone() {
        kotlinx.coroutines.runBlocking {
            groceries.add(
                listOf(
                    com.example.recipeclipper.data.model.NewGroceryLine("2 onions", "en"),
                    com.example.recipeclipper.data.model.NewGroceryLine("1 onion, sliced", "en", recipeId = 7),
                    com.example.recipeclipper.data.model.NewGroceryLine("1 red onion", "en")
                )
            )
        }
        show(item(1, "onions", aisle = Aisle.PRODUCE), item(2, "garlic", aisle = Aisle.PRODUCE))
        compose.onNodeWithTag("onList-1").assertIsDisplayed()
        compose.onNodeWithTag("onList-2").assertDoesNotExist()

        compose.onNodeWithTag("onList-1").performClick()
        compose.waitUntil(5_000) { groceries.items.value.size == 1 }
        compose.runOnIdle { assertEquals(listOf("1 red onion"), groceries.items.value.map { it.text }) }
        compose.onNodeWithTag("onList-1").assertDoesNotExist()
        compose.onNodeWithText("Removed 2 items from groceries").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { groceries.items.value.size == 3 }
        compose.runOnIdle {
            assertEquals(listOf("2 onions", "1 onion, sliced", "1 red onion"), groceries.items.value.map { it.text })
        }
        compose.onNodeWithTag("onList-1").assertIsDisplayed()
    }

    // #194: "Clear run-out items" in the menu, disabled until something has run out; it asks
    // first, leaves the grocery list alone, and Undo puts the items back.
    @Test
    fun clearRunOutItemsAsksFirstAndCanBeUndone() {
        val pantry = show(item(1, "oil"), item(2, "milk", inStock = false, aisle = Aisle.DAIRY), item(3, "rice", inStock = false))
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear run-out items").assertIsEnabled().performClick()
        compose.onNodeWithText("Clear 2 run-out items?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clear 2 run-out items?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(3, pantry.items.value.size) }

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear run-out items").performClick()
        compose.onNodeWithTag("clearRunOutConfirm").performClick()
        compose.runOnIdle { assertEquals(listOf("oil"), pantry.items.value.map { it.name }) }
        compose.onNode(runOutHeading).assertDoesNotExist()
        compose.onNodeWithText("Run-out items cleared").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(listOf("oil", "milk", "rice"), pantry.items.value.map { it.name }) }
        compose.onNode(runOutHeading).assertIsDisplayed()

        // Nothing run out: the item is there, disabled.
        compose.runOnIdle { pantry.items.value = pantry.items.value.map { it.copy(inStock = true) } }
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear run-out items").assertIsNotEnabled()
    }
}
