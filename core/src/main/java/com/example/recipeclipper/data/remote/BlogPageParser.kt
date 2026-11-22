package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.StepPhotos
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * A recipe site's page, as HTML, to its recipe: the pure half of `BlogRecipeSource` (which
 * fetches, in `:app`), split from it when the parsers moved to `:core` (#238). The page's
 * JSON-LD blocks go to [JsonLdRecipeParser], refined by the page's recipe cards and site rules,
 * or, when they hold no recipe, the page goes to [MicrodataRecipeParser]. Pure and CPU-bound, so
 * callers run it off the main thread. `BlogRecipeSource.parse` hands over to it; iOS keeps the
 * same step in `BlogRecipeSource.parse(html:url:)`.
 */
object BlogPageParser {

    /** The recipe in [html], the page at [url], or why there is none. */
    fun parse(html: String, url: String): ParseResult = parsePage(html, url).result

    /** [parse], plus the page's text when it holds no recipe data (#103), and the recipes
     *  written inside its article (#435). */
    fun parsePage(html: String, url: String): FetchedPage = parsePage(Jsoup.parse(html, url), url)

    /** [parsePage] for a page already parsed: the fetch's. */
    fun parsePage(doc: Document, url: String): FetchedPage {
        // Microdata only when there is no JSON-LD recipe, so no working site changes.
        val recipe = jsonLdRecipe(doc, url)?.let { refine(doc, url, it) }
            ?: MicrodataRecipeParser.parse(doc, url)?.let { refineMicrodata(doc, url, it) }
        return if (recipe != null) {
            FetchedPage(ParseResult.Success(recipe))
        } else {
            FetchedPage(
                ParseResult.Error(ParseError.NoRecipeFound), PageTextReader.read(doc),
                articles = ArticleRecipeParser.parse(doc, url)
            )
        }
    }

    private fun jsonLdRecipe(doc: Document, url: String): Recipe? = JsonLdRecipeParser.parse(
        doc.select("script[type=application/ld+json]").map { it.data() }, url, JsonLdRecipeParser.pageLanguage(doc)
    )

    /**
     * JSON-LD's recipe, refined by the page: WP Recipe Maker's ingredient parts when they line
     * up (#118), then the group headings JSON-LD drops from a plugin's card (#119) or the
     * site's own (#120), the step photos the site's page alone shows when it proves them (#438),
     * and the site's known noise dropped from the last step (#120, #440).
     */
    private fun refine(doc: Document, url: String, recipe: Recipe): Recipe {
        val photos = recipe.stepPhotos.ifEmpty { SiteRules.stepPhotos(doc, url, recipe.instructions).orEmpty() }
        val steps = SiteRules.steps(url, recipe.instructions)
        return recipe.copy(
            ingredients = SiteRules.ingredients(doc, url, WprmIngredients.refine(doc, recipe.ingredients)),
            instructions = steps,
            // The noise rules only cut the end of the last step, or drop it (#120, #440): its photos go with it (#433).
            stepPhotos = StepPhotos.aligned(photos, steps.size),
        )
    }

    /**
     * A microdata recipe, refined by the page's cards as JSON-LD's is (#437): Smitten Kitchen's
     * Jetpack card names its groups in `<h5>`s between the microdata's own lines. Only the
     * headings, only when the card lines up one-to-one with the microdata's lines; nothing else.
     */
    private fun refineMicrodata(doc: Document, url: String, recipe: Recipe): Recipe =
        recipe.copy(ingredients = SiteRules.ingredients(doc, url, recipe.ingredients))

    /** For the weekly site check (#120): whether each of [url]'s site rules still matches [doc]. */
    fun siteRuleCheck(doc: Document, url: String): Map<String, Boolean>? {
        jsonLdRecipe(doc, url)?.let { recipe ->
            return SiteRules.check(doc, url, WprmIngredients.refine(doc, recipe.ingredients), recipe.instructions)
        }
        val microdata = MicrodataRecipeParser.parse(doc, url) ?: return null
        return SiteRules.check(doc, url, microdata.ingredients, microdata.instructions)
    }
}
