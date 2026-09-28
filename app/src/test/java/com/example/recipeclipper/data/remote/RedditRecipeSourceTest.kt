package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.PageText
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.SourceType
import com.example.recipeclipper.fake.FakeConnectivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * [RedditRecipeSource] against a real local HTTP responder (as [BlogRecipeSourceStatusTest]),
 * pointed at it through the `base` parameter, so the request it makes and the way it names a
 * failure are what's tested. Plus the host routing in front of it.
 */
class RedditRecipeSourceTest {

    private lateinit var server: ServerSocket
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    @Volatile private var jsonStatus = 200
    @Volatile private var jsonBody = RedditFixtures.SELF_POST
    /** Listings by the path they're asked for at, before [jsonBody]. */
    @Volatile private var listings: Map<String, String> = emptyMap()
    @Volatile private var listingStatus: Map<String, Int> = emptyMap()

    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val target = reader.readLine().orEmpty().split(' ').getOrElse(1) { "" }
                    while (reader.readLine()?.isNotEmpty() == true) Unit // skip the headers
                    requests.add(target)
                    val path = target.substringBefore('?')
                    val (head, body) = when {
                        // What Reddit sends for a share link: a 301 to the post with the
                        // share's tracking query on it.
                        "/s/" in target ->
                            "HTTP/1.1 301 Moved Permanently\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                "Location: $base/r/recipes/comments/1f4b2cd/weeknight_lemon_chicken_orzo/?share_id=oqqXWtvgcCuonkcpck8yD" +
                                "&utm_content=1&utm_medium=android_app&utm_name=androidcss&utm_source=share&utm_term=1\r\n" to
                                "<a href=\"x\">Moved Permanently</a>."
                        ".json" in target ->
                            "HTTP/1.1 ${listingStatus[path] ?: jsonStatus} Status\r\nContent-Type: application/json; charset=utf-8\r\n" to
                                (listings[path] ?: jsonBody)
                        else ->
                            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" to "<html></html>"
                    }
                    val bytes = body.toByteArray()
                    val full = head + "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(full.toByteArray()); write(bytes); flush() }
                }
            }
        }
    }

    @After fun stop() = server.close()

    private fun fetch(url: String): ParseResult = runBlocking {
        RedditRecipeSource(FakeConnectivity(), base = base).fetch(url)
    }

    private val postUrl = "https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo/?utm_source=share"

    @Test fun `one fetch of the post's json listing makes the recipe`() {
        val result = fetch(postUrl)
        val recipe = (result as ParseResult.Success).recipe
        assertEquals("Weeknight Lemon Chicken Orzo", recipe.name)
        assertEquals(SourceType.REDDIT, recipe.sourceType)
        assertEquals(postUrl, recipe.sourceUrl)
        assertEquals(listOf("/r/recipes/comments/1abc01/lemon_orzo.json?raw_json=1&limit=200"), requests)
    }

    @Test fun `a share link is followed to the post first, and the share's query is left off the listing`() {
        val share = "$base/r/recipes/s/AbCd123"
        val result = fetch(share)
        assertEquals(share, (result as ParseResult.Success).recipe.sourceUrl)
        assertEquals(
            listOf(
                "/r/recipes/s/AbCd123",
                "/r/recipes/comments/1f4b2cd/weeknight_lemon_chicken_orzo/?share_id=oqqXWtvgcCuonkcpck8yD" +
                    "&utm_content=1&utm_medium=android_app&utm_name=androidcss&utm_source=share&utm_term=1",
                "/r/recipes/comments/1f4b2cd/weeknight_lemon_chicken_orzo.json?raw_json=1&limit=200"
            ),
            requests
        )
    }

    @Test fun `a post with no recipe text is NoTranscription`() {
        jsonBody = RedditFixtures.PHOTO_ONLY
        val error = (fetch(postUrl) as ParseResult.Error).error
        assertTrue(error is ParseError.NoTranscription)
        assertEquals(1, requests.size)
    }

    @Test fun `a crosspost with no recipe of its own reads the original's comments`() {
        jsonBody = RedditFixtures.CROSSPOST
        listings = mapOf("/comments/1f3k9xq.json" to RedditFixtures.IMAGE_WITH_OP_RECIPE)
        val recipe = (fetch(postUrl) as ParseResult.Success).recipe
        assertEquals("Sticky Honey Garlic Chicken Thighs", recipe.name)
        assertEquals("1/3 cup honey", recipe.ingredients[4])
        assertEquals(postUrl, recipe.sourceUrl)
        assertEquals("/comments/1f3k9xq.json?raw_json=1&limit=200", requests.last())
    }

    @Test fun `when the original has no recipe or won't load, the crosspost's own outcome stands`() {
        jsonBody = RedditFixtures.CROSSPOST
        listings = mapOf("/comments/1f3k9xq.json" to RedditFixtures.PHOTO_ONLY)
        val expected = ParseError.NoTranscription(
            "Saw this on r/recipes and had to share",
            "https://preview.redd.it/k2m8x7vq1abd1.jpeg?auto=webp&s=5c1e0f1a2b3c4d5e6f"
        )
        assertEquals(ParseResult.Error(expected), fetch(postUrl))
        listingStatus = mapOf("/comments/1f3k9xq.json" to 429)
        assertEquals(ParseResult.Error(expected), fetch(postUrl))
    }

    @Test fun `429 from the public endpoint is Blocked`() {
        jsonStatus = 429
        assertEquals(ParseResult.Error(ParseError.Blocked(429)), fetch(postUrl))
    }

    @Test fun `a 500 is Blocked and a 400 is a plain fetch failure`() {
        jsonStatus = 500
        assertEquals(ParseResult.Error(ParseError.Blocked(500)), fetch(postUrl))
        jsonStatus = 400
        assertEquals(ParseResult.Error(ParseError.FetchFailed("HTTP 400")), fetch(postUrl))
    }

    @Test fun `a reddit link that isn't a post is NoRecipeFound, without a fetch`() {
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), fetch("https://www.reddit.com/r/recipes/"))
        assertTrue(requests.isEmpty())
    }

    @Test fun `offline is Offline`() = runBlocking {
        server.close() // nothing listening: the connection is refused
        val result = RedditRecipeSource(FakeConnectivity(online = false), base = base).fetch(postUrl)
        assertEquals(ParseResult.Error(ParseError.Offline), result)
    }

    // --- Routing ---

    private class RecordingSource(
        private val result: ParseResult,
        private val page: PageText? = null,
        private val readsPages: Boolean = true
    ) : RecipeSource {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String): ParseResult {
            fetched.add(url)
            return result
        }
        override suspend fun fetchPage(url: String) = FetchedPage(fetch(url), page)
        override fun readsRenderedPage(url: String) = readsPages
    }

    private val redditPost = "https://www.reddit.com/r/recipes/comments/abc/x/"

    @Test fun `routing sends reddit hosts to the reddit source and everything else to the blog one`() = runBlocking {
        val blog = RecordingSource(ParseResult.Error(ParseError.NoRecipeFound))
        val reddit = RecordingSource(ParseResult.Error(ParseError.Offline))
        val router = RoutingRecipeSource(blog, reddit)

        router.fetch("https://www.reddit.com/r/recipes/comments/abc/x/")
        router.fetch("https://old.reddit.com/r/recipes/comments/abc/x/")
        router.fetch("https://redd.it/abc")
        router.fetch("https://www.seriouseats.com/reddit-inspired-pasta")
        router.fetch("https://www.notreddit.com/r/x/comments/abc/")

        assertEquals(3, reddit.fetched.size)
        assertEquals(
            listOf("https://www.seriouseats.com/reddit-inspired-pasta", "https://www.notreddit.com/r/x/comments/abc/"),
            blog.fetched
        )
    }

    @Test fun `with the reddit flag off, a reddit link goes to the blog source as before`() = runBlocking {
        val blog = RecordingSource(ParseResult.Error(ParseError.NoRecipeFound))
        val reddit = RecordingSource(ParseResult.Error(ParseError.Offline), readsPages = false)
        val router = RoutingRecipeSource(blog, reddit, redditOn = { false })

        router.fetch(redditPost)

        assertEquals(listOf(redditPost), blog.fetched)
        assertTrue(reddit.fetched.isEmpty())
        assertTrue(router.readsRenderedPage(redditPost))
    }

    @Test fun `routing passes fetchPage through, so a blog page's text still reaches the model`() = runBlocking {
        val text = PageText(title = "Soup", lines = listOf("A story about soup."))
        val blog = RecordingSource(ParseResult.Error(ParseError.NoRecipeFound), page = text)
        val router = RoutingRecipeSource(blog, RecordingSource(ParseResult.Error(ParseError.Offline)))

        assertEquals(text, router.fetchPage("https://example.com/soup").page)
    }

    @Test fun `a reddit post never goes to the rendered page, a blog page does`() {
        val router = RoutingRecipeSource(
            RecordingSource(ParseResult.Error(ParseError.NoRecipeFound)),
            RedditRecipeSource(FakeConnectivity())
        )
        assertFalse(router.readsRenderedPage(redditPost))
        assertTrue(router.readsRenderedPage("https://example.com/soup"))
    }
}
