package com.example.recipeclipper.data.remote

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
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.roundToInt

/**
 * Fetches a blog/recipe-site page and hands its JSON-LD blocks to [JsonLdRecipeParser].
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

    override suspend fun fetch(url: String): ParseResult = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 10; Mobile) RecipeClipper/1.0")
                .timeout(timeoutMs)
                .get()

            val ldJsonScripts = doc.select("script[type=application/ld+json]").map { it.data() }
            val recipe = JsonLdRecipeParser.parse(ldJsonScripts, url)
            if (recipe != null) {
                ParseResult.Success(recipe)
            } else {
                ParseResult.Error(ParseError.NoRecipeFound)
            }
        } catch (e: HttpStatusException) {
            // Jsoup throws this for any non-2xx answer. 403/404/429/5xx are usually a bot
            // block that lifts on its own (see ParseError.Blocked); anything else stays a
            // plain fetch failure naming the status.
            ParseResult.Error(ParseError.forHttpStatus(e.statusCode))
        } catch (e: CancellationException) {
            throw e // cancellation is never a failure to report
        } catch (e: IOException) {
            ParseResult.Error(
                when {
                    !connectivity.isOnline() -> ParseError.Offline
                    e is SocketTimeoutException -> ParseError.FetchFailed(e.message, timedOut = true)
                    else -> ParseError.FetchFailed(e.message)
                }
            )
        } catch (e: Exception) {
            ParseResult.Error(ParseError.FetchFailed(e.message))
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

    fun parse(jsonLdBlocks: List<String>, sourceUrl: String): Recipe? {
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
                parseRecipeJson(recipeJson, sourceUrl)?.let { return it }
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

    private fun parseRecipeJson(json: JSONObject, sourceUrl: String): Recipe? {
        val name = stripHtml(json.optString("name")).ifBlank { return null }
        val ingredients = extractStringList(json.opt("recipeIngredient") ?: json.opt("ingredients"))
        val instructions = extractInstructions(json.opt("recipeInstructions"))
        if (ingredients.isEmpty() && instructions.isEmpty()) return null

        return Recipe(
            name = name,
            image = extractImage(json.opt("image")),
            ingredients = ingredients,
            instructions = instructions,
            prepTime = formatDuration(json.optString("prepTime", "")),
            cookTime = formatDuration(json.optString("cookTime", "")),
            totalTime = formatDuration(json.optString("totalTime", "")),
            yield = extractYield(json.opt("recipeYield")),
            sourceUrl = sourceUrl
        )
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
     * Names of a HowToSection that restates the whole recipe in condensed form ahead of the
     * real steps. RecipeTin Eats (WP Recipe Maker) opens with an "Abbreviated Recipe" section
     * holding a one-paragraph summary; kept, it became step 1 in cook mode (with a timer read
     * out of the summary) followed by the same steps again in full. Compared after
     * [stripHtml], trimmed and lowercased; exact matches only, never a substring, so a real
     * section that merely mentions "summary" is untouched. Add a name only once a real site
     * is seen publishing it.
     */
    private val CONDENSED_SECTION_NAMES = setOf(
        "abbreviated recipe",
        "quick version",
        "short version",
        "summary",
        "recipe summary",
        "tl;dr",
        "at a glance",
    )

    private fun isCondensedSection(item: Any?): Boolean =
        item is JSONObject &&
            item.optString("@type").equals("HowToSection", ignoreCase = true) &&
            stripHtml(item.optString("name")).trim().lowercase() in CONDENSED_SECTION_NAMES

    private fun extractInstructions(node: Any?): List<String> {
        val steps = mutableListOf<String>()

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
                // CONDENSED_SECTION_NAMES) - but only when what's left still has steps, so a
                // recipe is never emptied by this. A lone section is never skipped.
                if (sectionCount >= 2 && items.any(::isCondensedSection)) {
                    items.filterNot(::isCondensedSection).forEach(::addStep)
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

    private fun extractYield(node: Any?): String? = when (node) {
        is String -> stripHtml(node).ifBlank { null }
        is JSONArray -> Servings.pickYield(
            (0 until node.length()).mapNotNull { extractYield(node.opt(it)) }
        )
        is JSONObject -> stripHtml(node.optString("value")).ifBlank { null }
        else -> node?.toString()
    }

    private val ISO_DURATION = Regex(
        "^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$",
        RegexOption.IGNORE_CASE
    )

    /**
     * An English duration phrase, as Condé Nast sites (Bon Appétit, Epicurious) publish
     * instead of ISO: "20 minutes", "1 hour", "1 hour 30 minutes", "1 hr, 5 mins",
     * "1 hour and 30 minutes". Whole-string only (used with matchEntire): whole numbers, an
     * hours part and/or a minutes part, nothing else. A range ("1-2 hours", "20 to 25
     * minutes"), a fraction or a word ("Overnight") does not match and is shown as written.
     */
    private val PHRASE_DURATION = Regex(
        "\\s*(?:(\\d+)\\s*(?:hours|hour|hrs|hr|h)" +
            "(?:\\s*,?\\s*(?:\\band\\s+)?(\\d+)\\s*(?:minutes|minute|mins|min|m))?" +
            "|(\\d+)\\s*(?:minutes|minute|mins|min|m))\\s*",
        RegexOption.IGNORE_CASE
    )

    /**
     * Turns an ISO-8601 duration like "PT1H30M", or a plain English phrase like
     * "1 hour 30 minutes", into "1h 30m". Either one totalling zero ("PT0S", "P0D",
     * "0 minutes") is null, so the label is hidden rather than showing "PT0S". Anything else
     * is returned as written (trimmed): never guess at "Overnight" or "20 to 25 minutes".
     */
    internal fun formatDuration(raw: String): String? {
        val text = raw.trim()
        if (text.isBlank()) return null

        ISO_DURATION.find(text)?.takeIf { m -> m.groupValues.drop(1).any { it.isNotBlank() } }?.let { match ->
            val days = match.groupValues[1].toIntOrNull() ?: 0
            val hours = match.groupValues[2].toIntOrNull() ?: 0
            val minutes = match.groupValues[3].toIntOrNull() ?: 0
            val seconds = match.groupValues[4].toDoubleOrNull() ?: 0.0
            return renderMinutes(days * 24 * 60 + hours * 60 + minutes + (seconds / 60.0).roundToInt(), text)
        }

        PHRASE_DURATION.matchEntire(text)?.let { match ->
            val g = match.groupValues
            // A figure too large for an Int is not a real time: show it as written.
            val hours = if (g[1].isEmpty()) 0 else g[1].toIntOrNull() ?: return text
            val minutesText = g[2].ifEmpty { g[3] }
            val minutes = if (minutesText.isEmpty()) 0 else minutesText.toIntOrNull() ?: return text
            return renderMinutes(hours * 60 + minutes, text)
        }

        return text
    }

    /** "1h 30m", "1h" or "20m"; null for a zero total; [asWritten] for a total that
     *  overflowed to a negative number. */
    private fun renderMinutes(totalMinutes: Int, asWritten: String): String? {
        if (totalMinutes == 0) return null
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 -> "${m}m"
            else -> asWritten
        }
    }
}
