package com.example.recipeclipper.ui.week

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Shop for this week's two groups (#396), a real ViewModel over the fakes. iOS: `WeekUITests`. */
@RunWith(AndroidJUnit4::class)
class WhatINeedFreshScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = FakePlanCalendar.WEDNESDAY

    private val groceries = FakeGroceryRepository().apply {
        planned = listOf(
            PlannedIngredients(1, day = today + 1, servings = null, recipeId = 7, title = "Pasta",
                ingredients = listOf("1 lb spaghetti"), yield = null, language = "en"),
            PlannedIngredients(2, day = today + 5, servings = null, recipeId = 9, title = "Salmon bowls",
                ingredients = listOf("2 salmon fillets", "1 bunch basil"), yield = null, language = "en")
        )
    }

    private fun show(freshFood: Boolean = true) {
        val flags = FeatureFlags(FakeFeatureFlagStore(mapOf("freshFood" to freshFood)), FlagRegistry.definitions, isDebug = true)
        val viewModel = WhatINeedViewModel(
            SavedStateHandle(mapOf(WhatINeedViewModel.WEEK_START_ARG to today)),
            groceries, FakePantryRepository(), FakeAppPreferences(), null, FakePlanCalendar(), flags
        )
        compose.setContent { WhatINeedScreen(onBack = {}, viewModel = viewModel, shopForWeek = true) }
    }

    @Test
    fun toBuyIsTwoGroupsEachSayingTheDayItIsFor() {
        show()

        compose.onNodeWithText("Buy now").assertIsDisplayed()
        compose.onNodeWithText("1 lb spaghetti").assertIsDisplayed()
        compose.onNodeWithTag("whatINeed").performScrollToNode(hasText("Buy later in the week"))
        compose.onNodeWithText("Buy later in the week").assertIsDisplayed()
        compose.onNodeWithText("For meals 3 or more days away", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("whatINeed").performScrollToNode(hasText("2 salmon fillets"))
        compose.onNodeWithText("2 salmon fillets").assertIsDisplayed()
        // The day each later row is for, and what its group keeps: a range, never a date.
        compose.onAllNodesWithTag("laterFor").assertCountEquals(2)
        compose.onAllNodesWithText("Keeps about 1-2 days once bought").assertCountEquals(1)
        compose.onAllNodesWithText("Keeps about 2-3 days once bought").assertCountEquals(1)
        // The week's lines are unchanged: each still says its recipe.
        compose.onAllNodesWithText("Salmon bowls", substring = true).assertCountEquals(2) // salmon's and basil's line
    }

    @Test
    fun addToGroceriesAddsBuyNowAndTheLaterGroupHasItsOwnButton() {
        show()

        compose.onNodeWithTag("addBuyToGroceries").performClick()
        compose.onNodeWithTag("addBuyToGroceries").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("1 lb spaghetti"), groceries.items.value.map { it.text }) }

        compose.onNodeWithTag("whatINeed").performScrollToNode(hasText("Add these to groceries"))
        compose.onNodeWithTag("addLaterToGroceries").performClick()
        compose.onNodeWithTag("addLaterToGroceries").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("1 lb spaghetti", "2 salmon fillets", "1 bunch basil"), groceries.items.value.map { it.text }) }
    }

    @Test
    fun withTheFlagOffToBuyIsOneList() {
        show(freshFood = false)

        compose.onNodeWithText("To buy").assertIsDisplayed()
        compose.onNodeWithText("Buy now").assertDoesNotExist()
        compose.onNodeWithText("Buy later in the week").assertDoesNotExist()
        compose.onNodeWithTag("whatINeed").performScrollToNode(hasText("2 salmon fillets"))
        compose.onNodeWithText("2 salmon fillets").assertIsDisplayed()
        compose.onNodeWithText("Keeps about", substring = true).assertDoesNotExist()
    }
}
