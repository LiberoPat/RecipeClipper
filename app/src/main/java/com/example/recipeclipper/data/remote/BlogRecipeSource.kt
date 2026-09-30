package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.Durations
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.Servings
import com.example.recipeclipper.data.Connectivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Fetches a blog/recipe-site page and hands its JSON-LD blocks to [JsonLdRecipeParser], or,
 * when they hold no recipe, the page to [MicrodataRecipeParser].
 *
 * Failures come back as causes (see [ParseError]): a non-2xx answer is [ParseError.Blocked] or
 * [ParseError.FetchFailed] by status; a network failure while [connectivity] reports no
 * network is [ParseError.Offline]; a timeout is a [ParseError.FetchFailed] marked `timedOut`;
 * any other failure is a plain [ParseError.FetchFailed]. Offline is decided by asking
 * [connectivity] rather than by exception type alone: an `UnknownHostException` means
 * "offline" on a phone with no network but "no such site" on one with, and only the
 * connectivity check can tell those apart.
 */
class BlogRecipeSource(
    private val connectivity: Connectivity,
    /** Caps the whole request, connect plus body. A parameter only so a test needn't wait 15 s. */
    private val timeoutMs: Int = 15_000
) : RecipeSource {

    override suspend fun fetch(url: String): ParseResult = fetchPage(url).result

    override suspend fun fetchPage(url: String): FetchedPage = fetchPage(url) {}

    /** [fetchPage], handing the loaded page to [inspect] first: the weekly site check's view of its site rules (#120). */
    internal suspend fun fetchPage(url: String, inspect: (Document) -> Unit): FetchedPage = withContext(Dispatchers.IO) {
        try {
            // HTTP errors are let through, so a refusal's header and body can say whether it was
            // Cloudflare's challenge (#220); the content type is then checked for 2xx only, as
            // Jsoup's own get() does.
            val response = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 10; Mobile) RecipeClipper/1.0")
                .timeout(timeoutMs)
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                .execute()
            val status = response.statusCode()
            if (status !in 200..299) {
                // 403/404/429/5xx are usually a bot block that lifts on its own (see
                // ParseError.Blocked); anything else stays a plain fetch failure naming the status.
                val challenge = CloudflareChallenge.isChallengeResponse(
                    status, response.header("cf-mitigated"), runCatching { response.body() }.getOrNull()
                )
                return@withContext FetchedPage(ParseResult.Error(ParseError.forHttpStatus(status)), challenge = challenge)
            }
            if (!isReadable(response.contentType())) {
                return@withContext FetchedPage(
                    ParseResult.Error(ParseError.FetchFailed("Unhandled content type ${response.contentType()}"))
                )
            }
            val doc = response.parse()

            inspect(doc)
            parsePage(doc, url)
        } catch (e: CancellationException) {
            throw e // cancellation is never a failure to report
        } catch (e: IOException) {
            val cause = when {
                !connectivity.isOnline() -> ParseError.Offline
                e is SocketTimeoutException -> ParseError.FetchFailed(e.message, timedOut = true)
                else -> ParseError.FetchFailed(e.message)
            }
            FetchedPage(ParseResult.Error(cause))
        } catch (e: Exception) {
            FetchedPage(ParseResult.Error(ParseError.FetchFailed(e.message)))
        }
    }

    companion object {
        private val XML_TYPE = Regex("""^(application|text)/\w*\+?xml.*""")

        /** What Jsoup's get() would parse: no content type, any `text/` type, or an XML type. */
        private fun isReadable(contentType: String?): Boolean =
            contentType == null || contentType.startsWith("text/") || XML_TYPE.matches(contentType)

        /**
         * The HTML-to-recipe step on its own, for a page that didn't come through [fetch]: the
         * HTML a [RenderedPageSource] returns. Pure and CPU-bound, so callers run it off the
         * main thread. iOS has the same `BlogRecipeSource.parse(html:url:)`.
         */
        fun parse(html: String, url: String): ParseResult = parsePage(html, url).result

        /** [parse], plus the page's text when it holds no recipe data (#103). */
        fun parsePage(html: String, url: String): FetchedPage = parsePage(Jsoup.parse(html, url), url)

        private fun parsePage(doc: Document, url: String): FetchedPage {
            // Microdata only when there is no JSON-LD recipe, so no working site changes.
            val recipe = jsonLdRecipe(doc, url)?.let { refine(doc, url, it) } ?: MicrodataRecipeParser.parse(doc, url)
            return if (recipe != null) {
                FetchedPage(ParseResult.Success(recipe))
            } else {
                FetchedPage(ParseResult.Error(ParseError.NoRecipeFound), PageTextReader.read(doc))
            }
        }

        private fun jsonLdRecipe(doc: Document, url: String): Recipe? = JsonLdRecipeParser.parse(
            doc.select("script[type=application/ld+json]").map { it.data() }, url, JsonLdRecipeParser.pageLanguage(doc)
        )

        /**
         * JSON-LD's recipe, refined by the page: WP Recipe Maker's ingredient parts when they line
         * up (#118), then the group headings JSON-LD drops from a plugin's card (#119) or the
         * site's own (#120), and the site's known noise dropped from the last step (#120).
         */
        private fun refine(doc: Document, url: String, recipe: Recipe): Recipe = recipe.copy(
            ingredients = SiteRules.ingredients(doc, url, WprmIngredients.refine(doc, recipe.ingredients)),
            instructions = SiteRules.steps(url, recipe.instructions),
        )

        /** For the weekly site check (#120): whether each of [url]'s site rules still matches [doc]. */
        internal fun siteRuleCheck(doc: Document, url: String): Map<String, Boolean>? {
            val recipe = jsonLdRecipe(doc, url) ?: return null
            return SiteRules.check(doc, url, WprmIngredients.refine(doc, recipe.ingredients), recipe.instructions)
        }
    }
}

