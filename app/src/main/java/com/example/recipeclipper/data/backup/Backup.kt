package com.example.recipeclipper.data.backup

/**
 * One export file: every recipe, every list and every membership, as plain data. The JSON
 * shape is in [BackupJson]; the rules for bringing one into a phone that already has recipes
 * are in [BackupMerger]. Pure types, no Android: the iOS app has the same ones
 * (`Data/Backup/Backup.swift`) and both platforms test against the same fixture files in
 * `shared/fixtures/backup/`.
 *
 * Every record carries a stable id ([BackupRecipe.id], [BackupList.id], [BackupPantryItem.id],
 * [BackupGroceryItem.id]): the row's `uid`, which
 * never changes on the phone that made it (a rename or a re-share keeps it). Memberships refer
 * to those ids, never to database row ids.
 *
 * Deliberately not in the file: cached photos (only [BackupRecipe.imageUrl]) and cook progress
 * (timers, the current step, the chosen servings), which live in memory today and, if #10
 * persists them, are still a moment in one kitchen rather than part of the recipe.
 */
data class Backup(
    val exportedAt: Long,
    val recipes: List<BackupRecipe>,
    val lists: List<BackupList>,
    val memberships: List<BackupMembership>,
    /** The pantry (#51). Absent in older files, which read as empty. */
    val pantry: List<BackupPantryItem> = emptyList(),
    /** The grocery list (#50), in list order. Absent in older files, which read as empty. */
    val groceries: List<BackupGroceryItem> = emptyList()
) {
    companion object {
        /** The `format` marker: tells an export apart from any other JSON file. */
        const val FORMAT = "recipe-clipper-backup"

        /**
         * Bumped only when an older app would misread a newer file. Adding a field or a whole
         * new top-level section (a meal plan, say) doesn't need a bump: readers ignore keys
         * they don't know, and import only ever adds.
         */
        const val FORMAT_VERSION = 1
    }
}

data class BackupRecipe(
    val id: String,
    val sourceUrl: String,
    /** "BLOG" or "REDDIT"; anything else is read as BLOG when imported. */
    val sourceType: String,
    val title: String,
    val imageUrl: String?,
    val ingredients: List<String>,
    val instructions: List<String>,
    val prepTime: String?,
    val cookTime: String?,
    val totalTime: String?,
    val servings: String?,
    val lastViewedAt: Long,
    val checkedIngredients: Set<Int>,
    val notes: String?,
    /** The recipe's language tag (#14), so an import reads it with the same words. */
    val language: String? = null,
    /** Whose words the content is (#29): "PARSED" (absent in older files), "EDITED",
     *  "CLIPPED" or "MANUAL". Kept so an imported edit is still never refreshed by a re-share. */
    val contentOrigin: String = "PARSED",
    /** When the user last saved an edit, or null. */
    val editedAt: Long? = null
)

data class BackupList(
    val id: String,
    val name: String,
    val isFavorites: Boolean,
    val isBuiltIn: Boolean,
    val sortOrder: Int,
    val createdAt: Long
)

data class BackupMembership(
    val recipeId: String,
    val listId: String,
    val addedAt: Long
)

/** A pantry item (#51). [purchasedDay] and [expiresDay] are epoch days; [aisle] an `Aisle` key. */
data class BackupPantryItem(
    val id: String,
    val name: String,
    val quantity: String?,
    val language: String?,
    val aisle: String,
    val inStock: Boolean,
    val alwaysHave: Boolean,
    val purchasedDay: Long?,
    val expiresDay: Long?,
    val updatedAt: Long
)

/**
 * A grocery item (#50). [recipeId] is the file id of the recipe it came from, or null (typed,
 * or its recipe is gone); [plannedDay] the planned day (an epoch day) it came from, if any.
 */
data class BackupGroceryItem(
    val id: String,
    val text: String,
    val language: String?,
    val aisle: String,
    val checked: Boolean,
    val recipeId: String?,
    val plannedDay: Long?,
    val updatedAt: Long
)

/**
 * Why an export or an import failed, as a cause: the Settings screen picks the words. An
 * import that fails for any of these has written nothing.
 */
sealed class BackupError {
    /** Not JSON, not an object, no `format` marker, or far too big to be an export. */
    object NotABackup : BackupError()

    /** Made by a newer app whose format this one can't read. */
    data class NewerVersion(val found: Int) : BackupError()

    /** An export, but damaged: [detail] names the first bad field (diagnostic, not copy). */
    data class Malformed(val detail: String) : BackupError()

    /** The picked file couldn't be opened or read. */
    object ReadFailed : BackupError()

    /** The database refused the import. It ran in one transaction, so nothing was written. */
    object SaveFailed : BackupError()

    /** The recipes couldn't be read out, or the file couldn't be written. */
    object ExportFailed : BackupError()
}

sealed class BackupResult<out T> {
    data class Success<T>(val value: T) : BackupResult<T>()
    data class Failure(val error: BackupError) : BackupResult<Nothing>()
}

/** What an import did, for the one-line summary. */
data class ImportSummary(
    /** New recipes written. */
    val recipesAdded: Int,
    /** New lists created (lists that combined with one already here don't count). */
    val listsAdded: Int,
    /** Recipes in the file that were already on this phone (same cleaned link). */
    val recipesAlreadyHere: Int,
    /** Recipes in no list left out because history was full (see [BackupMerger]). */
    val recipesSkipped: Int,
    /** New pantry items written (#51). */
    val pantryAdded: Int = 0,
    /** New grocery items written (#50). */
    val groceriesAdded: Int = 0
)

/** An export ready to hand to the share sheet. */
data class ExportedBackup(val json: String, val exportedAt: Long, val recipeCount: Int)
