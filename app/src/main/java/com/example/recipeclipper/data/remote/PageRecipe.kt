package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.PageRecipeCheck
import com.example.recipeclipper.data.model.PageSelection
import com.example.recipeclipper.data.model.PageText
import com.example.recipeclipper.data.model.Recipe

/**
 * The pure ends of reading a recipe out of a page's text (#103); the model call sits between
 * them, in the repository. The iOS app's `PageRecipe` is the same.
 */
internal object PageRecipe {

    /** The lines language detection reads before the model is asked. */
    private const val DETECTION_LINES = 300

    /** The page's language, primary subtag only ("en"): `<html lang>`, unless its words clearly say another. */
    fun language(page: PageText): String =
        LanguageWords.resolve(null, page.language) { page.lines.take(DETECTION_LINES).joinToString("\n") }
            .substringBefore('-')

    /**
     * The recipe, from what the model [picked] out of [window] (the text it was given), keeping
     * only what [PageRecipeCheck] finds there; null if that leaves no recipe. Marked
     * [ContentOrigin.EXTRACTED], with the page's photo and times read as the parsers read them.
     */
    fun recipe(window: String, picked: PageSelection, page: PageText, url: String): Recipe? {
        val kept = PageRecipeCheck.verify(window, picked) ?: return null
        val name = kept.name ?: return null
        val language = LanguageWords.resolve(null, page.language) {
            LanguageWords.detectionText(name, kept.ingredients)
        }
        val words = LanguageWords.forTag(language)
        fun time(text: String?) = text?.let { JsonLdRecipeParser.formatDuration(it, words) }
        return Recipe(
            name = name,
            image = page.image,
            ingredients = kept.ingredients,
            instructions = kept.steps,
            prepTime = time(kept.prepTime),
            cookTime = time(kept.cookTime),
            totalTime = time(kept.totalTime),
            yield = kept.yield,
            sourceUrl = url,
            language = language,
            origin = ContentOrigin.EXTRACTED
        )
    }
}
