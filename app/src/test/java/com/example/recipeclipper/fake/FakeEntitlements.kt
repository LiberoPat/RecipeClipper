package com.example.recipeclipper.fake

import com.example.recipeclipper.data.Entitlements
import com.example.recipeclipper.data.LibraryPolicy
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.UnlockState
import com.example.recipeclipper.data.model.LibraryLimit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [Entitlements] with no store: [purchaseOutcome] and [restoreOutcome] are what the next
 * purchase or restore answers, and UNLOCKED also unlocks [state]. Counts the calls.
 */
class FakeEntitlements(initial: UnlockState = UnlockState()) : Entitlements {
    override val state = MutableStateFlow(initial)
    var purchaseOutcome = PurchaseOutcome.UNLOCKED
    var restoreOutcome = PurchaseOutcome.NOTHING_TO_RESTORE
    var purchases = 0
        private set
    var restores = 0
        private set

    override suspend fun purchase(): PurchaseOutcome {
        purchases++
        return answer(purchaseOutcome)
    }

    override suspend fun restore(): PurchaseOutcome {
        restores++
        return answer(restoreOutcome)
    }

    private fun answer(outcome: PurchaseOutcome): PurchaseOutcome {
        when (outcome) {
            PurchaseOutcome.UNLOCKED -> state.value = state.value.copy(unlocked = true, pending = false)
            PurchaseOutcome.PENDING -> state.value = state.value.copy(pending = true)
            else -> Unit
        }
        return outcome
    }
}

/** A [LibraryPolicy] a test sets by hand. */
class FakeLibraryPolicy(initial: LibraryLimit) : LibraryPolicy {
    private val flow = MutableStateFlow(initial)
    override var limit: LibraryLimit
        get() = flow.value
        set(value) { flow.value = value }
    override val limits: Flow<LibraryLimit> = flow
}
