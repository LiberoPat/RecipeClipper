package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.SourceType
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup

/**
 * A Reddit post's `.json` listing to a recipe. The response is a two-element array: the post
 * (a `t3`), then its comment tree (`t1`s, each with its `replies`). In order:
 *
 *  1. **The post body.** If `selftext` splits cleanly ([RecipeTextSplitter]), that's the recipe.
 *  2. **A transcription in the comments.** Otherwise every comment in the tree is scored and the
 *     best one that splits wins ([RedditCommentScorer]).
 *  3. **Nothing found:** [ParseError.NoTranscription], with the post's title and photo. A
 *     legitimate outcome, not a failure: never guess a recipe from prose.
 *
 * The recipe's name is the post title; its photo is the post's image. A crosspost with no body
 * or photo of its own borrows the original's. Text that isn't a post listing at all is
 * [ParseError.NoRecipeFound].
 *
 * Pure: JSON text in, a [ParseResult] out.
 */
object RedditRecipeParser {

    /** How deep the comment walk goes. Reddit itself stops nesting long before this. */
    private const val MAX_DEPTH = 50

    fun parse(json: String, sourceUrl: String): ParseResult {
        val root = try {
            JSONTokener(json).nextValue()
        } catch (e: JSONException) {
            null
        } catch (e: StackOverflowError) {
            // org.json recurses while parsing, so absurd nesting overflows before any guard of
            // ours runs. Narrower than JsonLdRecipeParser's Throwable, with the same reasoning.
            null
        }
        val listings = root as? JSONArray ?: return ParseResult.Error(ParseError.NoRecipeFound)
        val post = listings.optJSONObject(0)
            ?.optJSONObject("data")?.optJSONArray("children")?.optJSONObject(0)
            ?.takeIf { it.optString("kind") == "t3" }?.optJSONObject("data")
            ?: return ParseResult.Error(ParseError.NoRecipeFound)

        val title = stripHtml(post.optString("title")).ifBlank {
            return ParseResult.Error(ParseError.NoRecipeFound)
        }
        val original = post.optJSONArray("crosspost_parent_list")?.optJSONObject(0)
        val image = imageOf(post) ?: original?.let(::imageOf)

        val body = bodyOf(post).ifBlank { original?.let(::bodyOf).orEmpty() }
        val split = RecipeTextSplitter.split(body)
            ?: RedditCommentScorer.pick(comments(listings.optJSONObject(1)))?.let(RecipeTextSplitter::split)
            ?: return ParseResult.Error(ParseError.NoTranscription(title, image))

        return ParseResult.Success(
            Recipe(
                name = title,
                image = image,
                ingredients = split.ingredients,
                instructions = split.instructions,
                prepTime = split.prepTime,
                cookTime = split.cookTime,
                totalTime = split.totalTime,
                yield = split.yield,
                sourceUrl = sourceUrl,
                sourceType = SourceType.REDDIT
            )
        )
    }

    private fun stripHtml(raw: String): String = Jsoup.parse(raw).text()

    private fun bodyOf(post: JSONObject): String =
        post.optString("selftext").takeUnless { it.trim() == "[removed]" || it.trim() == "[deleted]" }.orEmpty()

    /** Every comment body in the tree, depth first, in the order Reddit sent them. */
    internal fun comments(listing: JSONObject?): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: JSONObject?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            val children = node.optJSONObject("data")?.optJSONArray("children") ?: return
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                if (child.optString("kind") != "t1") continue // "more" stubs have no text
                val data = child.optJSONObject("data") ?: continue
                val body = data.optString("body")
                if (body.isNotBlank()) out.add(body)
                walk(data.optJSONObject("replies"), depth + 1) // "" when there are none
            }
        }
        walk(listing, 0)
        return out
    }

    private val IMAGE_EXTENSION = Regex("\\.(?:jpe?g|png|gif|webp)$", RegexOption.IGNORE_CASE)

    /**
     * The post's photo: the preview Reddit renders for it, else the first picture of a
     * gallery, else a link straight to an image. Reddit escapes `&` in these URLs even with
     * `raw_json` off, so `&amp;` is undone (and nothing else is touched: it's a URL).
     */
    internal fun imageOf(post: JSONObject): String? {
        post.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)
            ?.optJSONObject("source")?.optString("url")?.takeIf { it.startsWith("http") }
            ?.let { return unescape(it) }

        val firstId = post.optJSONObject("gallery_data")?.optJSONArray("items")
            ?.optJSONObject(0)?.optString("media_id")
        if (!firstId.isNullOrEmpty()) {
            val s = post.optJSONObject("media_metadata")?.optJSONObject(firstId)?.optJSONObject("s")
            (s?.optString("u")?.ifEmpty { null } ?: s?.optString("gif")?.ifEmpty { null })
                ?.takeIf { it.startsWith("http") }
                ?.let { return unescape(it) }
        }

        val link = post.optString("url_overridden_by_dest").ifEmpty { post.optString("url") }
        val path = try { java.net.URI(link).path.orEmpty() } catch (e: Exception) { "" }
        return link.takeIf { it.startsWith("http") && IMAGE_EXTENSION.containsMatchIn(path) }?.let(::unescape)
    }

    private fun unescape(url: String) = url.replace("&amp;", "&")
}
