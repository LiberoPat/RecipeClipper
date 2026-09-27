package com.example.recipeclipper.ui.week

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "What I need" (#51): the week against the pantry, a real ViewModel over the fakes. */
@RunWith(AndroidJUnit4::class)
class WhatINeedScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val groceries = FakeGroceryRepository().apply {
        planned = listOf(
            PlannedIngredients(1, day = 100, servings = null, recipeId = 7, title = "Pancakes",
                ingredients = listOf("2 cups flour", "2 eggs"), yield = null, language = "en")
        )
    }

    private fun show(vararg pantry: PantryItem) {
        val viewModel = WhatINeedViewModel(
            SavedStateHandle(mapOf(WhatINeedViewModel.WEEK_START_ARG to 100L)),
            groceries, FakePantryRepository(pantry.toList()), FakeAppPreferences()
        )
        compose.setContent { WhatINeedScreen(onBack = {}, viewModel = viewModel) }
    }

    @Test
    fun haveAndBuyAreSeparateAndPresenceIsAllItClaims() {
        show(PantryItem(1, "Flour", null, "en", Aisle.BAKING, inStock = true, alwaysHave = false, purchasedDay = null, expiresDay = null))

        compose.onNodeWithText("To buy").assertIsDisplayed()
        compose.onNodeWithText("2 eggs").assertIsDisplayed()
        compose.onNodeWithTag("whatINeed").performScrollToNode(hasText("You have Flour"))
        compose.onNodeWithText("In your pantry").assertIsDisplayed()
        compose.onNodeWithText("not that there's enough", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2 cups flour").assertIsDisplayed()
    }

    @Test
    fun addingBuyPutsOnlyTheBuyLinesOnGroceries() {
        show(PantryItem(1, "flour", null, "en", Aisle.BAKING, inStock = true, alwaysHave = false, purchasedDay = null, expiresDay = null))

        compose.onNodeWithTag("addBuyToGroceries").performClick()
        compose.onNodeWithText("Added to groceries").assertIsDisplayed()
        compose.onNodeWithTag("addBuyToGroceries").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("2 eggs"), groceries.items.value.map { it.text }) }
    }
}
