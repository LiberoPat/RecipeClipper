package com.example.recipeclipper.fake

import com.example.recipeclipper.data.local.TourPreferences
import com.example.recipeclipper.data.model.Tip
import com.example.recipeclipper.data.model.WelcomeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** The tour's bookkeeping (#151) in memory; [seenTips] re-emits on every change, as the real one does. */
class FakeTourPreferences(
    override var welcome: WelcomeState = WelcomeState.UNDECIDED,
    override var sampleAdded: Boolean = false,
    seen: Set<Tip> = emptySet()
) : TourPreferences {

    private val seenState = MutableStateFlow(seen)

    override val seenTips: StateFlow<Set<Tip>> = seenState

    override fun setTipSeen(tip: Tip, seen: Boolean) {
        seenState.update { if (seen) it + tip else it - tip }
    }
}
