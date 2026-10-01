package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.Connectivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Fetches a blog/recipe-site page and hands it to [BlogPageParser] (in `:core`, #238): its
 * JSON-LD blocks to [JsonLdRecipeParser], or, when they hold no recipe, the page to
 * [MicrodataRecipeParser].
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
            BlogPageParser.parsePage(doc, url)
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
         * main thread. iOS has the same `BlogRecipeSource.parse(html:url:)`. The parsing is
         * [BlogPageParser]'s, in `:core` (#238).
         */
        fun parse(html: String, url: String): ParseResult = BlogPageParser.parse(html, url)

        /** [parse], plus the page's text when it holds no recipe data (#103). */
        fun parsePage(html: String, url: String): FetchedPage = BlogPageParser.parsePage(html, url)

        /** For the weekly site check (#120): whether each of [url]'s site rules still matches [doc]. */
        internal fun siteRuleCheck(doc: Document, url: String): Map<String, Boolean>? = BlogPageParser.siteRuleCheck(doc, url)
    }
}
