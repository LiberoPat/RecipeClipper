package com.example.recipeclipper.data.model

/**
 * Whether the Today card (#402) may show, given the cook's choices (#448): "Show today's meal" in
 * Settings' Plan section ([showSetting]) and the day its close button was last tapped
 * ([dismissedDay], an epoch day, local; null when never). A close lasts for the rest of that day
 * only, so the card returns the next day; it is per day, never per meal. [closable] is the
 * `todayCardClose` flag: off, neither choice counts and the card behaves as before. The mid-cook
 * Continue cooking card is not this card and never asks. Pure, and identical to iOS's
 * `TodayCardChoice`.
 */
object TodayCardChoice {

    /** Whether the Today card shows on [today] (an epoch day, local). */
    fun shows(closable: Boolean, showSetting: Boolean, dismissedDay: Long?, today: Long): Boolean =
        !closable || (showSetting && dismissedDay != today)
}
