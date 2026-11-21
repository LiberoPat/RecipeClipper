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
import com.example.recipeclipper.data.DefaultProAccess
import com.example.recipeclipper.data.UnlockState
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.PlannedIngredients
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Skip (#398) and the fresh-food swap (#396) on the Week under Pro (#413): without it both are
 * still there, with the Pro badge, and open the Pro screen instead of moving anything; the meals
 * already planned, skipped or swapped stay as they are. With Pro, as before. iOS: `WeekProTests`.
 */
@RunWith(AndroidJUnit4::class)
class WeekProTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = FakePlanCalendar.WEDNESDAY
    private val plan = FakeMealPlanRepository()
    private val groceries = FakeGroceryRepository()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)
    private val entitlements = FakeEntitlements()
    private var proOpened = 0

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

    private fun vm() = WeekViewModel(plan, FakeRecipeRepository(), FakePlanCalendar(), groceries, flags, DefaultProAccess(flags, entitlements))

    private fun show(vm: WeekViewModel = vm()): WeekViewModel {
        compose.setContent { WeekScreen(onOpenRecipe = { _, _ -> }, onOpenMealTypes = {}, viewModel = vm, onOpenPro = { proOpened++ }) }
        return vm
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("weekList").performScrollToNode(hasTestTag(tag))

    @Test
    fun lockedTodaysSkipHasTheBadgeAndOpensProMovingNothing() {
        planWeek()
        val vm = show()
        val pasta = plan.meals.value.first()
        scrollTo("skip-${pasta.id}")
        compose.onNodeWithTag("skip-${pasta.id}").assertIsDisplayed()
        assertEquals(true, compose.onAllNodesWithTag("proBadge", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("skip-${pasta.id}").performClick()
        assertEquals(1, proOpened)
        compose.runOnIdle { assertNull(vm.uiState.value.skipping) }
        assertEquals(listOf(today, today + 5), plan.meals.value.map { it.day })
    }

    @Test
    fun lockedTheLongPressSkipOpensProAndRemoveStaysFree() {
        planWeek()
        show()
        val pasta = plan.meals.value.first()
        scrollTo("meal-${pasta.id}")
        compose.onNodeWithTag("meal-${pasta.id}").performTouchInput { longClick() }
        compose.onNodeWithTag("skipMenu-${pasta.id}").performClick()
        assertEquals(1, proOpened)

        compose.onNodeWithTag("meal-${pasta.id}").performTouchInput { longClick() }
        compose.onNodeWithText("Remove from plan").performClick()
        compose.runOnIdle { assertEquals(listOf(today + 5), plan.meals.value.map { it.day }) }
        assertEquals(1, proOpened)
    }

    @Test
    fun lockedTheFreshNoteStaysAndItsSwapOpensPro() {
        planWeek()
        show()
        val salmon = plan.meals.value[1]
        scrollTo("fresh-${salmon.id}")
        compose.onNodeWithText("Salmon fillets: keeps about 1-2 days once bought. Cook it earlier?").assertIsDisplayed()
        compose.onNodeWithTag("freshSwap").performClick()
        assertEquals(1, proOpened)
        assertEquals(listOf(today, today + 5), plan.meals.value.map { it.day })
    }

    @Test
    fun lockedTheViewModelNeitherSkipsNorSwaps() {
        planWeek()
        val vm = vm()
        val (pasta, salmon) = plan.meals.value
        vm.onSkipStart(pasta)
        vm.onFreshSwap(salmon.id)
        assertNull(vm.uiState.value.skipping)
        assertEquals(listOf(today, today + 5), plan.meals.value.map { it.day })
    }

    @Test
    fun withProSkipAndTheSwapWorkAsBeforeAndShowNoBadge() {
        entitlements.state.value = UnlockState(unlocked = true)
        planWeek()
        show()
        val (pasta, salmon) = plan.meals.value
        scrollTo("skip-${pasta.id}")
        compose.onAllNodesWithTag("proBadge", useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNodeWithTag("skip-${pasta.id}").performClick()
        compose.onNodeWithText("Skip Pasta").assertIsDisplayed()
        assertEquals(0, proOpened)
    }
}
