package com.example.recipeclipper.ui.groceries

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.fake.FakeDecisionRepository
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Groceries tab (#50), a real GroceriesViewModel over the fake repository. */
@RunWith(AndroidJUnit4::class)
class GroceriesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeGroceryRepository()

    private fun show(vararg lines: String, decisions: FakeDecisionRepository? = null) {
        runBlocking { repository.add(lines.map { NewGroceryLine(it, "en") }) }
        val viewModel = GroceriesViewModel(repository, FakePantryRepository(), FakePlanCalendar(), decisions)
        compose.setContent { GroceriesScreen(viewModel = viewModel) }
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("groceryList").performScrollToNode(hasTestTag(tag))

    @Test
    fun anEmptyListSaysHowToFillIt() {
        show()
        compose.onNodeWithText("Your list is empty", substring = true).assertIsDisplayed()
    }

    @Test
    fun aTypedItemLandsInItsAisle() {
        show()
        compose.onNodeWithTag("groceryDraft").performTextInput("milk")
        compose.onNodeWithTag("groceryDraft").performImeAction()

        compose.onNodeWithText("Dairy & eggs").assertIsDisplayed()
        compose.onNodeWithText("milk").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Aisle.DAIRY, repository.items.value.single().aisle) }
    }

    @Test
    fun theSameIngredientIsAddedUpOrKeptTogether() {
        show("200 g flour", "100 g flour", "1 cup sugar", "100 g sugar")

        compose.onNodeWithText("300 g flour").assertIsDisplayed()
        compose.onNodeWithText("200 g flour + 100 g flour").assertIsDisplayed()
        // Cups and grams of sugar can't be added up: both stay, under the name.
        compose.onNodeWithText("sugar").assertIsDisplayed()
        compose.onNodeWithText("1 cup sugar").assertIsDisplayed()
        compose.onNodeWithText("100 g sugar").assertIsDisplayed()
    }

    @Test
    fun junkDecidedByTheModelIsHidden() {
        val answers = mapOf(
            DecisionQuestion.ingredientName("2 onions dfsafs", "en") to "onions",
            DecisionQuestion.trailingText("dfsafs", "en") to "junk"
        )
        show("2 onions dfsafs", decisions = FakeDecisionRepository(answers))

        compose.onNodeWithText("2 onions").assertIsDisplayed()
        compose.onNodeWithText("2 onions dfsafs").assertDoesNotExist()
        compose.runOnIdle { assertEquals("2 onions dfsafs", repository.items.value.single().text) }
    }

    @Test
    fun tickingAnAddedUpRowTicksEveryLine() {
        show("200 g flour", "100 g flour")
        val first = repository.items.value.first().id
        compose.onNodeWithTag("grocery-$first").assertIsOff().performClick()
        compose.onNodeWithTag("grocery-$first").assertIsOn()
        compose.runOnIdle { assertTrue(repository.items.value.all { it.checked }) }
    }

    @Test
    fun deletingFromTheLongPressMenuCanBeUndone() {
        show("2 onions", "1 cup milk")
        val onions = repository.items.value.first().id
        scrollTo("grocery-$onions")
        compose.onNodeWithTag("grocery-$onions").performTouchInput { longClick() }
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(listOf("1 cup milk"), repository.items.value.map { it.text }) }

        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(listOf("2 onions", "1 cup milk"), repository.items.value.map { it.text }) }
    }

    @Test
    fun anItemCanMoveToAnotherAisle() {
        show("1 jar pickles")
        val id = repository.items.value.single().id
        compose.onNodeWithTag("grocery-$id").performTouchInput { longClick() }
        compose.onNodeWithText("Move to aisle…").performClick()
        compose.onNodeWithText("Oils, sauces & condiments").performClick()
        compose.runOnIdle { assertEquals(Aisle.CONDIMENTS, repository.items.value.single().aisle) }
    }

    @Test
    fun clearCheckedRemovesOnlyTheTickedItems() {
        show("2 onions", "1 cup milk")
        val onions = repository.items.value.first().id
        compose.onNodeWithTag("grocery-$onions").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Clear checked").performClick()
        compose.runOnIdle { assertEquals(listOf("1 cup milk"), repository.items.value.map { it.text }) }
    }

    // The owner's report: a recipe added three times showed "2 corn" three times, each with a
    // tick of its own, and ticking one ticked them all.
    @Test
    fun theSameRecipeAddedThreeTimesIsOneRowWithOneTick() {
        show("2 corn", "2 corn", "2 corn")
        compose.onNodeWithText("6 corn").assertIsDisplayed()
        compose.onNodeWithText("2 corn \u00d7 3").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
    }

    @Test
    fun linesThatCannotBeAddedUpAreOneRowWithOneTick() {
        show("2 corn (about 1 lb)", "2 corn (about 1 lb)", "1 cup corn kernels", "3 corn")
        compose.onNodeWithText("2 corn (about 1 lb) \u00d7 2").assertIsDisplayed()
        compose.onNodeWithText("3 corn").assertIsDisplayed()
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        val first = repository.items.value.first().id
        compose.onNodeWithTag("grocery-$first").assertIsOff().performClick()
        compose.onNodeWithTag("grocery-$first").assertIsOn()
        compose.runOnIdle {
            assertEquals(listOf(true, true, false, true), repository.items.value.map { it.checked })
        }
    }
}