/**
 * Almost every recipe site embeds a schema.org "Recipe" object as JSON-LD
 * inside a <script type="application/ld+json"> tag - that's the data Google
 * uses to build the recipe rich-snippet cards in search results. It already
 * contains just the title, ingredients, steps, times and servings, with none
 * of the surrounding story/ads/keyword filler. This parser reads that data
 * directly instead of scraping the visible article text.
 *
 * Pure: takes the raw text of each JSON-LD script block, does no network I/O.
 */
internal object JsonLdRecipeParser {

    /** How deep [findRecipeNode] will recurse through nested JSON-LD before giving up. Far
     *  deeper than any real `@graph` needs, nowhere near the JVM stack limit. */
    private const val MAX_DEPTH = 50

    /** Strips HTML tags and unescapes entities in text pulled out of JSON-LD, e.g. a
     *  `recipeInstructions.text` of "Mix &amp; pour" or "<p>Preheat the oven.</p>". Parsing a
     *  string (not fetching a URL) does no network I/O, so this keeps the parser pure. */
    private fun stripHtml(raw: String): String = Jsoup.parse(raw).text()

    /** The page's declared language, `<html lang>`, which a recipe without `inLanguage` takes. */
    fun pageLanguage(doc: Document): String? = doc.selectFirst("html")?.attr("lang")?.ifBlank { null }

    /** [pageLanguage] is the page's `<html lang>`, the fallback for a recipe without `inLanguage`. */
    fun parse(jsonLdBlocks: List<String>, sourceUrl: String, pageLanguage: String? = null): Recipe? {
        for (block in jsonLdBlocks) {
            val recipeJson = try {
                findRecipeNode(JSONTokener(block).nextValue())
            } catch (e: Throwable) {
                // JSONTokener.nextValue() recurses while parsing, before findRecipeNode's own
                // depth cap ever runs, so pathologically nested input can overflow the stack
                // here with a StackOverflowError - an Error, not an Exception. Catching
                // Throwable is deliberate at this one boundary: the parse either produced a
                // value or it didn't, nothing is half-written, so treating any failure here
                // the same as a block that didn't parse (skip to the next <script> tag) is
                // safe. Do not widen this pattern elsewhere, e.g. the outer catch in fetch()
                // below, which must stay Exception so coroutine cancellation still works.
                null
            }
            if (recipeJson != null) {
                parseRecipeJson(recipeJson, sourceUrl, pageLanguage)?.let { return it }
            }
        }
        return null
    }

    // --- Locating the Recipe object inside arbitrarily nested JSON-LD ---

