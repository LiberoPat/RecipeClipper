package com.example.recipeclipper.ui.groceries

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagValues
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.ui.common.LocalFlagValues
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Add to groceries sheet says why a line started unticked (#457): "In your pantry" under the
 * line the pantry has, and under no other. A real [AddToGroceriesViewModel] over fakes. iOS:
 * `AddToGroceriesPantryLabelUITests`.
 */
@RunWith(AndroidJUnit4::class)
class AddToGroceriesPantryLabelTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(label: Boolean = true) {
        val pantry = FakePantryRepository(
            listOf(PantryItem(1, "flour", null, "en", Aisle.OTHER, inStock = true, alwaysHave = false, purchasedDay = 1, expiresDay = null))
        )
        val viewModel = AddToGroceriesViewModel(FakeGroceryRepository(), FakeAppPreferences(), pantry)
        viewModel.setRecipe(7, "Pancakes", "en", listOf("2 cups flour", "2 eggs"))
        compose.setContent {
            CompositionLocalProvider(LocalFlagValues provides FlagValues(if (label) setOf(Flag.GROCERY_PANTRY_LABEL) else emptySet())) {
                AddToGroceriesSheet(viewModel, onDismiss = {})
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theLabelShowsOnThePantryLineOnly() {
        show()
        compose.onNodeWithTag("sheetLine-recipe-7-0").assertIsOff()
        compose.onNodeWithTag("sheetLine-recipe-7-1").assertIsOn()
        // The row is one toggleable node, so its text is the line and, for the pantry line, the label.
        compose.onNodeWithTag("sheetLine-recipe-7-0").assertTextContains("In your pantry")
        compose.onNodeWithTag("sheetLine-recipe-7-1").assert(!hasText("In your pantry", substring = true))
        compose.onAllNodesWithText("In your pantry", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("In your pantry", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun theLabelStaysWhenTheLineIsTickedAnyway() {
        show()
        compose.onNodeWithTag("sheetLine-recipe-7-0").performClick()
        compose.onNodeWithTag("sheetLine-recipe-7-0").assertIsOn()
        compose.onNodeWithTag("sheetLine-recipe-7-0").assertTextContains("In your pantry")
    }

    @Test
    fun withTheFlagOffThereIsNoLabel() {
        show(label = false)
        compose.onNodeWithTag("sheetLine-recipe-7-0").assertIsOff()
        compose.onAllNodesWithText("In your pantry", useUnmergedTree = true).assertCountEquals(0)
    }
}
