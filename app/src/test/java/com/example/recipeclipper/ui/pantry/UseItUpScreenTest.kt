package com.example.recipeclipper.ui.pantry

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.SavedRecipe
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Use it up (#305) on the Pantry tab: the row's line, its sheet, and one tap to plan. */
@RunWith(AndroidJUnit4::class)
class UseItUpScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val calendar = FakePlanCalendar()
    private val today = calendar.today()
    private val plan = FakeMealPlanRepository()

    private fun show() {
        val spinach = PantryItem(1, "spinach", null, "en", Aisle.PRODUCE, true, false, null, today + 1)
        val pantry = FakePantryRepository(listOf(spinach))
        val lists = FakeListRepository().apply {
            savedRecipes.value = listOf(
                SavedRecipe(10, "Saag paneer", null, "en", listOf("500 g spinach")),
                SavedRecipe(11, "Spinach pie", null, "en", listOf("300 g spinach, chopped"))
            )
        }
        val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)
        val useItUp = UseItUpViewModel(pantry, lists, plan, calendar, flags, UseItUpInbox())
        val viewModel = PantryViewModel(pantry, FakeGroceryRepository(), calendar)
        compose.setContent { PantryScreen(viewModel = viewModel, useItUpViewModel = useItUp) }
    }

    @Test fun `the row says how many saved recipes use it, and opens them`() {
        show()
        compose.onNodeWithTag("use-it-up-1").performClick()
        compose.onNodeWithText("2 saved recipes use it").assertExists()
        compose.onNodeWithText("Spinach · use by tomorrow").assertExists()
        compose.onNodeWithText("Saag paneer").assertExists()
        compose.onNodeWithText("Spinach pie").assertExists()
    }

    @Test fun `one tap plans it on the use-by day`() {
        show()
        compose.onNodeWithTag("use-it-up-1").performClick()
        compose.onNodeWithTag("useItUp-1-10").assertExists()
        compose.onAllNodesWithTextFirst("Plan tomorrow").performClick()
        compose.waitForIdle()
        assertEquals(listOf(10L to today + 1), plan.meals.value.map { it.recipeId to it.day })
        compose.onNodeWithText("Planned tomorrow").assertIsNotEnabled()
    }

    // #332: the line stays while selecting, so rows never change height under a dragging finger
    // (#258); a tap on it then toggles the row, as anywhere else on it.
    @Test fun `the line keeps its place while selecting, and a tap toggles the row`() {
        show()
        val before = compose.onNodeWithTag("pantry-1").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.onNodeWithText("spinach").performTouchInput { longClick() }
        compose.onNodeWithTag("selectedCount").assertTextEquals("1 selected")
        // The row is one checkbox now, so the line sits in its merged node.
        compose.onNodeWithTag("use-it-up-1", useUnmergedTree = true).assertExists()
        assertEquals(before, compose.onNodeWithTag("pantry-1").getUnclippedBoundsInRoot().let { it.bottom - it.top })
        compose.onNodeWithTag("use-it-up-1", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("pantry-1").assertIsOff()
        compose.onNodeWithTag("useItUpSheet").assertDoesNotExist()
    }

    // #332: the line's tap target is the minimum touch size, so a near miss opens the sheet
    // rather than the item's edit sheet.
    @Test fun `the line's tap target is at least 48 dp`() {
        show()
        compose.onNodeWithTag("use-it-up-1").assertHeightIsAtLeast(48.dp).assertHasClickAction()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextFirst(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text))[0]
}
