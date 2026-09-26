package com.example.recipeclipper.ui.tour

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.FirstRunTour
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.WelcomeState
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTourPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The welcome as it draws (#151), over a real ViewModel and fakes (iOS: WelcomeUITests). */
@RunWith(AndroidJUnit4::class)
class WelcomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val preferences = FakeTourPreferences(welcome = WelcomeState.PENDING)
    private val recipes = FakeRecipeRepository()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = false)

    private var done = 0
    private var opened: Long? = null

    private fun show(mealPlan: Boolean, chefMode: Boolean = false) {
        flags.set(Flag.MEAL_PLAN, mealPlan)
        flags.set(Flag.CHEF_MODE, chefMode)
        val viewModel = WelcomeViewModel(FirstRunTour(preferences, recipes), flags, SavedStateHandle())
        compose.setContent {
            WelcomeScreen(onDone = { done++ }, onOpenRecipe = { opened = it }, viewModel = viewModel)
        }
    }

    @Test
    fun theCardsWalkThroughTheAppAndTheLastOffersTheSample() {
        show(mealPlan = true, chefMode = true)

        compose.onNodeWithText("Just the recipe").assertIsDisplayed()
        compose.onNodeWithContentDescription("Card 1 of 4").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Clip a recipe").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Every day").assertIsDisplayed()
        compose.onNodeWithText("Chef mode, in Settings, shortens long steps on phones that can.").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Every week").assertIsDisplayed()
        compose.onNodeWithContentDescription("Card 4 of 4").assertExists()
        compose.onNodeWithText("Skip").assertDoesNotExist()

        compose.onNodeWithText("Try it with a sample recipe").performClick()
        compose.waitUntil { opened != null }
        assertEquals(99L, opened)
        assertEquals(WelcomeState.SEEN, preferences.welcome)
    }

    @Test
    fun withTheMealPlanOffTheDailyCardIsLastAndChefModeIsNotNamed() {
        show(mealPlan = false)

        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Every day").assertIsDisplayed()
        compose.onNodeWithContentDescription("Card 3 of 3").assertExists()
        compose.onNodeWithText("Chef mode, in Settings, shortens long steps on phones that can.").assertDoesNotExist()
        compose.onNodeWithText("Next").assertDoesNotExist()

        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Clip a recipe").assertIsDisplayed()
    }

    @Test
    fun skipLeavesAndMarksItSeen() {
        show(mealPlan = false)

        compose.onNodeWithText("Skip").performClick()
        compose.waitUntil { done == 1 }
        assertEquals(WelcomeState.SEEN, preferences.welcome)
    }

    @Test
    fun startOnTheLastCardLeavesWithoutOpeningARecipe() {
        show(mealPlan = false)
        repeat(2) { compose.onNodeWithText("Next").performClick() }

        compose.onNodeWithText("Start").performClick()
        compose.waitUntil { done == 1 }
        assertEquals(null, opened)
    }
}
