package com.example.recipeclipper.data.backup

import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.Durations
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.ManualRecipe
import com.example.recipeclipper.data.model.UrlCleaner
import com.example.recipeclipper.data.remote.MealieRecipe

/** What a Mealie import did (#391), as the Settings page says it. Counts only; the screen picks the words. */
data class MealieSummary(
    /** Recipes written. */
    val imported: Int,
    /** In the file, but already here (the same link, or the same Mealie recipe imported before), or twice in the file. */
    val alreadyHere: Int,
    /** Left out because the free library was full (nothing old is removed to make room). */
    val skippedForLimit: Int,
    /** JSON files that read, but weren't a Mealie recipe, or had no name, or neither ingredients nor steps. */
    val notRecipes: Int,
    /** Files that couldn't be unpacked or read. */
    val unreadable: Int,
    /** Pictures that came with a recipe but were left out (#386): too big, or not an image. The recipe came in. */
    val photosLeftOut: Int,
    /** The free library's size when [skippedForLimit] ran into it, else null. */
    val freeLimit: Int?,
    /** New lists made from the categories and tags (#387); a list that was already here doesn't count. */
    val listsMade: Int = 0
)

/**
 * Mealie's recipes as a [Backup] (#391), so they merge like any import: through [BackupMerger], the
 * one place that applies the library limit to a file. The same rules as [PaprikaImport], with
 * Mealie's own names. Pure: no database, no clock of its own.
 *
 * - **Identity** (what makes a second import of the same file add nothing): the recipe's cleaned
 *   `orgURL` when it has a real one, so it also meets the same recipe shared or clipped here; else
 *   `manual:mealie-<slug>` (a plain slug), else `manual:mealie-<id>` (a plain id), else
 *   `manual:mealie-h<hash>` of its name and text. A recipe already here is never changed (the merger
 *   keeps what is here, and only fills an empty note and a missing picture).
 * - **Origin:** `EDITED` for a recipe with a link (the cook's version: a re-share of its link opens it
 *   as imported, never fetched over it) and `MANUAL` for one with none.
 * - **Times** go through [Durations] with the words of the recipe's language (detected from its own
 *   name and ingredients): "1 hour 30 minutes" renders "1h 30m", a zero total is absent, "Overnight"
 *   stays as written.
 * - **Lists** (#387), only when `filing` is on: each category and tag puts the recipe in the list of
 *   that name (found here by the trimmed, case-insensitive name, else made). Mealie's favourites belong
 *   to a user and are not in a recipe's file, so nothing goes to Favorites.
 * - **Order:** the first recipe in the file is the most recently viewed, one millisecond apart.
 */
object MealieImport {

    /** A file's recipes ready for [BackupMerger], and how many repeated an earlier one in the same file. */
    data class Mapped(val backup: Backup, val repeated: Int)

    private val SAFE_NAME = Regex("^[A-Za-z0-9_-]{1,100}$")

    fun identity(recipe: MealieRecipe): String {
        recipe.sourceUrl?.let { return UrlCleaner.clean(it) }
        recipe.slug.takeIf { SAFE_NAME.matches(it) }?.let { return ManualRecipe.newSourceUrl("mealie-" + it.lowercase()) }
        recipe.id?.takeIf { SAFE_NAME.matches(it) }?.let { return ManualRecipe.newSourceUrl("mealie-" + it.lowercase()) }
        return ManualRecipe.newSourceUrl("mealie-h" + contentHash(recipe))
    }

    /** FNV-1a (64 bits) over the name and text: the same on both platforms, and nothing secret. */
    internal fun contentHash(recipe: MealieRecipe): String {
        val text = (listOf(recipe.name) + recipe.ingredients + recipe.instructions).joinToString("\n")
        var hash = 0xcbf29ce484222325uL
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toULong() and 0xffuL)) * 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    private class Entry(val backup: BackupRecipe) {
        val categories = LinkedHashMap<String, String>() // name key to the first spelling

        fun add(names: List<String>) {
            names.filter { it.isNotBlank() }.forEach { categories.putIfAbsent(BackupMerger.nameKey(it), it) }
        }
    }

    fun toBackup(recipes: List<MealieRecipe>, now: Long, filing: Boolean = false, newUid: () -> String): Mapped {
        val entries = LinkedHashMap<String, Entry>()
        var repeated = 0
        for (recipe in recipes) {
            val key = identity(recipe)
            val seen = entries[key]
            if (seen != null) {
                repeated++
                seen.add(recipe.categories)
                continue
            }
            val language = LanguageWords.resolve(null, null) {
                LanguageWords.detectionText(recipe.name, recipe.ingredients)
            }
            val words = LanguageWords.forTag(language)
            fun time(text: String?) = text?.let { Durations.format(it, words) }
            val backup = BackupRecipe(
                id = newUid(),
                sourceUrl = key,
                sourceType = "BLOG",
                title = recipe.name,
                imageUrl = null,
                ingredients = recipe.ingredients,
                instructions = recipe.instructions,
                prepTime = time(recipe.prepTime),
                cookTime = time(recipe.cookTime),
                totalTime = time(recipe.totalTime),
                servings = recipe.servings,
                lastViewedAt = now - entries.size,
                checkedIngredients = emptySet(),
                notes = recipe.notes,
                language = language,
                contentOrigin = (if (recipe.sourceUrl != null) ContentOrigin.EDITED else ContentOrigin.MANUAL).name,
                editedAt = now,
                photo = recipe.photoFile
            )
            entries[key] = Entry(backup).also { it.add(recipe.categories) }
        }

        val lists = ArrayList<BackupList>()
        val memberships = ArrayList<BackupMembership>()
        if (filing) {
            val byName = LinkedHashMap<String, BackupList>()
            for (entry in entries.values) {
                for ((nameKey, name) in entry.categories) {
                    val list = byName.getOrPut(nameKey) {
                        BackupList(
                            id = newUid(), name = name.trim(), isFavorites = false, isBuiltIn = false, sortOrder = 0, createdAt = now
                        ).also { lists += it }
                    }
                    memberships += BackupMembership(entry.backup.id, list.id, now)
                }
            }
        }
        return Mapped(
            Backup(exportedAt = now, recipes = entries.values.map { it.backup }, lists = lists, memberships = memberships),
            repeated
        )
    }
}
