package com.example.recipeclipper.ui.askai

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeListRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePantryRepository
import com.example.recipeclipper.fake.FakePlanChoices
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.ui.theme.RecipeClipperTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan my week's choices (#392, #367) are free for everyone (owner, 2026-11-23: nothing is Pro
 * today): the meals, the start day and the cook's own recipes show with no locked line. iOS: `FreeExtrasTests`.
 */
@RunWith(AndroidJUnit4::class)
class PlanMyWeekFreeTest {

    @get:Rule
    val compose = createComposeRule()

    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)

    private fun viewModel() = AskAiViewModel(
        FakePantryRepository(), FakeListRepository(), FakeRecipeRepository(), FakeAppPreferences(), Clock { 1_800_000_000_000L }, null,
        FakeMealPlanRepository(), FakePlanChoices(), flags
    )

    private fun clipboard(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()

    private fun show(vm: AskAiViewModel = viewModel()): AskAiViewModel {
        compose.setContent {
            RecipeClipperTheme { AskAiSheet(onDismiss = {}, viewModel = vm) }
        }
        compose.onNodeWithTag("askAiKind-PLAN_MY_WEEK").performClick()
        compose.waitForIdle()
        return vm
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test fun `the choices are there for everyone and there is no locked line`() {
        val vm = show()
        assertTrue(flags.isOn(Flag.PLAN_MY_WEEK_PLUS))
        compose.onNodeWithTag("askAiPlan").assertIsDisplayed()
        assertEquals(0, count("proTeaser"))
        assertEquals(0, count("proBadge"))
        assertTrue(vm.uiState.value.plan)
    }

    @Test fun `the prompt carries the choices, not the plain week of dinners`() {
        show()
        compose.onNodeWithTag("askAiCopy").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(clipboard()!!.startsWith("Plan one dinner recipe for each of these days:"))
    }

    @Test fun `the other prompts have no locked line either`() {
        val vm = viewModel()
        compose.setContent { RecipeClipperTheme { AskAiSheet(onDismiss = {}, viewModel = vm) } }
        compose.waitForIdle()
        assertEquals(0, count("proTeaser"))
    }
}
