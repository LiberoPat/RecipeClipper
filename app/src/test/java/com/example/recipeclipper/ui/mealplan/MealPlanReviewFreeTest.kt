package com.example.recipeclipper.ui.mealplan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.remote.MealPlanImport
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeLibraryPolicy
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.edit.SharedTextInbox
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The meal-plan review (#352) is free, its fresh-food note's swap (#396) included (owner,
 * 2026-11-23: nothing is Pro today): the swap trades the two days for everyone, with no badge.
 * iOS: `FreeExtrasTests`.
 */
@RunWith(AndroidJUnit4::class)
class MealPlanReviewFreeTest {

    @get:Rule
    val compose = createComposeRule()

    private val inbox = SharedTextInbox()
    private val today = FakePlanCalendar.WEDNESDAY
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)
    private val entitlements = FakeEntitlements()

    private fun recipe(name: String, vararg lines: String) = Recipe(
        id = 0, name = name, image = null, ingredients = lines.toList(), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = ""
    )

    private fun show(): MealPlanReviewViewModel {
        val plan = MealPlanImport.Plan(
            listOf(
                MealPlanImport.Item(recipe("Omelette", "4 eggs"), today),
                MealPlanImport.Item(recipe("Salmon bowls", "2 salmon fillets", "1 cup rice"), today + 5)
            )
        )
        val key = inbox.offerPlan("shared", plan)
        val vm = MealPlanReviewViewModel(
            SavedStateHandle(mapOf(MealPlanReviewViewModel.PLAN_ARG to key)),
            FakeRecipeRepository(), FakeMealPlanRepository(), FakePlanCalendar(), FakeLibraryPolicy(LibraryLimit.Unlimited),
            entitlements, inbox, Dispatchers.Unconfined, flags
        )
        compose.setContent { MealPlanReviewScreen(onBack = {}, onFinished = {}, viewModel = vm) }
        return vm
    }

    @Test
    fun theNoteAndItsSwapWorkWithNoBadge() {
        val vm = show()
        compose.onNodeWithText("Salmon fillets: keeps about 1-2 days once bought. Cook it earlier?").assertIsDisplayed()
        compose.onNodeWithTag("proBadge", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("freshSwap").performClick()
        compose.waitForIdle()
        assertEquals(listOf(today + 5, today), vm.uiState.value.items.map { it.day })
    }
}
