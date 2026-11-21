package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pro (#413) as [DefaultProAccess] reads it: the `proUnlock` flag, the one-time unlock of #107
 * (the same product, so an old unlock is Pro) and Developer settings' override. iOS: `ProAccessTests`.
 */
class ProAccessTest {

    private val store = FakeFeatureFlagStore()
    private val flags = FeatureFlags(store, FlagRegistry.definitions, isDebug = false)

    @Test fun `on by default in debug and release`() {
        assertTrue(FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true).isOn(Flag.PRO_UNLOCK))
        assertTrue(flags.isOn(Flag.PRO_UNLOCK))
    }

    @Test fun `with nothing bought the extras are locked`() = runTest {
        val pro = DefaultProAccess(flags, FakeEntitlements())
        assertEquals(ProState(gated = true, isPro = false), pro.current)
        assertTrue(pro.current.locked)
        assertFalse(pro.current.extras)
        assertEquals(pro.current, pro.states.first())
    }

    @Test fun `a cook who unlocked unlimited recipes under 107 is Pro`() = runTest {
        // The cached entitlement of #107, as PlayBillingEntitlements reads it at launch.
        val pro = DefaultProAccess(flags, FakeEntitlements(UnlockState(unlocked = true)))
        assertTrue(pro.current.isPro)
        assertTrue(pro.current.extras)
        assertTrue(pro.states.first().extras)
    }

    @Test fun `Developer settings' override counts as Pro, and Reset takes it away`() = runTest {
        val pro = DefaultProAccess(flags, FakeEntitlements())
        flags.setUnlockedOverride(true)
        assertTrue(pro.current.isPro)
        assertTrue(pro.states.first().extras)
        flags.reset()
        assertFalse(pro.current.isPro)
    }

    @Test fun `with the flag off nothing is gated, as before 413`() = runTest {
        flags.set(Flag.PRO_UNLOCK, false)
        val pro = DefaultProAccess(flags, FakeEntitlements())
        assertFalse(pro.current.gated)
        assertTrue(pro.current.extras)
        assertFalse(pro.current.locked)
    }

    @Test fun `the price is the store's, and there is none until it answers`() = runTest {
        val entitlements = FakeEntitlements()
        val pro = DefaultProAccess(flags, entitlements)
        assertNull(pro.current.price)
        entitlements.state.value = UnlockState(price = "2,99 €")
        assertEquals("2,99 €", pro.states.first().price)
    }

    @Test fun `a purchase goes through the one store, and unlocks`() = runTest {
        val entitlements = FakeEntitlements()
        val pro = DefaultProAccess(flags, entitlements)
        assertEquals(PurchaseOutcome.UNLOCKED, pro.purchase())
        assertEquals(1, entitlements.purchases)
        assertTrue(pro.states.first().isPro)
        assertEquals(PurchaseOutcome.NOTHING_TO_RESTORE, pro.restore())
        assertEquals(1, entitlements.restores)
    }

    @Test fun `without Pro only a reminder the cook switched on runs`() {
        val locked = ProState(gated = true)
        val pro = ProState(gated = true, isPro = true)
        // The evening reminder's default (on, never stored) is a Pro default.
        assertFalse(ProReminders.runs(on = true, chosen = false, pro = locked))
        assertTrue(ProReminders.runs(on = true, chosen = false, pro = pro))
        // One the cook turned on keeps running: nothing already set up is taken away.
        assertTrue(ProReminders.runs(on = true, chosen = true, pro = locked))
        assertFalse(ProReminders.runs(on = false, chosen = true, pro = pro))
        // Ungated (the flag off), as before.
        assertTrue(ProReminders.runs(on = true, chosen = false, pro = ProState()))
    }

    @Test fun `Ungated opens every extra`() {
        assertTrue(ProAccess.Ungated.current.extras)
    }
}
