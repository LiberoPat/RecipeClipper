package com.example.recipeclipper.ui.week

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.ui.groceries.AddToGroceriesViewModel
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Week tab (#49), a real WeekViewModel over fakes, today pinned to Wednesday 2026-09-23. */
@RunWith(AndroidJUnit4::class)
class WeekScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val plan = FakeMealPlanRepository()
    private val recipes = FakeRecipeRepository()
    private val today = FakePlanCalendar.WEDNESDAY

    private var opened: Pair<Long, Int?>? = null

    private val groceries = FakeGroceryRepository()

    private fun show() {
        val viewModel = WeekViewModel(plan, recipes, FakePlanCalendar())
        val groceriesViewModel = AddToGroceriesViewModel(groceries, FakeAppPreferences())
        compose.setContent {
            WeekScreen(
                onOpenRecipe = { id, servings -> opened = id to servings },
                onOpenMealTypes = {},
                viewModel = viewModel,
                groceriesViewModel = groceriesViewModel
            )
        }
    }

    private fun scrollTo(tag: String) =
        compose.onNodeWithTag("weekList").performScrollToNode(hasTestTag(tag))

    @Test
    fun aPlannedRecipeOpensAtItsPlannedServings() {
        plan.titles[7] = "Chicken Adobo"
        runBlocking { plan.addRecipe(7, today, DINNER, servings = 6) }
        show()

        val meal = plan.meals.value.single()
        scrollTo("meal-${meal.id}")
        compose.onNodeWithText("Chicken Adobo").assertIsDisplayed()
        compose.onNodeWithText("6 servings").assertIsDisplayed()
        compose.onNodeWithText("Chicken Adobo").performClick()

        compose.runOnIdle { assertEquals(7L to 6, opened) }
    }

    @Test
    fun removingFromTheLongPressMenuCanBeUndone() {
        plan.titles[7] = "Chicken Adobo"
        runBlocking { plan.addRecipe(7, today, DINNER, servings = null) }
        show()
        val meal = plan.meals.value.single()

        scrollTo("meal-${meal.id}")
        compose.onNodeWithTag("meal-${meal.id}").performTouchInput { longClick() }
        compose.onNodeWithText("Remove from plan").performClick()
        compose.runOnIdle { assertTrue(plan.meals.value.isEmpty()) }

        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(listOf(meal.id), plan.meals.value.map { it.id }) }
    }

    @Test
    fun plusOnADayAddsARecipeFromHistory() {
        recipes.history.value = listOf(RecipeSummary(5, "Miso Soup", null, null, 0, false))
        show()

        scrollTo("addToDay-${today + 1}")
        compose.onNodeWithTag("addToDay-${today + 1}").performClick()
        compose.onNodeWithText("Miso Soup").performClick()

        compose.runOnIdle {
            val meal = plan.meals.value.single()
            assertEquals(5L, meal.recipeId)
            assertEquals(today + 1, meal.day)
            assertEquals(DINNER, meal.mealTypeId)
        }
    }

    @Test
    fun typedTextIsAddedAsANote() {
        show()

        scrollTo("addToDay-$today")
        compose.onNodeWithTag("addToDay-$today").performClick()
        compose.onNodeWithTag("addSearch").performTextInput("Eat out")
        compose.onNode(hasText("Add “Eat out” as a note")).performClick()

        compose.runOnIdle { assertEquals("Eat out", plan.meals.value.single().note) }
    }

    @Test
    fun aMealMovesToAnotherDay() {
        runBlocking { plan.addNote("Leftovers", today, DINNER) }
        show()
        val meal = plan.meals.value.single()

        scrollTo("meal-${meal.id}")
        compose.onNodeWithTag("meal-${meal.id}").performTouchInput { longClick() }
        compose.onNodeWithText("Move…").performClick()
        compose.onNodeWithTag("planDay-${today + 2}").performClick()
        compose.onNode(hasText("Move to", substring = true)).performClick()

        compose.runOnIdle { assertEquals(today + 2, plan.meals.value.single().day) }
    }

    @Test
    fun theWeeksIngredientsGoOnTheGroceryListAtThePlannedServings() {
        groceries.planned = listOf(
            PlannedIngredients(
                entryId = 1, day = today, servings = 8, recipeId = 7, title = "Pancakes",
                ingredients = listOf("2 cups flour", "1 cup milk"), yield = "Serves 4", language = "en"
            )
        )
        show()

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Add this week's ingredients").performClick()
        compose.onNodeWithText("Pancakes").assertIsDisplayed()
        compose.onNodeWithText("4 cups flour").assertIsDisplayed()
        compose.onNodeWithTag("addToGroceriesButton").performClick()

        compose.runOnIdle {
            assertEquals(listOf("4 cups flour", "2 cup milk"), groceries.items.value.map { it.text })
            assertTrue(groceries.items.value.all { it.plannedDay == today })
        }
    }
}
