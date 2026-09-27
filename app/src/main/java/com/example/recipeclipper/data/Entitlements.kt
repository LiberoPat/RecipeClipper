package com.example.recipeclipper.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The store's product id of the one-time unlock (#107), the same on both stores. */
const val UNLIMITED_RECIPES_PRODUCT_ID = "unlimited_recipes"

/**
 * What the store says about the unlock. [price] is the store's own formatted price, null until
 * it has answered (or when it can't: no Play account, no product yet). [pending] is a purchase
 * waiting for payment (Play's cash or slow card payments; Ask to Buy on iOS).
 */
data class UnlockState(
    val unlocked: Boolean = false,
    val pending: Boolean = false,
    val price: String? = null
)

/** How a purchase or a restore ended. */
enum class PurchaseOutcome {
    UNLOCKED,
    PENDING,
    CANCELLED,

    /** A restore that found no purchase on this store account. */
    NOTHING_TO_RESTORE,

    /** The store couldn't be reached, the product isn't set up, or the store refused. */
    FAILED
}

/**
 * The one-time, non-consumable unlock (#107). A seam: [PlayBillingEntitlements] is the real
 * one (Google Play Billing), tests use `FakeEntitlements`. ViewModels see only this, never the
 * Billing library. Purchases are acknowledged by the implementation, so Play never refunds
 * them after three days.
 */
interface Entitlements {
    /** The current state, then every change (including purchases finished elsewhere). */
    val state: StateFlow<UnlockState>

    /** Starts the store's purchase sheet and waits for its answer. */
    suspend fun purchase(): PurchaseOutcome

    /** Asks the store again for this account's purchases. */
    suspend fun restore(): PurchaseOutcome

    /** No store at all: locked, and every purchase fails. The default where none is given. */
    object Unavailable : Entitlements {
        override val state: StateFlow<UnlockState> = MutableStateFlow(UnlockState())
        override suspend fun purchase() = PurchaseOutcome.FAILED
        override suspend fun restore() = PurchaseOutcome.FAILED
    }
}

/** Whether [this] outcome is worth a word to the user: a wait or a failure. */
val PurchaseOutcome.needsNotice: Boolean
    get() = this == PurchaseOutcome.PENDING || this == PurchaseOutcome.FAILED ||
        this == PurchaseOutcome.NOTHING_TO_RESTORE
