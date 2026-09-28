package com.example.recipeclipper.data.local

import com.example.recipeclipper.data.model.Tooltip
import kotlinx.coroutines.flow.Flow

/**
 * The first-run tour's bookkeeping (#151, #190), in the same `unit_preferences` file as the
 * settings (so it is backed up with them) under the same keys as iOS's UserDefaults:
 * `tour_sample_added` and one `tooltip_…` per [Tooltip]. #151's `tour_welcome` and `tour_tip_…`
 * keys are ignored: never read or written again. Its own interface, so the screens that only
 * show settings never see it; [SharedPrefsAppPreferences] implements both.
 */
interface TourPreferences {
    /** The sample recipe has been added once, or wasn't needed. Never added again by itself, even when deleted. */
    var sampleAdded: Boolean

    /** The tooltips seen so far, then every change. */
    val seenTooltips: Flow<Set<Tooltip>>

    fun setTooltipSeen(tooltip: Tooltip, seen: Boolean)
}
