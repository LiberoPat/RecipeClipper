package com.example.recipeclipper.data.local

import com.example.recipeclipper.data.model.Tip
import com.example.recipeclipper.data.model.WelcomeState
import kotlinx.coroutines.flow.Flow

/**
 * The first-run tour's bookkeeping (#151), in the same `unit_preferences` file as the settings
 * (so it is backed up with them) under the same keys as iOS's UserDefaults: `tour_welcome`,
 * `tour_sample_added` and one `tour_tip_…` per [Tip]. Its own interface, so the screens that
 * only show settings never see it; [SharedPrefsAppPreferences] implements both.
 */
interface TourPreferences {
    var welcome: WelcomeState

    /** The sample recipe has been added once. It is never added again by itself, even when deleted. */
    var sampleAdded: Boolean

    /** The tips dismissed so far, then every change. */
    val seenTips: Flow<Set<Tip>>

    fun setTipSeen(tip: Tip, seen: Boolean)
}
