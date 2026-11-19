package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.UrlCleaner
import com.example.recipeclipper.data.model.WebImageUrl
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * One recipe of a Paprika export (#382), as far as the app reads it. Every text is as Paprika
 * holds it, trimmed: nothing here is converted or guessed. [uid] is Paprika's own id (the
 * identity of the recipe in an export, which a re-import uses), [sourceUrl] a real web link
 * (cleaned) or null, [imageUrl] a web picture link or null. [hasPhoto] is whether the file
 * carries the recipe's picture itself (`photo_data`, or the first of `photos`). [categories] are
 * Paprika's own filing; the cook may have them become lists (#387). [favorite] is Paprika's own
 * flag (`on_favorites`).
 *
 * [photoData] is that picture as the base64 text the file holds it, only until the archive reader
 * has handed it to the photo store (#386): it is then null, and [photoFile] names the stored file
 * (null if the picture was left out: too big, or not an image).
 */
data class PaprikaRecipe(
    val uid: String?,
    val name: String,
    val ingredients: List<String>,
    val directions: List<String>,
    val notes: String?,
    val servings: String?,
    val prepTime: String?,
    val cookTime: String?,
    val totalTime: String?,
    val sourceUrl: String?,
    val imageUrl: String?,
    val categories: List<String>,
    val hasPhoto: Boolean,
    val photoData: String? = null,
    val photoFile: String? = null,
    val favorite: Boolean = false
)

/** What one entry of the export said. */
sealed interface PaprikaEntry {
    data class Recipe(val recipe: PaprikaRecipe) : PaprikaEntry

    /** A JSON object with no name, or with neither ingredients nor steps: not a recipe, skipped and counted. */
    data object NotARecipe : PaprikaEntry

    /** Not JSON, not an object, or too deeply nested to read: counted as unreadable. */
    data object Unreadable : PaprikaEntry
}

/**
 * Reads the JSON of one `.paprikarecipe` entry (the gunzipped text). The fields are the ones the
 * libpaprika crate documents (`name`, `ingredients` and `directions` as newline-separated text,
 * `notes`, `servings`, `prep_time`, `cook_time`, `total_time`, `source_url`, `image_url`,
 * `categories`, `photo_data`, `uid`); a missing, null or unknown field is simply absent.
 * A recipe needs a name plus ingredients or steps, the parsers' rule. Pure: text in, data out.
 */
object PaprikaRecipeParser {

    fun parse(text: String): PaprikaEntry {
        val root = try {
            JSONTokener(text.removePrefix("﻿")).nextValue()
        } catch (e: Exception) {
            return PaprikaEntry.Unreadable
        } catch (e: StackOverflowError) {
            // A hostile entry nested thousands deep: org.json recurses.
            return PaprikaEntry.Unreadable
        }
        val json = root as? JSONObject ?: return PaprikaEntry.Unreadable

        val name = json.string("name").orEmpty()
        val ingredients = RecipeDraft.lines(json.string("ingredients").orEmpty())
        val directions = RecipeDraft.lines(json.string("directions").orEmpty())
        if (name.isEmpty() || (ingredients.isEmpty() && directions.isEmpty())) return PaprikaEntry.NotARecipe

        val photos = json.optJSONArray("photos")
        // The picture: photo_data, else the first of the photos' data (libpaprika's RecipePhoto).
        val photoData = json.string("photo_data") ?: (0 until (photos?.length() ?: 0)).firstNotNullOfOrNull { i ->
            (photos?.opt(i) as? JSONObject)?.string("data")
        }
        return PaprikaEntry.Recipe(
            PaprikaRecipe(
                uid = json.string("uid"),
                name = name,
                ingredients = ingredients,
                directions = directions,
                notes = json.string("notes"),
                servings = json.string("servings"),
                prepTime = json.string("prep_time"),
                cookTime = json.string("cook_time"),
                totalTime = json.string("total_time"),
                sourceUrl = json.string("source_url")?.let(::webLink),
                imageUrl = WebImageUrl.of(json.string("image_url")),
                categories = json.optJSONArray("categories")?.strings().orEmpty(),
                hasPhoto = photoData != null || (photos != null && photos.length() > 0),
                photoData = photoData,
                favorite = json.flag("on_favorites")
            )
        )
    }

    /** A real web link, cleaned as every saved link is; anything else ("", "n/a", a name) is none. */
    private fun webLink(text: String): String? = WebImageUrl.of(text)?.let { UrlCleaner.clean(it) }

    /**
     * A text field, trimmed; null if absent, JSON null, blank or not text. `optString` would turn
     * a JSON null into "null" on Android, so the value is read as it is.
     */
    private fun JSONObject.string(key: String): String? =
        when (val value = opt(key)) {
            is String -> value
            is Number -> value.toString()
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }

    /** A switch as Paprika writes it: a boolean, or a number that isn't 0 (`on_favorites` is 0 or 1). */
    private fun JSONObject.flag(key: String): Boolean =
        when (val value = opt(key)) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            else -> false
        }

    private fun JSONArray.strings(): List<String> =
        (0 until length()).mapNotNull { (opt(it) as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
}
