package com.example.recipeclipper.data.local

/**
 * When each recipe's "Update the pantry" sheet (#147) was last confirmed or dismissed, so one
 * cooking is never offered twice: finishing cook mode and then adding a photo, or two photos of
 * one dinner. In the settings file (`unit_preferences`, so it is backed up with them) under the
 * key `pantry_use_up`, as iOS's UserDefaults. Its own interface, like [TourPreferences];
 * [SharedPrefsAppPreferences] implements it.
 *
 * Nothing is cleaned up when a recipe is deleted, because nothing needs to be: the ViewModel
 * keeps only the entries still inside the window each time it writes, and recipe ids are never
 * reused (AUTOINCREMENT), so a deleted recipe's entry can't hold back another recipe.
 */
interface UseUpLog {
    /** Recipe id to wall-clock milliseconds. */
    var useUps: Map<Long, Long>

    companion object {
        /** Stored as `id:millis` pairs, comma-separated; anything unreadable is skipped. */
        fun decode(text: String?): Map<Long, Long> =
            text.orEmpty().split(',').mapNotNull { pair ->
                val (id, at) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                (id.trim().toLongOrNull() ?: return@mapNotNull null) to (at.trim().toLongOrNull() ?: return@mapNotNull null)
            }.toMap()

        fun encode(useUps: Map<Long, Long>): String = useUps.entries.joinToString(",") { "${it.key}:${it.value}" }
    }
}
