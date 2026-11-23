package com.example.recipeclipper.data

import androidx.annotation.StringRes
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** One thing Pro adds: a title and a line for the Pro screen's list. */
data class ProExtra(@StringRes val title: Int, @StringRes val body: Int)

/**
 * What Pro adds, the one list per platform. **Empty** (owner, 2026-11-23): the Week extras of #413
 * are all free, and Pro is kept for future AI features. While it is empty nothing is gated, and
 * Settings has no Pro row and no screen opens the Pro page; the first entry brings them back.
 */
object ProExtras {
    val all: List<ProExtra> = emptyList()
}

/**
 * Pro (#413) at one moment: the one place a screen learns whether the Pro extras are open
 * ([available] says which there are, none today). [gated] is the `proUnlock` flag (off, nothing is gated: the app as before #413). [isPro]
 * is the one-time unlock of #107, bought on this store account or counted as bought in Developer
 * settings: a cook who unlocked "unlimited recipes" is Pro, since it is the same product and the
 * same cached entitlement. [price] is the store's own formatted price, null until it has answered
 * (never a figure written here); [pending] a purchase waiting for payment.
 */
data class ProState(
    val gated: Boolean = false,
    val isPro: Boolean = false,
    val pending: Boolean = false,
    val price: String? = null,
    /** What Pro adds ([ProExtras.all]); the Pro page and its Settings row exist only while this isn't empty. */
    val available: List<ProExtra> = ProExtras.all
) {
    /** The Pro extras may be used: nothing is gated, or the cook has Pro. */
    val extras: Boolean get() = !gated || isPro

    /** An extra shows its Pro badge and opens the Pro screen instead of acting. */
    val locked: Boolean get() = !extras

    /** Whether Settings lists the Pro page: the flag is on and Pro adds something. */
    val showsPage: Boolean get() = gated && available.isNotEmpty()
}

/**
 * Reads [ProState] (#413) for ViewModels, and runs the purchase and restore,
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
