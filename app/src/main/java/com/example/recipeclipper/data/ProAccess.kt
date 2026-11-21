package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pro (#413) at one moment: the one place a screen learns whether the Week planning extras are
 * open. [gated] is the `proUnlock` flag (off, nothing is gated: the app as before #413). [isPro]
 * is the one-time unlock of #107, bought on this store account or counted as bought in Developer
 * settings: a cook who unlocked "unlimited recipes" is Pro, since it is the same product and the
 * same cached entitlement. [price] is the store's own formatted price, null until it has answered
 * (never a figure written here); [pending] a purchase waiting for payment.
 */
data class ProState(
    val gated: Boolean = false,
    val isPro: Boolean = false,
    val pending: Boolean = false,
    val price: String? = null
) {
    /** The Week planning extras may be used: nothing is gated, or the cook has Pro. */
    val extras: Boolean get() = !gated || isPro

    /** An extra shows its Pro badge and opens the Pro screen instead of acting. */
    val locked: Boolean get() = !extras
}

/**
 * Reads [ProState] (#413) for ViewModels and the reminders, and runs the purchase and restore,
 * which are [Entitlements]' (Play Billing, #107). ViewModels collect [states]; nothing reads a
 * build constant. [Ungated] is the default where none is given (tests that don't care): every
 * extra open, as before #413.
 */
interface ProAccess {
    val current: ProState

    /** [current], then every change (a purchase finished elsewhere, a refund, a flag, the override). */
    val states: Flow<ProState>

    suspend fun purchase(): PurchaseOutcome

    suspend fun restore(): PurchaseOutcome

    object Ungated : ProAccess {
        override val current = ProState()
        override val states: Flow<ProState> = flowOf(current)
        override suspend fun purchase() = PurchaseOutcome.FAILED
        override suspend fun restore() = PurchaseOutcome.FAILED
    }
}

@Singleton
class DefaultProAccess @Inject constructor(
    private val flags: FeatureFlags,
    private val entitlements: Entitlements
) : ProAccess {

    override val current: ProState
        get() = state(flags.isOn(Flag.PRO_UNLOCK), flags.unlockedOverride, entitlements.state.value)

    override val states: Flow<ProState> =
        combine(flags.values, flags.unlockedOverrides, entitlements.state) { values, override, store ->
            state(values.isOn(Flag.PRO_UNLOCK), override, store)
        }.distinctUntilChanged()

    override suspend fun purchase(): PurchaseOutcome = entitlements.purchase()

    override suspend fun restore(): PurchaseOutcome = entitlements.restore()

    private fun state(gated: Boolean, override: Boolean, store: UnlockState) =
        ProState(gated = gated, isPro = store.unlocked || override, pending = store.pending, price = store.price)
}

/**
 * The evening and Tonight reminders under Pro (#397, #413). Locked, a reminder the cook switched
 * on themselves keeps running (nothing already set up is taken away) and can still be turned off
 * or retimed; only switching one on needs Pro. The evening reminder's default (on, never stored)
 * is a Pro default: a cook without Pro who never touched the switch gets no reminder.
 */
object ProReminders {
    /** Whether a reminder stored as [on] runs: [chosen] is whether the cook ever set its switch. */
    fun runs(on: Boolean, chosen: Boolean, pro: ProState): Boolean = on && (pro.extras || chosen)
}
