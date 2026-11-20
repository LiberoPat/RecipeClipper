package com.example.recipeclipper.ui.week

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.ui.groceries.AddToGroceriesViewModel
import com.example.recipeclipper.ui.plan.dayTitle
import com.example.recipeclipper.ui.plan.fullDayTitle
import com.example.recipeclipper.ui.plan.monthTitle
import com.example.recipeclipper.ui.plan.weekRange
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.passTheSearchDebounce
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

    private fun show(flags: FeatureFlags? = null) {
        val viewModel = WeekViewModel(plan, recipes, FakePlanCalendar(), flags)
        val groceriesViewModel = AddToGroceriesViewModel(groceries, FakeAppPreferences(), FakePantryRepository())
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
        passTheSearchDebounce() // the sheet lists history through the search
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

    // --- Skip tonight (#398)

    private fun planThree() {
        plan.titles[7] = "Chicken Adobo"
        plan.titles[8] = "Miso Soup"
        plan.titles[9] = "Dal"
        runBlocking {
            plan.addRecipe(7, today, DINNER, servings = null)
            plan.addRecipe(8, today + 1, DINNER, servings = null)
            plan.addRecipe(9, today + 2, DINNER, servings = null)
        }
    }

    private fun dayOf(recipeId: Long) = plan.meals.value.single { it.recipeId == recipeId }.day

    @Test
    fun skipInTheLongPressMenuOffersThreeChoicesAndPushingTheWeekCanBeUndone() {
        planThree()
        show()
        val tomorrows = plan.meals.value.first { it.recipeId == 8L }

        scrollTo("meal-${tomorrows.id}")
        compose.onNodeWithTag("meal-${tomorrows.id}").performTouchInput { longClick() }
        compose.onNodeWithTag("skipMenu-${tomorrows.id}").performClick()
        compose.onNodeWithText("Skip Miso Soup").assertIsDisplayed()
        compose.onNodeWithText("Push the rest of the week back a day").assertIsDisplayed()
        compose.onNodeWithText("Move to the next free day").assertIsDisplayed()
        compose.onNodeWithText("Remove from the plan").assertIsDisplayed()
        compose.onNodeWithText("Push the rest of the week back a day").performClick()

        compose.runOnIdle {
            assertEquals(today, dayOf(7)) // before it: never moves
            assertEquals(today + 2, dayOf(8))
            assertEquals(today + 3, dayOf(9))
        }
        compose.onNodeWithText("Pushed the rest of the week back a day").assertIsDisplayed()

        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle {
            assertEquals(listOf(today, today + 1, today + 2), listOf(7L, 8L, 9L).map(::dayOf))
        }
    }

    @Test
    fun movingToTheNextFreeDayAndRemovingBothGoThroughTheSameSheet() {
        planThree()
        runBlocking { plan.addNote("Eat out", today + 1, DINNER) } // tomorrow's dinner is taken twice over
        show()
        val tonight = plan.meals.value.first { it.recipeId == 7L }

        scrollTo("meal-${tonight.id}")
        compose.onNodeWithTag("meal-${tonight.id}").performTouchInput { longClick() }
        compose.onNodeWithTag("skipMenu-${tonight.id}").performClick()
        compose.onNodeWithText("Move to the next free day").performClick()
        compose.runOnIdle { assertEquals(today + 3, dayOf(7)) }
        compose.onNode(hasText("Moved Chicken Adobo to", substring = true)).assertIsDisplayed()

        val soup = plan.meals.value.first { it.recipeId == 8L }
        scrollTo("meal-${soup.id}")
        compose.onNodeWithTag("meal-${soup.id}").performTouchInput { longClick() }
        compose.onNodeWithTag("skipMenu-${soup.id}").performClick()
        compose.onNodeWithText("Remove from the plan").performClick()
        compose.runOnIdle { assertTrue(plan.meals.value.none { it.recipeId == 8L }) }
        compose.onNodeWithText("Removed Miso Soup from the plan").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(today + 1, dayOf(8)) }
    }

    @Test
    fun todaysMealsHaveAVisibleSkipButtonAndOtherDaysDoNot() {
        planThree()
        show()
        val tonight = plan.meals.value.first { it.recipeId == 7L }
        val tomorrows = plan.meals.value.first { it.recipeId == 8L }

        scrollTo("meal-${tonight.id}")
        compose.onNodeWithTag("skip-${tonight.id}").assertIsDisplayed()
        scrollTo("meal-${tomorrows.id}")
        compose.onNodeWithTag("skip-${tomorrows.id}").assertDoesNotExist()

        scrollTo("meal-${tonight.id}")
        compose.onNodeWithTag("skip-${tonight.id}").performClick()
        compose.onNodeWithText("Skip Chicken Adobo").assertIsDisplayed()
    }

    @Test
    fun aMealInThePastHasNoSkip() {
        plan.titles[7] = "Chicken Adobo"
        runBlocking { plan.addRecipe(7, today - 2, DINNER, servings = null) }
        show()
        val meal = plan.meals.value.single()

        scrollTo("meal-${meal.id}")
        compose.onNodeWithTag("meal-${meal.id}").performTouchInput { longClick() }
        compose.onNodeWithText("Move…").assertIsDisplayed()
        compose.onNodeWithTag("skipMenu-${meal.id}").assertDoesNotExist()
        compose.onNodeWithTag("skip-${meal.id}").assertDoesNotExist()
    }

    @Test
    fun withTheFlagOffThereIsNoSkipAnywhere() {
        planThree()
        show(FeatureFlags(FakeFeatureFlagStore(mapOf(Flag.SKIP_TONIGHT.key to false)), FlagRegistry.definitions, true))
        val tonight = plan.meals.value.first { it.recipeId == 7L }

        scrollTo("meal-${tonight.id}")
        compose.onNodeWithTag("skip-${tonight.id}").assertDoesNotExist()
        compose.onNodeWithTag("meal-${tonight.id}").performTouchInput { longClick() }
        compose.onNodeWithText("Move…").assertIsDisplayed()
        compose.onNodeWithTag("skipMenu-${tonight.id}").assertDoesNotExist()
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

    @Test
    fun theMonthViewMarksPlannedDaysAndATapScrollsToThatDay() {
        plan.titles[7] = "Chicken Adobo"
        val nextFriday = today + 9
        runBlocking { plan.addRecipe(7, nextFriday, DINNER, servings = null) }
        show()

        compose.onNodeWithTag("toggleMonth").performClick()
        // The labels follow the device's locale, so they are built the way the screen builds them.
        compose.onNodeWithTag("monthTitle").assertTextEquals(monthTitle(today))
        compose.onNodeWithTag("monthDay-$nextFriday")
            .assertContentDescriptionEquals("${dayTitle(nextFriday)}, meals planned")
        compose.onNodeWithTag("monthDay-$today").assertContentDescriptionEquals(dayTitle(today))

        compose.onNodeWithTag("monthDay-$nextFriday").performClick()
        // The day tapped is at the top, and the header names its block from today (#232).
        compose.onNodeWithText("Chicken Adobo").assertIsDisplayed()
        compose.onNodeWithTag("addToDay-$today").assertDoesNotExist()
        compose.onNodeWithTag("weekRange").assertTextEquals(weekRange(today + 7))
        compose.onNodeWithTag("toggleMonth").assertTextEquals("Month")
    }

    @Test
    fun theDayStripMarksPlannedDaysAndATapScrollsToThatDay() {
        plan.titles[7] = "Chicken Adobo"
        val monday = today + 5
        runBlocking { plan.addRecipe(7, monday, DINNER, servings = null) }
        show()

        // The seven days the header names (#286), read aloud as full dates.
        compose.onNodeWithTag("stripDay-$today").assertContentDescriptionEquals(fullDayTitle(today))
        compose.onNodeWithTag("stripDay-$monday")
            .assertContentDescriptionEquals("${fullDayTitle(monday)}, meals planned")
        compose.onNodeWithTag("stripDay-${today + 7}").assertDoesNotExist()
        // An empty day says so, above its "+ Add".
        compose.onNodeWithTag("nothingPlanned-$today").assertTextEquals("Nothing planned")

        compose.onNodeWithTag("stripDay-$monday").performClick()
        // That day at the top; the header and the strip stay on this week.
        compose.onNodeWithText("Chicken Adobo").assertIsDisplayed()
        compose.onNodeWithTag("addToDay-$today").assertDoesNotExist()
        compose.onNodeWithTag("nothingPlanned-$monday").assertDoesNotExist()
        compose.onNodeWithTag("weekRange").assertTextEquals(weekRange(today))
        compose.onNodeWithTag("stripDay-$today").assertIsDisplayed()

        // › moves the strip with the header.
        compose.onNodeWithContentDescription("Next week").performClick()
        compose.onNodeWithTag("stripDay-${today + 7}").assertIsDisplayed()
        compose.onNodeWithTag("stripDay-$today").assertDoesNotExist()
    }

    @Test
    fun itOpensOnTodayAndTheArrowsSnapToTheNextAndPreviousWeek() {
        show()
        // Today at the top, its week from today (#232).
        compose.onNodeWithTag("weekRange").assertTextEquals(weekRange(today))
        compose.onNodeWithTag("addToDay-$today").assertIsDisplayed()
        compose.onNodeWithText("Today", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("todayButton").assertDoesNotExist()

        compose.onNodeWithContentDescription("Next week").performClick()
        compose.onNodeWithTag("weekRange").assertTextEquals(weekRange(today + 7))
        compose.onNodeWithTag("addToDay-${today + 7}").assertIsDisplayed()
        compose.onNodeWithTag("addToDay-$today").assertDoesNotExist()

        compose.onNodeWithContentDescription("Previous week").performClick()
        compose.onNodeWithContentDescription("Previous week").performClick()
        compose.onNodeWithTag("weekRange").assertTextEquals(weekRange(today - 7))

        compose.onNodeWithTag("todayButton").performClick()
        compose.onNodeWithTag("weekRange").assertTextEquals(weekRange(today))
        compose.onNodeWithTag("addToDay-$today").assertIsDisplayed()
        compose.onNodeWithTag("todayButton").assertDoesNotExist()
    }

    @Test
    fun freeScrollingMovesTheWeekWithTheTopDay() {
        show()
        compose.onNodeWithTag("weekList").performScrollToIndex(365 + 10)
        compose.onNodeWithTag("weekRange").assertTextEquals(weekRange(today + 7))
        compose.onNodeWithTag("weekList").performScrollToIndex(365 - 1)
        compose.onNodeWithTag("weekRange").assertTextEquals(weekRange(today - 7))
    }
}
