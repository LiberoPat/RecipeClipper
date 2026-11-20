package com.example.recipeclipper.ui.week

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.flags.FlagValues
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.ui.common.LocalFlagValues
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shop for this week's rows name their recipe as a small tag (#422), as Groceries does: in Buy
 * now, Buy later and In your pantry; a tap opens the recipe at the servings the week plans it at.
 * iOS: `WeekUITests`.
 */
@RunWith(AndroidJUnit4::class)
class WhatINeedRecipeTagsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = FakePlanCalendar.WEDNESDAY
    private val opened = mutableListOf<Pair<Long, Int?>>()

    private val groceries = FakeGroceryRepository().apply {
        planned = listOf(
            PlannedIngredients(1, day = today + 1, servings = 6, recipeId = 7, title = "Pasta",
                ingredients = listOf("1 lb spaghetti"), yield = null, language = "en"),
            PlannedIngredients(2, day = today + 5, servings = null, recipeId = 9, title = "Salmon bowls",
                ingredients = listOf("2 salmon fillets"), yield = null, language = "en"),
            PlannedIngredients(3, day = today + 2, servings = 4, recipeId = 8, title = "Pancakes",
                ingredients = listOf("2 cups flour"), yield = null, language = "en")
        )
    }

    private fun show(tags: Boolean = true) {
        val flags = FeatureFlags(FakeFeatureFlagStore(mapOf("freshFood" to true)), FlagRegistry.definitions, isDebug = true)
        val flour = PantryItem(1, "Flour", null, "en", Aisle.BAKING, inStock = true, alwaysHave = false, purchasedDay = null, expiresDay = null)
        val viewModel = WhatINeedViewModel(
            SavedStateHandle(mapOf(WhatINeedViewModel.WEEK_START_ARG to today)),
            groceries, FakePantryRepository(listOf(flour)), FakeAppPreferences(), null, FakePlanCalendar(), flags
        )
        compose.setContent {
            CompositionLocalProvider(LocalFlagValues provides FlagValues(if (tags) setOf(Flag.GROCERY_RECIPE_TAGS) else emptySet())) {
                WhatINeedScreen(
                    onBack = {}, viewModel = viewModel, shopForWeek = true,
                    onOpenRecipe = { id, servings -> opened += id to servings }
                )
            }
        }
    }

    private fun scrollTo(description: String) =
        compose.onNodeWithTag("whatINeed").performScrollToNode(hasContentDescription(description))

    @Test
    fun everySectionNamesItsRecipeAsATag() {
        show()

        // Buy now, Buy later, In your pantry: each line's recipe, with its day, apart from the amount.
        compose.onNodeWithText("1 lb spaghetti").assertIsDisplayed()
        compose.onNodeWithText("Pasta · Thu").assertIsDisplayed()
        compose.onNodeWithContentDescription("For Pasta, Thursday").assertIsDisplayed()
        scrollTo("For Salmon bowls, Monday")
        compose.onNodeWithContentDescription("For Salmon bowls, Monday").assertIsDisplayed()
        scrollTo("For Pancakes, Friday")
        compose.onNodeWithContentDescription("For Pancakes, Friday").assertIsDisplayed()
    }

    @Test
    fun aTapOpensTheRecipeAtTheServingsTheWeekPlansIt() {
        show()

        compose.onNodeWithContentDescription("For Pasta, Thursday").performClick()
        scrollTo("For Salmon bowls, Monday")
        compose.onNodeWithContentDescription("For Salmon bowls, Monday").performClick()
        scrollTo("For Pancakes, Friday")
        compose.onNodeWithContentDescription("For Pancakes, Friday").performClick()
        // Planned at 6, its own yield (null), and 4.
        compose.runOnIdle { assertEquals(listOf<Pair<Long, Int?>>(7L to 6, 9L to null, 8L to 4), opened) }
    }

    @Test
    fun withTheFlagOffEachLineKeepsTheMutedRecipeLine() {
        show(tags = false)

        compose.onNodeWithText("Pasta · Thu").assertIsDisplayed()
        compose.onNodeWithContentDescription("For Pasta, Thursday").assertDoesNotExist()
    }
}
