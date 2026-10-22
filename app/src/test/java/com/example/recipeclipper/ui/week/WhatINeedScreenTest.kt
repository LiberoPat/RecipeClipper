package com.example.recipeclipper.ui.week

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

    private fun item(id: Long, name: String, quantity: String?, inStock: Boolean = true) =
        PantryItem(id, name, quantity, "en", Aisle.OTHER, inStock = inStock, alwaysHave = false, purchasedDay = null, expiresDay = null)

    /**
     * iOS's WeekUITests.testWhatINeedDrawsEveryRowOfBothSections (#185), with the pantry's
     * amounts (#302): two recipes use chicken thighs, which add up; the vinegar's lines don't
     * (a range), so only the pantry's amount shows; soy sauce has no amount, so nothing new.
     */
    @Test
    fun everyRowOfBothSectionsWithThePantrysAmountBesideTheWeeks() {
        groceries.planned = listOf(
            PlannedIngredients(1, day = 100, servings = null, recipeId = 3, title = "Chicken Adobo", ingredients = listOf(
                "2 lb chicken thighs", "1/2 cup soy sauce", "1/3 cup white vinegar", "6 cloves garlic, crushed",
                "3 bay leaves", "1 tsp black peppercorns"
            ), yield = null, language = "en"),
            PlannedIngredients(2, day = 102, servings = null, recipeId = 4, title = "Chicken soup",
                ingredients = listOf("1 lb chicken thighs", "2-3 tbsp white vinegar"), yield = null, language = "en")
        )
        show(
            item(1, "chicken thighs", "3 lb"), item(2, "white vinegar", "2 cups"), item(3, "soy sauce", null),
            item(4, "garlic", "1 head", inStock = false)
        )

        val list = compose.onNodeWithTag("whatINeed")
        for (text in listOf(
            "garlic", "bay leaves", "black peppercorns", "chicken thighs", "You have 3 lb · this week's recipes use 3 lb",
            "soy sauce", "You have soy sauce", "white vinegar", "You have 2 cups"
        )) {
            list.performScrollToNode(hasText(text))
            compose.onNodeWithText(text).assertIsDisplayed()
        }
        // Only the chicken adds up; nothing judges it, and the garlic that's out shows no amount.
        compose.onAllNodesWithText("this week's recipes use", substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("You have 1 head").assertCountEquals(0)
        compose.onAllNodesWithText("enough", substring = true, ignoreCase = true).assertCountEquals(1) // the note only
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
