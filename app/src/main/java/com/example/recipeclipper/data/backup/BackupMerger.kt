package com.example.recipeclipper.data.backup

import com.example.recipeclipper.data.model.UrlCleaner
import java.util.Locale

/** A recipe already on this phone, as far as merging needs to know it. */
data class ExistingRecipe(
    val id: Long,
    val uid: String,
    val sourceUrl: String,
    val hasNotes: Boolean,
    /** In at least one list. */
    val isListed: Boolean
)

/** A list already on this phone. Pass them in display order: a name match takes the first. */
data class ExistingList(
    val id: Long,
    val uid: String,
    val name: String,
    val isFavorites: Boolean
)

/** What a planned membership or note points at: a row already here, or one the import adds. */
sealed class Target {
    data class Existing(val id: Long) : Target()
    /** Keyed by the uid the new row will be written with. */
    data class New(val uid: String) : Target()
}

data class NewList(
    val uid: String,
    val name: String,
    val isBuiltIn: Boolean,
    val sortOrder: Int,
    val createdAt: Long
)

data class NoteUpdate(val recipeId: Long, val notes: String)

data class PlannedMembership(val recipe: Target, val list: Target, val addedAt: Long)

/**
 * Everything an import will write, worked out before anything is written. [newRecipes] are
 * ready to insert: link cleaned, uid final, note blank-to-null, ticks within range, sourceType
 * one the app knows.
 */
data class ImportPlan(
    val newRecipes: List<BackupRecipe>,
    val noteUpdates: List<NoteUpdate>,
    val newLists: List<NewList>,
    val memberships: List<PlannedMembership>,
    val summary: ImportSummary
)

/**
 * How an export file merges into a phone that already has recipes (issue #26; the owner's
 * decision: merge, never replace, never delete). Pure, so both platforms test it against the
 * same fixture and must agree.
 *
 * - **Recipes** match by the cleaned link ([UrlCleaner]), against this phone and within the
 *   file (the first copy in the file wins; later copies add their memberships and, if the first
 *   has none, their note). A recipe already here keeps its content, ticks and last view, gains
 *   the imported memberships, and gains the imported note only if it has none. A new recipe keeps
 *   its uid unless that uid is already taken here, in which case it gets a fresh one.
 * - **Favorites** is matched by `isFavorites`, never by name: it maps to this phone's Favorites
 *   whatever either is called, and a user list that happens to be named "Favorites" never joins it.
 * - **Other lists** join a list here with the same uid (the same list, perhaps renamed), else one
 *   with the same name (trimmed, case-insensitive), else another list in the file with that name,
 *   else they are created after the lists already here. The Favorites list here is never a name
 *   or uid match for them.
 * - **Memberships** are planned once per recipe and list; writing them is insert-or-ignore, so
 *   one that already exists keeps its `addedAt`.
 * - **History.** Importing never deletes anything, and never culls. Recipes in a list always
 *   come in. Imported recipes in no list join history at their original last view, but only
 *   into free places under [historyLimit] (counting what's here and not in a list once the
 *   import's memberships are in): the most recently viewed fill them, and the rest are skipped
 *   and counted. So an import can't push a recipe already here out of history.
 */
object BackupMerger {

