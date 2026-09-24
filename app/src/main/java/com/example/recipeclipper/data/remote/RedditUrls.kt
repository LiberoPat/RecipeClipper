package com.example.recipeclipper.data.remote

import java.net.URI

/**
 * Which links are Reddit's, and the `.json` address of a post. Pure string work.
 *
 * A post link is anything with a `/comments/<id>` path on reddit.com or a subdomain of it
 * (www, old, new, m, np), plus the `redd.it/<id>` and `reddit.com/gallery/<id>` short forms.
 * The Reddit app shares `reddit.com/r/<sub>/s/<code>` links instead, which only redirect to a
 * post: [isShareLink] marks those, and the source follows the redirect first.
 */
object RedditUrls {

    const val DEFAULT_BASE = "https://www.reddit.com"

    /** `raw_json=1` returns the Markdown as written rather than HTML-escaped; `limit` bounds
     *  the comment tree for a very busy thread. */
    private const val QUERY = "?raw_json=1&limit=200"

    private val SHARE_PATH = Regex("^/r/[^/]+/s/[^/]+/?$")
    private val COMMENTS_PATH = Regex("^(.*?/comments/[A-Za-z0-9]+)(/.*)?$")
    private val ID = Regex("^[A-Za-z0-9]+$")

    private fun parts(url: String): Pair<String, String>? = try {
        val uri = URI(url.trim())
        val host = uri.host?.lowercase() ?: return null
        host to (uri.rawPath ?: "")
    } catch (e: Exception) {
        null
    }

    fun isReddit(url: String): Boolean {
        val host = parts(url)?.first ?: return false
        return host == "reddit.com" || host.endsWith(".reddit.com") || host == "redd.it"
    }

    fun isShareLink(url: String): Boolean {
        val (host, path) = parts(url) ?: return false
        return host != "redd.it" && SHARE_PATH.matches(path)
    }

    /**
     * The post's JSON listing (post, then its comment tree) on [base], or null when [url]
     * isn't a link to one post. The query string and fragment are dropped; a path to one
     * comment (`/comments/<id>/<slug>/<commentId>/`) is kept, so that comment's thread is
     * what comes back.
     */
    fun jsonUrl(url: String, base: String = DEFAULT_BASE): String? {
        val (host, rawPath) = parts(url) ?: return null
        val segments = rawPath.split('/').filter { it.isNotEmpty() }
        val path = when {
            host == "redd.it" ->
                segments.singleOrNull()?.takeIf { ID.matches(it) }?.let { "/comments/$it" }
            segments.size == 2 && segments[0] == "gallery" && ID.matches(segments[1]) ->
                "/comments/${segments[1]}"
            else -> {
                val m = COMMENTS_PATH.matchEntire(rawPath) ?: return null
                (m.groupValues[1] + m.groupValues[2]).trimEnd('/').removeSuffix(".json")
            }
        } ?: return null
        return base.trimEnd('/') + path + ".json" + QUERY
    }
}
