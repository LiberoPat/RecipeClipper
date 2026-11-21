package com.example.recipeclipper.ui.common

/**
 * The state of a "Select all" row (#406) over a checklist: nothing ticked, some, or every row.
 * Tapping it when [ALL] unticks every row; in any other state it ticks every row.
 */
enum class SelectAllState {
    NONE,
    SOME,
    ALL;

    /** What a tap does: untick everything when everything is ticked, else tick everything. */
    val ticksAll: Boolean get() = this != ALL

    companion object {
        fun of(ticked: Int, total: Int): SelectAllState = when {
            total > 0 && ticked >= total -> ALL
            ticked <= 0 -> NONE
            else -> SOME
        }

        /** The row shows only when there is more than one thing to tick. */
        const val MIN_ROWS = 2
    }
}
