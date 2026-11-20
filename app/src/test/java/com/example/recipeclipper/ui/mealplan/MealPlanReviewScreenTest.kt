package com.example.recipeclipper.ui.mealplan

import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.MealPlanImport
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeLibraryPolicy
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.edit.SharedTextInbox
import com.example.recipeclipper.ui.navigation.Routes
import com.example.recipeclipper.ui.navigation.Tab
import com.example.recipeclipper.ui.navigation.mealPlanDestination
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The meal-plan review (#352) over a real ViewModel and fakes, and its route through a NavHost. */
@RunWith(AndroidJUnit4::class)
class MealPlanReviewScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val inbox = SharedTextInbox()
    private val recipes = FakeRecipeRepository()
    private val week = FakeMealPlanRepository()
    private val policy = FakeLibraryPolicy(LibraryLimit.Unlimited)
    private val today = FakePlanCalendar.WEDNESDAY

    private fun recipe(name: String, id: Long = 0) = Recipe(
        id = id, name = name, image = null, ingredients = listOf("1 egg"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = ""
    )

    private fun plan(vararg names: String) = MealPlanImport.Plan(
        names.mapIndexed { i, name -> MealPlanImport.Item(recipe(name), today + i) }
    )

    private lateinit var nav: NavHostController

    /** The review as the route opens it: the plan waiting in the inbox under the key the route carries. */
    private fun show(plan: MealPlanImport.Plan) {
        val key = inbox.offerPlan("shared", plan)
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) { Text("stub:home") }
                composable(Tab.WEEK.route) { Text("stub:week") }
                mealPlanDestination(nav) { entry ->
                    val routeKey = entry.arguments?.getString(MealPlanReviewViewModel.PLAN_ARG).orEmpty()
                    remember(routeKey) {
                        MealPlanReviewViewModel(
                            SavedStateHandle(mapOf(MealPlanReviewViewModel.PLAN_ARG to routeKey)),
                            recipes, week, FakePlanCalendar(), policy, FakeEntitlements(), inbox, Dispatchers.Unconfined
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("stub:home").assertIsDisplayed()
        compose.runOnIdle { nav.navigate(Routes.mealPlan(key)) }
        compose.waitForIdle()
    }

    @Test
    fun theWeekIsListedTickedWithItsDaysAndSavedOnlyOnSave() {
        recipes.addManualQueue.add(recipe("Omelette", 1))
        recipes.addManualQueue.add(recipe("Soup", 2))
        show(plan("Omelette", "Soup"))

        compose.onNodeWithText("Your week").assertIsDisplayed()
        compose.onNodeWithText("Omelette").assertIsDisplayed()
        compose.onNodeWithText("Soup").assertIsDisplayed()
        compose.onNodeWithText("Keeping 2 of 2").assertIsDisplayed()
        assertTrue(recipes.addManualCalls.isEmpty())

        compose.onNodeWithTag("mealplan_save").performClick()
        compose.waitForIdle()

        assertEquals(listOf("Omelette", "Soup"), recipes.addManualCalls.map { it.name })
        assertEquals(2, week.meals.value.size)
        // Planned: Week opens in place of the review.
        compose.onNodeWithText("stub:week").assertIsDisplayed()
    }

    @Test
    fun aRecipeUntickedIsLeftOut() {
        recipes.addManualQueue.add(recipe("Soup", 2))
        show(plan("Omelette", "Soup"))

        compose.onNodeWithText("Omelette").performClick()
        compose.onNodeWithText("Keeping 1 of 2").assertIsDisplayed()
        compose.onNodeWithTag("mealplan_save").performClick()
        compose.waitForIdle()

        assertEquals(listOf("Soup"), recipes.addManualCalls.map { it.name })
    }

    @Test
    fun aCutOffAnswerSaysSoAndOffersAMessageForTheAI() {
        show(MealPlanImport.Plan(plan("Omelette", "Soup").items, cutOff = true))

        compose.onNodeWithTag("mealplan_cut_off").assertIsDisplayed()
        compose.onNodeWithTag("cutoff_copy").assertIsDisplayed()
        compose.onNodeWithTag("cutoff_copy").performClick()
        compose.waitForIdle()

        // The confirmation takes the button's place; nothing was saved.
        compose.onNodeWithTag("cutoff_copied").assertIsDisplayed()
        assertTrue(recipes.addManualCalls.isEmpty())
    }

    @Test
    fun aWholeAnswerHasNoCutOffNote() {
        show(plan("Omelette", "Soup"))
        compose.onNodeWithTag("mealplan_cut_off").assertDoesNotExist()
    }

    @Test
    fun theFreeLimitSaysHowManyFitAndOffersUnlock() {
        policy.limit = LibraryLimit.Capped(50)
        recipes.count.value = 49
        show(plan("Omelette", "Soup"))

        compose.onNodeWithTag("mealplan_limit").assertIsDisplayed()
        compose.onNodeWithText("room for 1 more", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("mealplan_wont_fit_1").assertIsDisplayed()
        compose.onNodeWithText("Unlock").assertIsDisplayed()
    }

    @Test
    fun aDayIsChosenFromTheMenuOrDropped() {
        recipes.addManualQueue.add(recipe("Omelette", 1))
        recipes.addManualQueue.add(recipe("Soup", 2))
        show(plan("Omelette", "Soup"))

        compose.onNodeWithTag("mealplan_day_1").performClick()
        compose.onNodeWithText("Don't plan").performClick()
        compose.onNodeWithTag("mealplan_save").performClick()
        compose.waitForIdle()

        assertEquals(listOf(1L), week.meals.value.map { it.recipeId })
    }
}
