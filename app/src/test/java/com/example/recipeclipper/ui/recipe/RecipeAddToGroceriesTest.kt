package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.fake.FakeGroceryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Add to groceries" in the recipe screen's overflow menu (#50), only behind the tab flag. */
@RunWith(AndroidJUnit4::class)
class RecipeAddToGroceriesTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun addsTheTickedLinesAsTheReadingViewShowsThem() {
        val fixture = RecipeScreenFixture(groceries = FakeGroceryRepository())
        fixture.show(compose)
        val lines = RecipeScreenFixture.testRecipe().ingredients

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Add to groceries").performClick()
        // Untick the first line.
        compose.onNodeWithTag("sheetLine-recipe-${RecipeScreenFixture.RECIPE_ID}-0").performClick()
        compose.onNodeWithTag("addToGroceriesButton").performClick()

        compose.runOnIdle {
            val items = fixture.groceries!!.items.value
            assertEquals(lines.drop(1), items.map { it.text })
            assertTrue(items.all { it.recipeId == RecipeScreenFixture.RECIPE_ID })
        }
    }

    @Test
    fun theMenuHasNoAddToGroceriesWhileTheFlagIsOff() {
        RecipeScreenFixture().show(compose)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Edit").assertExists()
        compose.onNodeWithText("Add to groceries").assertDoesNotExist()
    }
}
