package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.Connectivity
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * A Reddit post: one fetch of its public `.json` listing (the post and its comment tree
 * together), handed to [RedditRecipeParser]. A share link (`/r/<sub>/s/<code>`) is followed to
 * the post first, since only the post's own address has a listing. A crosspost with no recipe
 * of its own costs one more fetch, of the original's listing, whose comments may hold it; any
 * failure there keeps the crosspost's own outcome.
 *
 * The endpoint is public and unauthenticated, so heavy use can meet HTTP 429: that is
 * [ParseError.Blocked], like any other refusal. Failures map to causes exactly as in
 * [BlogRecipeSource].
 */
class RedditRecipeSource(
    private val connectivity: Connectivity,
    private val timeoutMs: Int = 15_000,
    /** Where the listing is fetched from. A parameter only so a test can point it at a local
     *  responder. */
    private val base: String = RedditUrls.DEFAULT_BASE
) : RecipeSource {

    override suspend fun fetch(url: String): ParseResult = withContext(Dispatchers.IO) {
        try {
            val postUrl = if (RedditUrls.isShareLink(url)) {
                val response = connect(url).followRedirects(true).ignoreHttpErrors(true).execute()
                val landed = response.url().toString()
                if (RedditUrls.jsonUrl(landed, base) == null) {
                    val status = response.statusCode()
                    return@withContext ParseResult.Error(
                        if (status !in 200..299) ParseError.forHttpStatus(status) else ParseError.NoRecipeFound
                    )
                }
                landed
            } else {
                url
            }
            val jsonUrl = RedditUrls.jsonUrl(postUrl, base)
                ?: return@withContext ParseResult.Error(ParseError.NoRecipeFound)
            val reading = RedditRecipeParser.read(listing(jsonUrl), url)
            val original = reading.crosspostOf?.let { RedditUrls.jsonUrl("https://redd.it/$it", base) }
            if (original == null || (reading.result as? ParseResult.Error)?.error !is ParseError.NoTranscription) {
                return@withContext reading.result
            }
            // A crosspost's comments are on the original's thread: the recipe may be there.
            val fromOriginal = try {
                RedditRecipeParser.parse(listing(original), url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            fromOriginal as? ParseResult.Success ?: reading.result
        } catch (e: HttpStatusException) {
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

    /** Reddit's rendered pages hold no recipe data the blog parsers read, and their text is a
     *  whole thread: a block stays a block, and no page text goes to the on-device model. */
    override fun readsRenderedPage(url: String): Boolean = false

    private fun listing(jsonUrl: String): String = connect(jsonUrl).ignoreContentType(true).execute().body()

    private fun connect(url: String): Connection = Jsoup.connect(url)
        .userAgent(USER_AGENT)
        .timeout(timeoutMs)
        .maxBodySize(MAX_BODY_BYTES)

    companion object {
        /** Reddit asks API clients to say who they are, in this shape. */
        const val USER_AGENT = "android:com.example.recipeclipper:1.0 (RecipeClipper)"

        /** A busy thread's listing runs to a few megabytes; Jsoup's default cap would cut it
         *  off mid-JSON. */
        const val MAX_BODY_BYTES = 16 * 1024 * 1024
    }
}

/**
 * The [RecipeSource] the repository sees: Reddit links go to [reddit], everything else to
 * [blog]. Chosen by host alone ([RedditUrls.isReddit]), and only while [redditOn] (the
 * `reddit` flag, #11): off, a Reddit link is read like any page, as before.
 */
class RoutingRecipeSource(
    private val blog: RecipeSource,
    private val reddit: RecipeSource,
    private val redditOn: () -> Boolean = { true }
) : RecipeSource {
    private fun sourceFor(url: String): RecipeSource =
        if (redditOn() && RedditUrls.isReddit(url)) reddit else blog

    override suspend fun fetch(url: String): ParseResult = sourceFor(url).fetch(url)

    override suspend fun fetchPage(url: String): FetchedPage = sourceFor(url).fetchPage(url)

    override fun readsRenderedPage(url: String): Boolean = sourceFor(url).readsRenderedPage(url)
}
