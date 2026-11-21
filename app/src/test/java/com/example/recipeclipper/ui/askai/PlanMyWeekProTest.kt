package com.example.recipeclipper.ui.askai

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultProAccess
import com.example.recipeclipper.data.UnlockState
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeEntitlements
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
 * Plan my week's choices (#392, #367) under Pro (#413): without it Plan my week is still there, as
 * the free week of dinners it was before #392, and its choices are one locked line that opens the
 * Pro screen; with Pro the choices are back. Single-recipe prompts are never gated. iOS: `AskAiProTests`.
 */
@RunWith(AndroidJUnit4::class)
class PlanMyWeekProTest {

    @get:Rule
    val compose = createComposeRule()

    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)
    private val entitlements = FakeEntitlements()
    private var proOpened = 0
    private var dismissed = 0

    private fun viewModel() = AskAiViewModel(
        FakePantryRepository(), FakeListRepository(), FakeRecipeRepository(), FakeAppPreferences(), Clock { 1_800_000_000_000L }, null,
        FakeMealPlanRepository(), FakePlanChoices(), flags, DefaultProAccess(flags, entitlements)
    )

    private fun clipboard(): String? =
        ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()

    private fun show(vm: AskAiViewModel = viewModel()): AskAiViewModel {
        compose.setContent {
            RecipeClipperTheme { AskAiSheet(onDismiss = { dismissed++ }, viewModel = vm, onOpenPro = { proOpened++ }) }
        }
        compose.onNodeWithTag("askAiKind-PLAN_MY_WEEK").performClick()
        compose.waitForIdle()
        return vm
    }

    private fun count(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    @Test fun `locked, Plan my week is the free week of dinners and its choices are one locked line`() {
        val vm = show()
        assertTrue(flags.isOn(Flag.PLAN_MY_WEEK_PLUS))
        assertEquals(0, count("askAiPlan"))
        assertFalse(vm.uiState.value.plan)
        assertTrue(vm.uiState.value.planLocked)
        compose.onNodeWithText("Choose the meals, the first day and your own recipes").performScrollTo().assertIsDisplayed()

        compose.onNodeWithTag("askAiCopy").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(clipboard()!!.startsWith("Plan one dinner recipe for each of these days:"))
    }

    @Test fun `the locked line closes the sheet and opens the Pro screen`() {
        show()
        compose.onNodeWithTag("proTeaser").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, proOpened)
        assertEquals(1, dismissed)
    }

    @Test fun `with Pro the choices are back and there is no locked line`() {
        entitlements.state.value = UnlockState(unlocked = true)
        val vm = show()
        compose.onNodeWithTag("askAiPlan").assertIsDisplayed()
        assertEquals(0, count("proTeaser"))
        assertTrue(vm.uiState.value.plan)
        assertFalse(vm.uiState.value.planLocked)
    }

    @Test fun `the other prompts are never locked`() {
        val vm = viewModel()
        compose.setContent { RecipeClipperTheme { AskAiSheet(onDismiss = {}, viewModel = vm, onOpenPro = { proOpened++ }) } }
        compose.waitForIdle()
        assertEquals(0, count("proTeaser"))
        assertEquals(0, proOpened)
    }
}
