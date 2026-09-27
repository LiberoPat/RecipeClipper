package com.example.recipeclipper.data.backup

/** Where a shared file's pantry items go (#149): the receiver's choice. */
enum class PantryDestination { PANTRY, GROCERIES }

/**
 * What the receiver ticked in a shared file: the file ids of each section, and where its pantry
 * items go.
 */
data class ShareChoice(
    val recipeIds: Set<String>,
    val groceryIds: Set<String>,
    val pantryIds: Set<String> = emptySet(),
    val pantryTo: PantryDestination = PantryDestination.PANTRY
)

/**
 * A small file that carries picked recipes and grocery or pantry items to someone else's Recipe
 * Clipper (#149, phase 2). It is the export format (#26, [BackupJson]) with `"kind": "share"`
 * and only what was picked: no lists, plan, menus or photos. Pure, like [BackupMerger]; the iOS
 * app has the same rules (`Data/Backup/ShareFile.swift`) and both read
 * `shared/fixtures/backup/share-v1.recipeclipper`.
 *
 * - **Sent:** each recipe complete, as saved (not scaled or converted), without what is the
 *   sender's own: no ticks and no note (the text share leaves the note out too). Grocery items
 *   as stored, unticked, each naming its recipe only if that recipe is in the file.
 * - **Received:** what the receiver ticked, merged by [BackupMerger] like an import. A recipe
 *   arrives as the newest viewed, like a shared link, with no ticks; a grocery item keeps its
 *   recipe only if that recipe was ticked too; pantry items go to the Pantry or, as their names,
 *   onto the grocery list.
 */
object ShareFile {
    /** The file's extension, and the type Android and iOS open it by. */
    const val EXTENSION = "recipeclipper"
    const val MIME_TYPE = "application/vnd.recipeclipper+json"

    /** The file to send: [recipes] complete, [groceries] unticked, [pantry] as it is. */
    fun make(
        now: Long,
        recipes: List<BackupRecipe>,
        groceries: List<BackupGroceryItem> = emptyList(),
        pantry: List<BackupPantryItem> = emptyList()
    ): Backup {
        val sent = recipes.distinctBy { it.id }.map {
            it.copy(lastViewedAt = now, checkedIngredients = emptySet(), notes = null)
        }
        val ids = sent.mapTo(HashSet()) { it.id }
        return Backup(
            exportedAt = now,
            recipes = sent,
            lists = emptyList(),
            memberships = emptyList(),
            pantry = pantry.distinctBy { it.id },
            groceries = groceries.distinctBy { it.id }.map {
                it.copy(checked = false, plannedDay = null, recipeId = it.recipeId?.takeIf { id -> id in ids })
            },
            isShare = true
        )
    }

    /**
     * The Pantry's file (#149): its in-stock items, as they are, and nothing else; null when
     * none is in stock. Items that are out are left out, as in the Pantry's "Send list": running
     * out put them on the grocery list (#146), which Groceries sends.
     */
    fun pantry(now: Long, items: List<BackupPantryItem>): Backup? {
        val inStock = items.filter { it.inStock }
        return if (inStock.isEmpty()) null else make(now, emptyList(), pantry = inStock)
    }

    /**
     * What to merge once the receiver has chosen: only the ticked parts of [file], each new to
     * this phone as of [now]. A pantry item sent onto the grocery list keeps its uid, so the same
     * file opened twice adds it once, as every other item.
     */
    fun chosen(file: Backup, choice: ShareChoice, now: Long): Backup {
        val recipes = file.recipes.filter { it.id in choice.recipeIds }
            .map { it.copy(lastViewedAt = now, checkedIngredients = emptySet()) }
        val kept = recipes.mapTo(HashSet()) { it.id }
        val groceries = file.groceries.filter { it.id in choice.groceryIds }.map {
            it.copy(checked = false, plannedDay = null, recipeId = it.recipeId?.takeIf { id -> id in kept }, updatedAt = now)
        }
        val pantry = file.pantry.filter { it.id in choice.pantryIds }
        val toPantry = choice.pantryTo == PantryDestination.PANTRY
        return Backup(
            exportedAt = now,
            recipes = recipes,
            lists = emptyList(),
            memberships = emptyList(),
            pantry = if (toPantry) pantry.map { it.copy(updatedAt = now) } else emptyList(),
            groceries = if (toPantry) groceries else groceries + pantry.map {
                BackupGroceryItem(it.id, it.name, it.language, it.aisle, checked = false, recipeId = null, plannedDay = null, updatedAt = now)
            },
            isShare = true
        )
    }

    /** "Sheet-pan chicken.recipeclipper": the title, safe as a file name on any phone. */
    fun fileName(title: String): String {
        val safe = title.map { c -> if (c in UNSAFE || c.isISOControl()) ' ' else c }.joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim().trimStart('.').take(MAX_NAME).trim()
        return safe.ifEmpty { "Recipe Clipper" } + "." + EXTENSION
    }

    private const val UNSAFE = "/\\:*?\"<>|"
    private const val MAX_NAME = 60
}
