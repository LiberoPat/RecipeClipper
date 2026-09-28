package com.example.recipeclipper.fake

import com.example.recipeclipper.data.local.TourPreferences
import com.example.recipeclipper.data.model.Tooltip
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The tour's bookkeeping (#151, #190) in memory; [seenTooltips] re-emits on every change, as the real one does. */
class FakeTourPreferences(
    override var sampleAdded: Boolean = false,
    seen: Set<Tooltip> = emptySet()
) : TourPreferences {

    private val seenState = MutableStateFlow(seen)

    override val seenTooltips: StateFlow<Set<Tooltip>> = seenState

    override fun setTooltipSeen(tooltip: Tooltip, seen: Boolean) {
        seenState.update { if (seen) it + tooltip else it - tooltip }
    }
}
