package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.model.LibraryLimit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which [LibraryLimit] applies (#107): the `freeTier` flag, then the unlock, bought or the
 * Developer settings override. An interface so repository tests can fix one.
 */
interface LibraryPolicy {
    val limit: LibraryLimit
    val limits: Flow<LibraryLimit>

    /** The app as it was before #107, for tests and callers with no policy in mind. */
    object HistoryOnly : LibraryPolicy {
        override val limit: LibraryLimit = LibraryLimit.History(LibraryLimit.HISTORY_RECIPES)
        override val limits: Flow<LibraryLimit> = flowOf(limit)
    }
}

@Singleton
class DefaultLibraryPolicy @Inject constructor(
    private val flags: FeatureFlags,
    private val entitlements: Entitlements
) : LibraryPolicy {

    override val limit: LibraryLimit
        get() = LibraryLimit.of(
            freeTier = flags.isOn(Flag.FREE_TIER),
            unlocked = entitlements.state.value.unlocked || flags.unlockedOverride
        )

    override val limits: Flow<LibraryLimit> =
        combine(flags.values, flags.unlockedOverrides, entitlements.state) { values, override, state ->
            LibraryLimit.of(values.isOn(Flag.FREE_TIER), state.unlocked || override)
        }.distinctUntilChanged()
}
