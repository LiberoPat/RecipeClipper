package com.example.recipeclipper.ui.recipe

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Add to plan" in the recipe screen's overflow menu (#49), only behind the tab flag. */
@RunWith(AndroidJUnit4::class)
class RecipeAddToPlanTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun addsTheRecipeOnTheChosenDayAsDinnerAtItsYield() {
        val fixture = RecipeScreenFixture(plan = FakeMealPlanRepository())
        fixture.show(compose)
        val friday = FakePlanCalendar.WEDNESDAY + 2

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Add to plan").performClick()
        compose.onNodeWithTag("planDay-$friday").performClick()
        compose.onNode(hasText("Add to ", substring = true) and hasText("Friday", substring = true)).performClick()

        compose.runOnIdle {
            val meal = fixture.plan!!.meals.value.single()
            assertEquals(RecipeScreenFixture.RECIPE_ID, meal.recipeId)
            assertEquals(friday, meal.day)
            assertEquals(FakeMealPlanRepository.DINNER, meal.mealTypeId)
            assertEquals(4, meal.servings)
        }
    }

    @Test
    fun theMenuHasNoAddToPlanWhileTheFlagIsOff() {
        RecipeScreenFixture().show(compose)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Edit").assertExists()
        compose.onNodeWithText("Add to plan").assertDoesNotExist()
    }
}
