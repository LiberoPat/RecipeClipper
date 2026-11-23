package com.example.recipeclipper.ui.pro

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.R
import com.example.recipeclipper.data.ProExtra
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.UnlockState
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeProAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Pro screen (#413), a real [ProViewModel] over the fake store: what Pro adds, one-time and
 * never a subscription, the store's price only when it gives one, Restore purchases, and the free
 * line. iOS: `ProViewModelTests`.
 */
@RunWith(AndroidJUnit4::class)
class ProScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)
    private var back = 0

    // Pro adds nothing today (2026-11-23), so the screen is shown with an extra of the test's own.
    private val testExtra = ProExtra(R.string.action_move, R.string.action_remove_from_plan)

    private fun show(entitlements: FakeEntitlements): ProViewModel {
        val vm = ProViewModel(FakeProAccess(flags, entitlements, listOf(testExtra)))
        compose.setContent { ProScreen(onBack = { back++ }, viewModel = vm) }
        return vm
    }

    @Test
    fun saysWhatProAddsThatItIsOneTimeAndThatTheAppIsFree() {
        show(FakeEntitlements())
        compose.onNodeWithText("Pith Pro").assertIsDisplayed()
        listOf("Move…", "Remove from plan").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("One-time purchase, never a subscription.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Free. No ads. No account. No subscription.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Restore purchases").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withNoPriceFromTheStoreTheButtonNamesNoFigure() {
        show(FakeEntitlements())
        compose.onNodeWithTag("proUnlock").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Unlock Pro").assertIsDisplayed()
    }

    @Test
    fun theStoresPriceIsShownWhenItHasOne() {
        show(FakeEntitlements(UnlockState(price = "€3.49")))
        compose.onNodeWithText("Unlock Pro for €3.49").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unlockingBuysOnceAndThanks() {
        val entitlements = FakeEntitlements()
        show(entitlements)
        compose.onNodeWithTag("proUnlock").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, entitlements.purchases)
        compose.onNodeWithTag("proUnlocked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("proUnlock").assertDoesNotExist()
    }

    @Test
    fun aRestoreWithNothingToRestoreSaysSo() {
        val entitlements = FakeEntitlements()
        show(entitlements)
        compose.onNodeWithTag("proRestore").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, entitlements.restores)
        compose.onNodeWithText("No purchase to restore on this account.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aPendingPurchaseSaysItIsPending() {
        val entitlements = FakeEntitlements().apply { purchaseOutcome = PurchaseOutcome.PENDING }
        show(entitlements)
        compose.onNodeWithTag("proUnlock").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("proNotice").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anOld107UnlockOpensAsPro() {
        show(FakeEntitlements(UnlockState(unlocked = true)))
        compose.onNodeWithTag("proUnlocked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("proUnlock").assertDoesNotExist()
    }

    @Test
    fun backGoesBack() {
        show(FakeEntitlements())
        compose.onNodeWithText("Back", substring = true).performClick()
        assertTrue(back == 1)
    }
}
