package com.example.recipeclipper.ui.week

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shop for this week's Buy later (#396) is free (owner, 2026-11-23: nothing is Pro today): the two
 * trips show for everyone, with no locked line, and "Add to groceries" adds Buy now's lines.
 * iOS: `FreeExtrasTests`.
 */
@RunWith(AndroidJUnit4::class)
class WhatINeedFreeTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = FakePlanCalendar.WEDNESDAY
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)

    private val groceries = FakeGroceryRepository().apply {
        planned = listOf(
            PlannedIngredients(1, day = today + 1, servings = null, recipeId = 7, title = "Pasta",
                ingredients = listOf("1 lb spaghetti"), yield = null, language = "en"),
            PlannedIngredients(2, day = today + 5, servings = null, recipeId = 9, title = "Salmon bowls",
                ingredients = listOf("2 salmon fillets"), yield = null, language = "en")
        )
    }

    private fun vm() = WhatINeedViewModel(
        SavedStateHandle(mapOf(WhatINeedViewModel.WEEK_START_ARG to today)),
        groceries, FakePantryRepository(), FakeAppPreferences(), null, FakePlanCalendar(), flags
    )

    private fun show(): WhatINeedViewModel {
        val vm = vm()
        compose.setContent { WhatINeedScreen(onBack = {}, viewModel = vm, shopForWeek = true) }
        return vm
    }

    @Test
    fun theTwoTripsShowWithNoLockedLine() {
        val vm = show()
        compose.onNodeWithText("Buy now").assertIsDisplayed()
        compose.onNodeWithTag("whatINeed").performScrollToNode(hasText("Buy later in the week"))
        compose.onNodeWithText("Buy later in the week").assertIsDisplayed()
        compose.onNodeWithTag("proTeaser").assertDoesNotExist()
        compose.onNodeWithTag("proBadge", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { assertNotNull(vm.uiState.value.shownTrips) }
    }

    @Test
    fun addToGroceriesAddsBuyNowAndBuyLaterAddsTheRest() {
        show()
        compose.onNodeWithTag("addBuyToGroceries").performClick()
        compose.runOnIdle { assertEquals(listOf("1 lb spaghetti"), groceries.items.value.map { it.text }) }
        compose.onNodeWithTag("whatINeed").performScrollToNode(hasTestTag("addLaterToGroceries"))
        compose.onNodeWithTag("addLaterToGroceries").performClick()
        compose.runOnIdle { assertEquals(listOf("1 lb spaghetti", "2 salmon fillets"), groceries.items.value.map { it.text }) }
    }

    @Test
    fun withNothingToBuyLaterToBuyIsOneList() {
        groceries.planned = groceries.planned.take(1)
        show()
        compose.onNodeWithText("To buy").assertIsDisplayed()
        compose.onNodeWithTag("proTeaser").assertDoesNotExist()
    }
}
