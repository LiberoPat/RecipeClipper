package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.UrlCleaner
import com.example.recipeclipper.data.model.WebImageUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * One recipe of a Recipe Keeper export (#390), as far as the app reads it. Every text is as
 * Recipe Keeper holds it, trimmed. [id] is its `recipeId`; [sourceUrl] a real web link (cleaned)
 * or null (Recipe Keeper writes "Recipe Keeper" there when the cook typed the recipe in);
 * [servings] is `recipeYield` as written ("Serves 6-8"); the times are the text of `prepTime` and
 * `cookTime` (ISO); [categories] are the recipe's courses, categories and collections, which the
 * cook may have become lists (#387); [photos] are the `src`s of `photo0`, `photo1`, … in that
 * order. An ingredient heading is a line ending in a colon
 * ([com.example.recipeclipper.data.model.IngredientHeading]).
 */
data class RecipeKeeperRecipe(
    val id: String?,
    val name: String,
    val ingredients: List<String>,
    val directions: List<String>,
    val notes: String?,
    val servings: String?,
    val prepTime: String?,
    val cookTime: String?,
    val sourceUrl: String?,
    val categories: List<String>,
    val favorite: Boolean,
    val photos: List<String>
)

/** What `recipes.html` held: its recipes, how many blocks weren't one, and how many blocks there were. */
data class RecipeKeeperPage(val recipes: List<RecipeKeeperRecipe>, val notRecipes: Int, val blocks: Int)

/**
 * Reads Recipe Keeper's `recipes.html` (#390; iOS's twin is `RecipeKeeperParser`). The page is a
 * run of `div.recipe-details`, each read **by `itemprop` only**: the visible labels are translated
 * ("Portate", "Porzioni"), the itemprops are not. The markup is not strict HTML (unclosed
 * `<meta>`, an unquoted `itemprop=photo0/`), so Jsoup reads it as a browser would.
 *
 * - A value is a `<meta>`'s `content`, else the element's text (the first course is a `<span>`,
 *   any further ones `<meta>`s).
 * - Ingredients and directions are the `<p>`s of their `div`, blanks dropped. A bold ingredient
 *   line (the whole line in `<b>`) is a heading, written with a colon if it has none. A direction
 *   loses a leading "1. ".
 * - `recipeSource` is kept only when it is an http(s) link. A `PT0S` time is none by the time
 *   rules, where the import maps it.
 * - A recipe needs a name plus ingredients or steps (the parsers' rule); a block without is
 *   counted as not a recipe. Nutrition, rating and share id are not kept.
 *
 * Pure: text in, data out. A BOM is stripped here.
 */
object RecipeKeeperParser {

    fun parse(text: String): RecipeKeeperPage {
        val doc = try {
            Jsoup.parse(text.removePrefix("﻿"))
        } catch (e: Exception) {
            return RecipeKeeperPage(emptyList(), 0, 0)
        } catch (e: StackOverflowError) {
            return RecipeKeeperPage(emptyList(), 0, 0)
        }
        val blocks = doc.getElementsByClass("recipe-details").filter { it.tagName() == "div" }
        val recipes = ArrayList<RecipeKeeperRecipe>()
        var notRecipes = 0
        for (block in blocks) {
            val recipe = recipe(block)
            if (recipe == null) notRecipes++ else recipes += recipe
        }
        return RecipeKeeperPage(recipes, notRecipes, blocks.size)
    }

    private fun recipe(block: Element): RecipeKeeperRecipe? {
        val props = LinkedHashMap<String, MutableList<Element>>()
        for (el in block.getElementsByAttribute("itemprop")) {
            // `itemprop=photo0/` unquoted reads as "photo0/": the slash is not part of the name.
            val key = el.attr("itemprop").trim().trimEnd('/')
            props.getOrPut(key) { ArrayList() } += el
        }
        fun values(key: String): List<String> = props[key].orEmpty().map(::value).filter { it.isNotEmpty() }
        fun first(key: String): String? = values(key).firstOrNull()

        val name = first("name") ?: return null
        val ingredients = props["recipeIngredients"]?.firstOrNull()?.let(::ingredientLines).orEmpty()
        val directions = props["recipeDirections"]?.firstOrNull()?.let(::directionLines).orEmpty()
        if (ingredients.isEmpty() && directions.isEmpty()) return null

        val photos = props.entries
            .mapNotNull { (key, els) -> photoIndex(key)?.let { index -> index to els } }
            .sortedBy { it.first }
            .flatMap { (_, els) -> els.mapNotNull { it.attr("src").trim().takeIf { src -> src.isNotEmpty() } } }

        return RecipeKeeperRecipe(
            id = first("recipeId"),
            name = name,
            ingredients = ingredients,
            directions = directions,
            notes = props["recipeNotes"]?.firstOrNull()?.let(::paragraphs)?.joinToString("\n")?.takeIf { it.isNotEmpty() },
            servings = first("recipeYield"),
            prepTime = first("prepTime"),
            cookTime = first("cookTime"),
            sourceUrl = first("recipeSource")?.let { WebImageUrl.of(it) }?.let { UrlCleaner.clean(it) },
            categories = values("recipeCourse") + values("recipeCategory") + values("recipeCollection"),
            favorite = first("recipeIsFavourite").equals("True", ignoreCase = true),
            photos = photos
        )
    }

    /** `photo0`, `photo12` (digits only after "photo"): its number, else null. */
    private fun photoIndex(key: String): Int? {
        if (!key.startsWith("photo")) return null
        val digits = key.removePrefix("photo")
        return if (digits.isNotEmpty() && digits.length <= 6 && digits.all(Char::isDigit)) digits.toInt() else null
    }

    /** A `<meta>`'s content, else the element's text, trimmed. */
    private fun value(el: Element): String = (if (el.tagName() == "meta") el.attr("content") else el.text()).trim()

    /** The text of each `<p>` in [el], blanks dropped; with no `<p>`, the element's own text as one line. */
    private fun paragraphs(el: Element): List<String> {
        val ps = el.getElementsByTag("p")
        val lines = if (ps.isEmpty()) listOf(el.text()) else ps.map { it.text() }
        return lines.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun ingredientLines(el: Element): List<String> {
        val ps = el.getElementsByTag("p")
        if (ps.isEmpty()) return paragraphs(el)
        return ps.mapNotNull { p ->
            val text = p.text().trim()
            if (text.isEmpty()) return@mapNotNull null
            val bold = p.children().filter { it.tagName() == "b" || it.tagName() == "strong" }.joinToString("") { it.text() }
            val isHeading = bold.isNotBlank() && bold.filterNot(Char::isWhitespace) == text.filterNot(Char::isWhitespace)
            if (isHeading && !text.endsWith(":")) "$text:" else text
        }
    }

    private val NUMBER = Regex("^\\d{1,3}\\.\\s+")

    private fun directionLines(el: Element): List<String> =
        paragraphs(el).map { it.replace(NUMBER, "").trim() }.filter { it.isNotEmpty() }
}
