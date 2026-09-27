package com.example.recipeclipper.ui.week

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Reusable weekly menus on the Week tab (#52): a real WeekViewModel over fakes. */
@RunWith(AndroidJUnit4::class)
class WeekMenusScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val plan = FakeMealPlanRepository()
    private val today = FakePlanCalendar.WEDNESDAY
    private lateinit var viewModel: WeekViewModel

    private fun show() {
        viewModel = WeekViewModel(plan, FakeRecipeRepository(), FakePlanCalendar())
        compose.setContent { WeekScreen(onOpenRecipe = { _, _ -> }, onOpenMealTypes = {}, viewModel = viewModel) }
    }

    private fun openMenu(tag: String) {
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithTag(tag).performClick()
    }

    @Test
    fun anEmptyWeekCantBeSaved() {
        show()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithTag("saveWeekAsMenu").assertIsNotEnabled()
    }

    @Test
    fun theWeekIsSavedUnderTheTypedName() {
        runBlocking { plan.addNote("Soup", today, DINNER) }
        show()

        openMenu("saveWeekAsMenu")
        compose.onNodeWithTag("menuName").performTextInput("Week A")
        compose.onNodeWithText("Save").performClick()

        compose.runOnIdle { assertEquals(listOf("Week A"), plan.menus.value.map { it.name }) }
        compose.onNodeWithText("Saved “Week A”").assertExists()
    }

    @Test
    fun applyingAMenuAddsItsMealsToTheWeekShown() {
        runBlocking {
            plan.addNote("Soup", today, DINNER)
            plan.saveWeekAsMenu("Week A", weekStart)
        }
        show()
        viewModel.onNextWeek()

        openMenu("applyMenu")
        compose.onNodeWithText("Week A").performClick()

        compose.runOnIdle {
            assertEquals(listOf(today, today + 7), plan.meals.value.map { it.day })
        }
        compose.onNodeWithText("Added 1 meal from “Week A”").assertExists()
    }

    @Test
    fun aMenuIsRenamedAndDeletedFromTheSheet() {
        runBlocking {
            plan.addNote("Soup", today, DINNER)
            plan.saveWeekAsMenu("Week A", weekStart)
        }
        show()
        val id = plan.menus.value.single().id

        openMenu("applyMenu")
        compose.onNodeWithTag("menuOptions-$id").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNodeWithTag("menuName").performTextClearance()
        compose.onNodeWithTag("menuName").performTextInput("Autumn")
        compose.onNodeWithText("Rename").performClick()
        compose.runOnIdle { assertEquals("Autumn", plan.menus.value.single().name) }

        compose.onNodeWithTag("menuOptions-$id").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle {
            assertTrue(plan.menus.value.isEmpty())
            assertEquals(1, plan.meals.value.size) // the plan is untouched
        }
    }

    private val weekStart = PlanDays.weekStart(today, FakePlanCalendar().firstDayOfWeek())
}
