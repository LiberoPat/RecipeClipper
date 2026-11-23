package com.example.recipeclipper.fake

import com.example.recipeclipper.data.DefaultProAccess
import com.example.recipeclipper.data.Entitlements
import com.example.recipeclipper.data.ProAccess
import com.example.recipeclipper.data.ProExtra
import com.example.recipeclipper.data.ProState
import com.example.recipeclipper.data.PurchaseOutcome
import com.example.recipeclipper.data.flags.FeatureFlags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [ProAccess] as [DefaultProAccess] reads it, but with a chosen list of [extras]: today Pro adds
 * nothing (`ProExtras.all` is empty), so a test that needs the Pro page to exist names its own.
 */
class FakeProAccess(
    flags: FeatureFlags,
    entitlements: Entitlements,
    private val extras: List<ProExtra>
) : ProAccess {
    private val real = DefaultProAccess(flags, entitlements)

    override val current: ProState get() = real.current.copy(available = extras)
    override val states: Flow<ProState> = real.states.map { it.copy(available = extras) }

    override suspend fun purchase(): PurchaseOutcome = real.purchase()

    override suspend fun restore(): PurchaseOutcome = real.restore()
}