    private fun findRecipeNode(node: Any?, depth: Int = 0): JSONObject? {
        if (depth > MAX_DEPTH) return null
        when (node) {
            is JSONObject -> {
                if (isRecipeType(node.opt("@type"))) return node
                if (node.has("@graph")) {
                    findRecipeNode(node.opt("@graph"), depth + 1)?.let { return it }
                }
                val keys = node.keys()
                for (key in keys) {
                    val child = node.opt(key)
                    if (child is JSONObject || child is JSONArray) {
                        findRecipeNode(child, depth + 1)?.let { return it }
                    }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findRecipeNode(node.opt(i), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    private fun isRecipeType(type: Any?): Boolean = when (type) {
        is String -> type.equals("Recipe", ignoreCase = true)
        is JSONArray -> (0 until type.length()).any {
            (type.opt(it) as? String)?.equals("Recipe", ignoreCase = true) == true
        }

        else -> false
    }

    // --- Turning the matched Recipe JSON object into our data class ---

    private fun parseRecipeJson(json: JSONObject, sourceUrl: String, pageLanguage: String?): Recipe? {
        val name = stripHtml(json.optString("name")).ifBlank { return null }
        val ingredients = extractStringList(json.opt("recipeIngredient") ?: json.opt("ingredients"))
        // The ingredient lines are read the same in any language; the steps, times and yield
        // need the language's words (condensed-section names, duration and range words).
        val language = LanguageWords.resolve(declaredLanguage(json.opt("inLanguage")), pageLanguage) {
            LanguageWords.detectionText(name, ingredients)
        }
        val words = LanguageWords.forTag(language)
        val instructions = extractInstructions(json.opt("recipeInstructions"), words)
        if (ingredients.isEmpty() && instructions.isEmpty()) return null

        return Recipe(
            name = name,
            image = extractImage(json.opt("image")),
            ingredients = ingredients,
            instructions = instructions,
            prepTime = Durations.format(json.optString("prepTime", ""), words),
            cookTime = Durations.format(json.optString("cookTime", ""), words),
            totalTime = Durations.format(json.optString("totalTime", ""), words),
            yield = extractYield(json.opt("recipeYield"), words),
            sourceUrl = sourceUrl,
            language = language
        )
    }

    /** `inLanguage` as a tag ("en-US"), or a schema.org Language with one in `alternateName`. */
    private fun declaredLanguage(node: Any?): String? = when (node) {
        is String -> node
        is JSONObject -> node.optString("alternateName").ifBlank { null }
        is JSONArray -> if (node.length() > 0) declaredLanguage(node.opt(0)) else null
        else -> null
    }

    private fun extractImage(node: Any?): String? = when (node) {
        is String -> node
        is JSONObject -> node.optString("url").ifBlank { null }
        is JSONArray -> if (node.length() > 0) extractImage(node.opt(0)) else null
        else -> null
    }

    private fun extractStringList(node: Any?): List<String> {
        val result = mutableListOf<String>()
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) {
                (node.opt(i) as? String)?.let { stripHtml(it).trim() }
                    ?.let { if (it.isNotEmpty()) result.add(it) }
            }

            is String -> stripHtml(node).trim().let { if (it.isNotEmpty()) result.add(it) }
        }
        return result
    }

    /**
     * Whether [item] is a HowToSection that restates the whole recipe in condensed form ahead
     * of the real steps. RecipeTin Eats (WP Recipe Maker) opens with an "Abbreviated Recipe"
     * section holding a one-paragraph summary; kept, it became step 1 in cook mode (with a
     * timer read out of the summary) followed by the same steps again in full. The names are
     * the language's `sections.json`, compared after [stripHtml], trimmed and lowercased;
     * exact matches only, never a substring, so a real section that merely mentions "summary"
     * is untouched. Add a name only once a real site is seen publishing it.
     */
    private fun isCondensedSection(item: Any?, names: Set<String>): Boolean =
        item is JSONObject &&
            item.optString("@type").equals("HowToSection", ignoreCase = true) &&
            stripHtml(item.optString("name")).trim().lowercase() in names

    private fun condensedSectionNames(words: LanguageWords?): Set<String> =
        words?.compiled(CondensedSections::class) { w ->
            CondensedSections(w.strings("sections", "condensed").toSet())
        }?.names.orEmpty()

    private class CondensedSections(val names: Set<String>)

    private fun extractInstructions(node: Any?, words: LanguageWords?): List<String> {
        val steps = mutableListOf<String>()
        val condensedNames = condensedSectionNames(words)
        fun condensed(item: Any?) = isCondensedSection(item, condensedNames)

        // HowToSection names ("For the sauce") are not emitted: sections are flattened into
        // one list of steps, so cook mode's step numbering stays simple. Showing them as
        // headers in the reading view would be a possible future feature; it would need the
        // section boundaries carried through Recipe rather than dropped here.
        fun addStep(item: Any?) {
            when (item) {
                is String -> stripHtml(item).trim().let { if (it.isNotEmpty()) steps.add(it) }
                is JSONObject -> {
                    val type = item.optString("@type")
                    if (type.equals("HowToSection", ignoreCase = true)) {
                        val nested = item.optJSONArray("itemListElement")
                        if (nested != null) for (i in 0 until nested.length()) addStep(nested.opt(i))
                    } else {
                        val text = stripHtml(item.optString("text").ifBlank { item.optString("name") })
                        if (text.isNotBlank()) steps.add(text.trim())
                    }
                }
            }
        }

        when (node) {
            is JSONArray -> {
                val items = (0 until node.length()).map { node.opt(it) }
                val sectionCount = items.count {
                    it is JSONObject && it.optString("@type").equals("HowToSection", ignoreCase = true)
                }
                // With two or more sections, drop a condensed duplicate of the recipe (see
                // isCondensedSection) - but only when what's left still has steps, so a
                // recipe is never emptied by this. A lone section is never skipped.
                if (sectionCount >= 2 && items.any(::condensed)) {
                    items.filterNot(::condensed).forEach(::addStep)
                    if (steps.isEmpty()) items.forEach(::addStep)
                } else {
                    items.forEach(::addStep)
                }
            }
            // Strip each line AFTER splitting on the raw string's literal newlines, not
            // before: running the whole block through Jsoup first would normalize a
            // <br>-separated set of lines into one collapsed line.
            is String -> steps.addAll(node.split("\n").map { stripHtml(it).trim() }.filter { it.isNotEmpty() })
        }
        return steps
    }

    private fun extractYield(node: Any?, words: LanguageWords?): String? = when (node) {
        is String -> stripHtml(node).ifBlank { null }
        is JSONArray -> Servings.pickYield(
            (0 until node.length()).mapNotNull { extractYield(node.opt(it), words) },
            words
        )
        is JSONObject -> stripHtml(node.optString("value")).ifBlank { null }
        else -> node?.toString()
    }
}
