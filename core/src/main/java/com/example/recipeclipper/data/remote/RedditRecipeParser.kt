package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.SourceType
import com.example.recipeclipper.data.model.WebImageUrl
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
 *  2. **The comments.** Otherwise the poster's own comments (`is_submitter`), then every other
 *     comment in the tree by score; the first that splits wins ([RedditCommentScorer]).
 *     AutoModerator's comments are skipped (a subreddit's rules can read like a recipe
 *     template), but not the replies under them.
 *  3. **Nothing found:** [ParseError.NoTranscription], with the post's title and photo. A
 *     legitimate outcome, not a failure: never guess a recipe from prose.
 *
 * Each text is read in the language its words say, the body with the title (#208), else
 * English; the recipe keeps that language unless its name and ingredients clearly say another.
 *
 * The recipe's name is the post title; its photo is the post's image. A crosspost with no body
 * or photo of its own borrows the original's; its comments are on the original's thread, so
 * [read] names that post for the source to fetch. Text that isn't a post listing at all is
 * [ParseError.NoRecipeFound].
 *
 * Pure: JSON text in, a [ParseResult] out.
 */
object RedditRecipeParser {

    /** How deep the comment walk goes. Reddit itself stops nesting long before this. */
    private const val MAX_DEPTH = 50

    /** What [read] made of a listing: the [result] and, for a crosspost, the id of the post it
     *  shares ([crosspostOf]: `crosspost_parent` "t3_abc123" is "abc123"). */
    class Reading(val result: ParseResult, val crosspostOf: String? = null)

    fun parse(json: String, sourceUrl: String): ParseResult = read(json, sourceUrl).result

    fun read(json: String, sourceUrl: String): Reading {
        val root = try {
            JSONTokener(json).nextValue()
        } catch (e: JSONException) {
            null
        } catch (e: StackOverflowError) {
            // org.json recurses while parsing, so absurd nesting overflows before any guard of
            // ours runs. Narrower than JsonLdRecipeParser's Throwable, with the same reasoning.
            null
        }
        val noRecipe = Reading(ParseResult.Error(ParseError.NoRecipeFound))
        val listings = root as? JSONArray ?: return noRecipe
        val post = listings.optJSONObject(0)
            ?.optJSONObject("data")?.optJSONArray("children")?.optJSONObject(0)
            ?.takeIf { it.optString("kind") == "t3" }?.optJSONObject("data")
            ?: return noRecipe

        val title = stripHtml(post.optString("title")).ifBlank { return noRecipe }
        val original = post.optJSONArray("crosspost_parent_list")?.optJSONObject(0)
        val image = imageOf(post) ?: original?.let(::imageOf)
        val crosspostOf = post.optString("crosspost_parent").removePrefix("t3_").ifEmpty { null }

        val body = bodyOf(post).ifBlank { original?.let(::bodyOf).orEmpty() }
        val split = RecipeTextSplitter.detectAndSplit(body, context = title)
            ?: RedditCommentScorer.pick(comments(listings.optJSONObject(1)))
                ?.let { RecipeTextSplitter.detectAndSplit(it) }
            ?: return Reading(
                ParseResult.Error(
                    ParseError.NoTranscription(
                        title, image, imagesOf(post).ifEmpty { original?.let(::imagesOf).orEmpty() }
                    )
                ),
                crosspostOf
            )

        return Reading(ParseResult.Success(
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
                sourceType = SourceType.REDDIT,
                // Reddit declares no language: the text's words (#208), then the recipe's, decide,
                // else English (#14's rule).
                language = LanguageWords.resolve(split.language, null) {
                    LanguageWords.detectionText(title, split.ingredients)
                }
            )
        ))
    }

    private fun stripHtml(raw: String): String = Jsoup.parse(raw).text()

    private fun bodyOf(post: JSONObject): String =
        post.optString("selftext").takeUnless { it.trim() == "[removed]" || it.trim() == "[deleted]" }.orEmpty()

    /** Every comment in the tree but AutoModerator's, depth first, in the order Reddit sent
     *  them. */
    internal fun comments(listing: JSONObject?): List<RedditComment> {
        val out = mutableListOf<RedditComment>()
        fun walk(node: JSONObject?, depth: Int) {
            if (node == null || depth > MAX_DEPTH) return
            val children = node.optJSONObject("data")?.optJSONArray("children") ?: return
            for (i in 0 until children.length()) {
                val child = children.optJSONObject(i) ?: continue
                if (child.optString("kind") != "t1") continue // "more" stubs have no text
                val data = child.optJSONObject("data") ?: continue
                val body = data.optString("body")
                if (body.isNotBlank() && data.optString("author") != "AutoModerator") {
                    out.add(RedditComment(body, data.optBoolean("is_submitter")))
                }
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
     * `raw_json` off, so `&amp;` is undone (and nothing else is touched: it's a URL). A web
     * image only ([WebImageUrl], #235).
     */
    internal fun imageOf(post: JSONObject): String? {
        post.optJSONObject("preview")?.optJSONArray("images")?.optJSONObject(0)
            ?.optJSONObject("source")?.optString("url")?.let(::webImage)
            ?.let { return it }

        val firstId = post.optJSONObject("gallery_data")?.optJSONArray("items")
            ?.optJSONObject(0)?.optString("media_id")
        if (!firstId.isNullOrEmpty()) {
            val s = post.optJSONObject("media_metadata")?.optJSONObject(firstId)?.optJSONObject("s")
            (s?.optString("u")?.ifEmpty { null } ?: s?.optString("gif")?.ifEmpty { null })
                ?.let(::webImage)
                ?.let { return it }
        }

        val link = post.optString("url_overridden_by_dest").ifEmpty { post.optString("url") }
        val path = try { java.net.URI(link).path.orEmpty() } catch (e: Exception) { "" }
        return link.takeIf { IMAGE_EXTENSION.containsMatchIn(path) }?.let(::webImage)
    }

    /**
     * Every picture of the post, in order, for reading its text (#198): each of a gallery's
     * (`gallery_data` gives the order, `media_metadata` the full-size address), else the one
     * [imageOf] finds.
     */
    internal fun imagesOf(post: JSONObject): List<String> {
        val items = post.optJSONObject("gallery_data")?.optJSONArray("items")
        val metadata = post.optJSONObject("media_metadata")
        if (items != null && metadata != null) {
            val gallery = (0 until items.length()).mapNotNull { i ->
                val id = items.optJSONObject(i)?.optString("media_id").orEmpty()
                val s = metadata.optJSONObject(id)?.optJSONObject("s")
                (s?.optString("u")?.ifEmpty { null } ?: s?.optString("gif")?.ifEmpty { null })
                    ?.let(::webImage)
            }
            if (gallery.isNotEmpty()) return gallery
        }
        return listOfNotNull(imageOf(post))
    }

    private fun webImage(url: String) = WebImageUrl.of(url.replace("&amp;", "&"))
}
