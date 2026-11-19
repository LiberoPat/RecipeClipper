package com.example.recipeclipper.data.backup

import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.Durations
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.ManualRecipe
import com.example.recipeclipper.data.model.UrlCleaner
import com.example.recipeclipper.data.remote.PaprikaRecipe

/** What a Paprika import did (#382), as the Settings page says it. Counts only; the screen picks the words. */
data class PaprikaSummary(
    /** Recipes written. */
    val imported: Int,
    /** In the file, but already here (the same link, or the same Paprika recipe imported before), or twice in the file. */
    val alreadyHere: Int,
    /** Left out because the free library was full (nothing old is removed to make room). */
    val skippedForLimit: Int,
    /** Entries that read as JSON but had no name, or neither ingredients nor steps. */
    val notRecipes: Int,
    /** Entries that couldn't be unpacked or read. */
    val unreadable: Int,
    /** The file carried pictures of its own, which the app doesn't keep. */
    val photosNotImported: Boolean,
    /** The free library's size when [skippedForLimit] ran into it, else null. */
    val freeLimit: Int?
)

/**
 * Paprika's recipes as a [Backup] (#382), so they merge like any import: through [BackupMerger],
 * the one place that applies the library limit to a file. Pure: no database, no clock of its own.
 *
 * - **Identity** (what makes a second import of the same file add nothing): the recipe's cleaned
 *   source link when it has a real one, so it also meets the same recipe shared or clipped
 *   here; else `manual:paprika-<uid>` from Paprika's own id; else `manual:paprika-h<hash>` of its
 *   name and text, for a file with no ids. A recipe already here is never changed (the merger
 *   keeps what is here, and only fills an empty note).
 * - **Origin:** `EDITED` for a recipe with a link (the cook's version: a re-share of its link
 *   opens it as imported, never fetched over it; "Update from source" is the way back) and
 *   `MANUAL` for one with none (Paprika's typed-in recipes, like the ones typed in here).
 * - **Text stays as written:** names, lines, notes and servings are Paprika's, trimmed. A time
 *   that is a whole phrase ("1 hour 30 minutes") renders like ISO ("1h 30m") through
 *   [Durations]; any other ("Overnight") stays, as the parsers do. Categories are not filed into
 *   lists ("Lists are deliberate").
 * - **Order:** the first recipe in the file is the most recently viewed, one millisecond apart,
 *   so the file's order is kept in Recipes and decides which fit at the free limit.
 */
object PaprikaImport {

    /** A file's recipes ready for [BackupMerger], and how many repeated an earlier one in the same file. */
    data class Mapped(val backup: Backup, val repeated: Int)

    private val SAFE_UID = Regex("^[A-Za-z0-9-]{1,64}$")

    fun identity(recipe: PaprikaRecipe): String {
        recipe.sourceUrl?.let { return UrlCleaner.clean(it) }
        recipe.uid?.takeIf { SAFE_UID.matches(it) }?.let { return ManualRecipe.newSourceUrl("paprika-" + it.lowercase()) }
        return ManualRecipe.newSourceUrl("paprika-h" + contentHash(recipe))
    }

    /** FNV-1a (64 bits) over the name and text: the same on both platforms, and nothing secret. */
    internal fun contentHash(recipe: PaprikaRecipe): String {
        val text = (listOf(recipe.name) + recipe.ingredients + recipe.directions).joinToString("\n")
        var hash = 0xcbf29ce484222325uL
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toULong() and 0xffuL)) * 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }

    fun toBackup(recipes: List<PaprikaRecipe>, now: Long, newUid: () -> String): Mapped {
        val seen = HashSet<String>()
        val mapped = ArrayList<BackupRecipe>()
        var repeated = 0
        for (recipe in recipes) {
            val key = identity(recipe)
            if (!seen.add(key)) {
                repeated++
                continue
            }
            val language = LanguageWords.resolve(null, null) {
                LanguageWords.detectionText(recipe.name, recipe.ingredients)
            }
            val words = LanguageWords.forTag(language)
            fun time(text: String?) = text?.let { Durations.format(it, words) }
            val hasLink = recipe.sourceUrl != null
            mapped += BackupRecipe(
                id = newUid(),
                sourceUrl = key,
                sourceType = "BLOG",
                title = recipe.name,
                imageUrl = recipe.imageUrl,
                ingredients = recipe.ingredients,
                instructions = recipe.directions,
                prepTime = time(recipe.prepTime),
                cookTime = time(recipe.cookTime),
                totalTime = time(recipe.totalTime),
                servings = recipe.servings,
                lastViewedAt = now - mapped.size,
                checkedIngredients = emptySet(),
                notes = recipe.notes,
                language = language,
                contentOrigin = (if (hasLink) ContentOrigin.EDITED else ContentOrigin.MANUAL).name,
                editedAt = now
            )
        }
        return Mapped(
            Backup(exportedAt = now, recipes = mapped, lists = emptyList(), memberships = emptyList()),
            repeated
        )
    }
}