    fun plan(
        backup: Backup,
        existingRecipes: List<ExistingRecipe>,
        existingLists: List<ExistingList>,
        maxSortOrder: Int,
        historyLimit: Int,
        newUid: () -> String
    ): ImportPlan {
        // --- Recipes: fold the file onto distinct cleaned links, then onto what's here.
        val existingByUrl = HashMap<String, ExistingRecipe>()
        existingRecipes.forEach { existingByUrl.putIfAbsent(UrlCleaner.clean(it.sourceUrl), it) }
        val takenRecipeUids = existingRecipes.mapTo(HashSet()) { it.uid }

        val recipeTargets = HashMap<String, Target>()   // file recipe id -> target
        val newByUrl = LinkedHashMap<String, BackupRecipe>()
        val matched = LinkedHashMap<Long, ExistingRecipe>()
        val importedNote = HashMap<Long, String>()      // existing id -> first non-blank note

        for (recipe in backup.recipes) {
            val url = UrlCleaner.clean(recipe.sourceUrl)
            val note = recipe.notes?.takeIf { it.isNotBlank() }
            val existing = existingByUrl[url]
            if (existing != null) {
                matched[existing.id] = existing
                if (note != null) importedNote.putIfAbsent(existing.id, note)
                recipeTargets[recipe.id] = Target.Existing(existing.id)
                continue
            }
            val first = newByUrl[url]
            if (first != null) {
                if (first.notes == null && note != null) newByUrl[url] = first.copy(notes = note)
                recipeTargets[recipe.id] = Target.New(first.id)
                continue
            }
            val uid = if (takenRecipeUids.add(recipe.id)) recipe.id else freshUid(takenRecipeUids, newUid)
            newByUrl[url] = recipe.copy(
                id = uid,
                sourceUrl = url,
                sourceType = recipe.sourceType.takeIf { it in KNOWN_SOURCE_TYPES } ?: "BLOG",
                checkedIngredients = recipe.checkedIngredients.filterTo(HashSet()) { it in recipe.ingredients.indices },
                notes = note
            )
            recipeTargets[recipe.id] = Target.New(uid)
        }

        // --- Lists.
        val favorites = existingLists.firstOrNull { it.isFavorites }
        val others = existingLists.filterNot { it.isFavorites }
        val takenListUids = existingLists.mapTo(HashSet()) { it.uid }
        val listTargets = HashMap<String, Target>()      // file list id -> target
        val newListsByName = LinkedHashMap<String, NewList>()
        var nextSortOrder = maxSortOrder + 1

        for (list in backup.lists) {
            val key = nameKey(list.name)
            val target: Target = when {
                list.isFavorites && favorites != null -> Target.Existing(favorites.id)
                else -> others.firstOrNull { it.uid == list.id }?.let { Target.Existing(it.id) }
                    ?: others.firstOrNull { nameKey(it.name) == key }?.let { Target.Existing(it.id) }
                    ?: newListsByName[key]?.let { Target.New(it.uid) }
                    ?: run {
                        val uid = if (takenListUids.add(list.id)) list.id else freshUid(takenListUids, newUid)
                        newListsByName[key] = NewList(
                            uid = uid,
                            name = list.name.trim(),
                            isBuiltIn = list.isBuiltIn,
                            sortOrder = nextSortOrder++,
                            createdAt = list.createdAt
                        )
                        Target.New(uid)
                    }
            }
            listTargets[list.id] = target
        }

        // --- Memberships, once per recipe and list; the first in the file wins.
        val seen = HashSet<Pair<Target, Target>>()
        val memberships = backup.memberships.mapNotNull { m ->
            val recipe = recipeTargets.getValue(m.recipeId)
            val list = listTargets.getValue(m.listId)
            if (seen.add(recipe to list)) PlannedMembership(recipe, list, m.addedAt) else null
        }

        // --- History: new recipes in no list only take free places; nothing here is pushed out.
        val listedTargets = memberships.mapTo(HashSet()) { it.recipe }
        val unlistedHere = existingRecipes.count { !it.isListed && Target.Existing(it.id) !in listedTargets }
        val freePlaces = (historyLimit - unlistedHere).coerceAtLeast(0)
        val unlistedNew = newByUrl.values.filter { Target.New(it.id) !in listedTargets }
        val kept = unlistedNew
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<BackupRecipe>> { it.value.lastViewedAt }.thenBy { it.index })
            .take(freePlaces)
            .mapTo(HashSet()) { it.value.id }
        val skipped = unlistedNew.count { it.id !in kept }
        val newRecipes = newByUrl.values.filter { Target.New(it.id) in listedTargets || it.id in kept }

        val noteUpdates = matched.values
            .filter { !it.hasNotes && importedNote[it.id] != null }
            .map { NoteUpdate(it.id, importedNote.getValue(it.id)) }

        return ImportPlan(
            newRecipes = newRecipes,
            noteUpdates = noteUpdates,
            newLists = newListsByName.values.toList(),
            memberships = memberships,
            summary = ImportSummary(
                recipesAdded = newRecipes.size,
                listsAdded = newListsByName.size,
                recipesAlreadyHere = matched.size,
                recipesSkipped = skipped
            )
        )
    }

    /** How list names are compared: trimmed and case-insensitive ("Desserts" = " desserts"). */
    fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)

    private fun freshUid(taken: MutableSet<String>, newUid: () -> String): String {
        while (true) {
            val uid = newUid()
            if (taken.add(uid)) return uid
        }
    }

    private val KNOWN_SOURCE_TYPES = setOf("BLOG", "REDDIT")
}
