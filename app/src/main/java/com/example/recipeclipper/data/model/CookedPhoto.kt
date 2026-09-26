package com.example.recipeclipper.data.model

/**
 * One "I made this" entry (#116): the user's own photo of a recipe they cooked, with the [day]
 * they cooked it (a local epoch day, [PlanDays]) and an optional short [note]. [path] is the
 * stored JPEG's absolute path, for showing and sharing it; the file lives in the app's own
 * storage, downscaled, and belongs to the recipe (deleting the recipe deletes it).
 */
data class CookedPhoto(
    val id: Long,
    val recipeId: Long,
    val fileName: String,
    val path: String,
    val day: Long,
    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val uid: String
) {
    companion object {
        /** The longest note kept: "short", a line or two under a photo. */
        const val MAX_NOTE = 280

        /** A note as stored: trimmed, blank as none, at most [MAX_NOTE] characters. */
        fun cleanNote(note: String?): String? = note?.trim()?.take(MAX_NOTE)?.takeIf { it.isNotEmpty() }
    }
}
