package com.example.recipeclipper.ui.askai

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanChoices
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The "Plan my week" part of Ask an AI's sheet (#392, #367) over a real ViewModel and fakes. */
@RunWith(AndroidJUnit4::class)
class PlanMyWeekSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = 1_800_000_000_000L
    private val recipes = FakeRecipeRepository()
    private val choices = FakePlanChoices()

    private fun viewModel(on: Boolean = true) = AskAiViewModel(
        FakePantryRepository(), FakeListRepository(), recipes, FakeAppPreferences(), Clock { now }, null,
        FakeMealPlanRepository(), choices,
        FeatureFlags(FakeFeatureFlagStore(mapOf(Flag.PLAN_MY_WEEK_PLUS.key to on)), FlagRegistry.definitions, isDebug = true)
    )

    private fun clipboard(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()

    private fun show(on: Boolean = true) {
        compose.setContent { RecipeClipperTheme { AskAiSheet(onDismiss = {}, viewModel = viewModel(on)) } }
        compose.onNodeWithTag("askAiKind-PLAN_MY_WEEK").performClick()
        compose.waitForIdle()
    }

    private fun summary(id: Long, title: String) = RecipeSummary(
        id = id, title = title, imageUrl = null, totalTime = null, lastViewedAt = id, isSaved = true, sourceUrl = "https://example.com/$id"
    )

    @Test fun `the plan shows the meals with Dinner ticked, and a tick changes the prompt`() {
        show()
        compose.onNodeWithTag("askAiPlan").assertIsDisplayed()
        compose.onNodeWithTag("askAiMeal-dinner").assertIsDisplayed()
        compose.onNodeWithTag("askAiMeal-breakfast").performScrollTo().performClick()
        compose.onNodeWithTag("askAiCopy").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()

        assertTrue(clipboard()!!.startsWith("Plan one recipe for each of these meals (Breakfast, Dinner) on each of these days:"))
        assertEquals(listOf("breakfast", "dinner"), choices.planMealKeys)
    }

    @Test fun `the start day is chosen on the strip, and the first day listed follows`() {
        show()
        val today = PlanDays.today(now)
        compose.onNodeWithTag("planDay-${today + 2}").performScrollTo().performClick()
        compose.onNodeWithTag("askAiCopy").performScrollTo().performClick()
        compose.waitForIdle()
        val prompt = clipboard()!!
        assertTrue(prompt.contains("- ${com.example.recipeclipper.data.model.AskAiPrompt.isoDate(today + 2)} ("))
        assertTrue(!prompt.contains(com.example.recipeclipper.data.model.AskAiPrompt.isoDate(today + 1)))
    }

    @Test fun `with no meal ticked it says so and copying is off`() {
        show()
        compose.onNodeWithTag("askAiMeal-dinner").performScrollTo().performClick()
        compose.onNodeWithTag("askAiMealsNone").assertIsDisplayed()
        compose.onNodeWithTag("askAiCopy").assertIsNotEnabled()
    }

    @Test fun `from my recipes says how many names are sent, and has none to send with an empty library`() {
        show()
        compose.onNodeWithTag("askAiSource-MINE").performScrollTo().performClick()
        compose.onNodeWithTag("askAiPlanNoRecipes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("askAiCopy").assertIsNotEnabled()

        recipes.history.value = listOf(summary(1, "Adobo"), summary(2, "Pancakes"))
        compose.waitForIdle()
        compose.onNodeWithTag("askAiPlanSent").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sending 2 of your 2 recipes, the most recently viewed first.").assertIsDisplayed()
        compose.onNodeWithTag("askAiCopy").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(clipboard()!!.contains("- Pancakes | https://example.com/2\n- Adobo | https://example.com/1"))
    }

    @Test fun `a mix has a stepper for how many of mine`() {
        recipes.history.value = listOf(summary(1, "Adobo"))
        show()
        compose.onNodeWithTag("askAiSource-MIX").performScrollTo().performClick()
        compose.onNodeWithText("Up to 3 of mine, the rest new").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("askAiMixMore").performScrollTo().performClick()
        compose.onNodeWithText("Up to 4 of mine, the rest new").assertIsDisplayed()
        compose.onNodeWithTag("askAiMixLess").performClick()
        compose.onNodeWithTag("askAiMixLess").performClick()
        compose.onNodeWithText("Up to 2 of mine, the rest new").assertIsDisplayed()
    }

    @Test fun `many meals warn that the answer may be cut off`() {
        show()
        compose.onNodeWithTag("askAiMeal-lunch").performScrollTo().performClick()
        compose.onNodeWithTag("askAiMeal-breakfast").performScrollTo().performClick()
        compose.onNodeWithTag("askAiPlanLong").performScrollTo().assertIsDisplayed()
    }

    @Test fun `without the flag the sheet is today's, with no choices`() {
        show(on = false)
        assertEquals(0, compose.onAllNodesWithTagCount("askAiPlan"))
        compose.onNodeWithText("A week of new dinners, shaped by what you like").assertIsDisplayed()
        compose.onNodeWithTag("askAiCopy").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(clipboard()!!.startsWith("Plan one dinner recipe for each of these days:"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
