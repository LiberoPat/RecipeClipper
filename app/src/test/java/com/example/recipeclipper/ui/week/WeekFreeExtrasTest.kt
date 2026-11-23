package com.example.recipeclipper.ui.week

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
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
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Skip (#398) and the fresh-food swap (#396) on the Week are free for everyone (owner, 2026-11-23:
 * nothing is Pro today): no badge, no Pro screen, and each does its job. iOS: `FreeExtrasTests`.
 */
@RunWith(AndroidJUnit4::class)
class WeekFreeExtrasTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = FakePlanCalendar.WEDNESDAY
    private val plan = FakeMealPlanRepository()
    private val groceries = FakeGroceryRepository()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)

    /** Pasta tonight, salmon bowls five days on: the salmon gets a note, and tonight's pasta a Skip. */
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
            PlannedIngredients(salmon.id, today + 5, null, 9, "Salmon bowls", listOf("2 salmon fillets"), null, "en")
        )
    }

    private fun vm() = WeekViewModel(plan, FakeRecipeRepository(), FakePlanCalendar(), groceries, flags)

    private fun show(vm: WeekViewModel = vm()): WeekViewModel {
        compose.setContent { WeekScreen(onOpenRecipe = { _, _ -> }, onOpenMealTypes = {}, viewModel = vm) }
        return vm
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("weekList").performScrollToNode(hasTestTag(tag))

    private fun assertNoBadge() =
        assertEquals(0, compose.onAllNodesWithTag("proBadge", useUnmergedTree = true).fetchSemanticsNodes().size)

    @Test
    fun todaysSkipHasNoBadgeAndOpensTheSheet() {
        planWeek()
        show()
        val pasta = plan.meals.value.first()
        scrollTo("skip-${pasta.id}")
        compose.onNodeWithTag("skip-${pasta.id}").assertIsDisplayed()
        assertNoBadge()
        compose.onNodeWithTag("skip-${pasta.id}").performClick()
        compose.onNodeWithText("Skip Pasta").assertIsDisplayed()
    }

    @Test
    fun theLongPressSkipHasNoBadgeAndOpensTheSheet() {
        planWeek()
        show()
        val pasta = plan.meals.value.first()
        scrollTo("meal-${pasta.id}")
        compose.onNodeWithTag("meal-${pasta.id}").performTouchInput { longClick() }
        assertNoBadge()
        compose.onNodeWithTag("skipMenu-${pasta.id}").performClick()
        compose.onNodeWithText("Skip Pasta").assertIsDisplayed()
    }

    @Test
    fun theFreshNoteSwapHasNoBadgeAndTradesTheDays() {
        planWeek()
        show()
        val salmon = plan.meals.value[1]
        scrollTo("fresh-${salmon.id}")
        compose.onNodeWithText("Salmon fillets: keeps about 1-2 days once bought. Cook it earlier?").assertIsDisplayed()
        assertNoBadge()
        compose.onNodeWithTag("freshSwap").performClick()
        compose.runOnIdle {
            val after = plan.meals.value.associateBy { it.id }
            assertEquals(today, after.getValue(salmon.id).day)
            assertEquals(today + 5, after.getValue(plan.meals.value.first { it.id != salmon.id }.id).day)
        }
    }

    @Test
    fun theViewModelOpensTheSkipSheetWithoutPro() {
        planWeek()
        val vm = vm()
        vm.onSkipStart(plan.meals.value.first())
        assertNotNull(vm.uiState.value.skipping)
    }
}
