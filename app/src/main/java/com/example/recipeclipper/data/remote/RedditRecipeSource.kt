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
 * the post first, since only the post's own address has a listing.
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
            val body = connect(jsonUrl).ignoreContentType(true).execute().body()
            RedditRecipeParser.parse(body, url)
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
 * [blog]. Chosen by host alone ([RedditUrls.isReddit]).
 */
class RoutingRecipeSource(
    private val blog: RecipeSource,
    private val reddit: RecipeSource
) : RecipeSource {
    override suspend fun fetch(url: String): ParseResult =
        if (RedditUrls.isReddit(url)) reddit.fetch(url) else blog.fetch(url)
}
