package com.example.recipeclipper.ui.week

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fresh food's note on the Week's planned meals (#396), through the screen: a perishable meal
 * planned later than its group keeps from the plan's first day gets a quiet note and one swap.
 * The ViewModel's side is `WeekFreshNudgeTest`. iOS: `WeekFreshNudgeTests`.
 */
@RunWith(AndroidJUnit4::class)
class WeekFreshNudgeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = FakePlanCalendar.WEDNESDAY
    private val plan = FakeMealPlanRepository()
    private val groceries = FakeGroceryRepository()

    /** Pasta tonight, salmon bowls five days on: more than a salmon fillet keeps from tonight. */
    private fun planWeek() {
        plan.titles[7] = "Pasta"
        plan.titles[9] = "Salmon bowls"
        runBlocking {
            plan.addRecipe(7, today, DINNER, null)
            plan.addRecipe(9, today + 5, DINNER, null)
        }
        val (pasta, salmon) = plan.meals.value
        groceries.planned = listOf(
            PlannedIngredients(pasta.id, today, null, 7, "Pasta", listOf("1 lb spaghetti"), null, "en"),
            PlannedIngredients(salmon.id, today + 5, null, 9, "Salmon bowls", listOf("2 salmon fillets", "1 cup rice"), null, "en")
        )
    }

    private fun show(on: Boolean) {
        val flags = FeatureFlags(FakeFeatureFlagStore(mapOf("freshFood" to on)), FlagRegistry.definitions, isDebug = true)
        val vm = WeekViewModel(plan, FakeRecipeRepository(), FakePlanCalendar(), groceries, flags)
        compose.setContent { WeekScreen(onOpenRecipe = { _, _ -> }, onOpenMealTypes = {}, viewModel = vm) }
    }

    @Test
    fun theNoteNamesTheIngredientAndWhatItsGroupKeepsAndMovesNothingByItself() {
        planWeek()
        show(on = true)
        val (pasta, salmon) = plan.meals.value

        compose.onNodeWithTag("weekList").performScrollToNode(hasTestTag("fresh-${salmon.id}"))
        compose.onNodeWithText("Salmon fillets: keeps about 1-2 days once bought. Cook it earlier?").assertIsDisplayed()
        compose.onNodeWithText("Swap with", substring = true).assertIsDisplayed()
        // Nothing moved on its own.
        assertEquals(listOf(today, today + 5), plan.meals.value.map { it.day })

        compose.onNodeWithTag("freshSwap").performClick()
        compose.waitForIdle()
        val days = plan.meals.value.associate { it.id to it.day }
        assertEquals(today + 5, days[pasta.id])
        assertEquals(today, days[salmon.id])
        // The perishable meal is on the first day now, so the note is gone.
        compose.onNodeWithText("Cook it earlier?", substring = true).assertDoesNotExist()
    }

    @Test
    fun withTheFlagOffThereIsNoNote() {
        planWeek()
        show(on = false)
        compose.onNodeWithTag("weekList").performScrollToNode(hasTestTag("meal-${plan.meals.value[1].id}"))
        compose.onNodeWithText("Cook it earlier?", substring = true).assertDoesNotExist()
    }
}
