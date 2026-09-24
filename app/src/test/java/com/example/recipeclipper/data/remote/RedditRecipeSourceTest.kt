package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.SourceType
import com.example.recipeclipper.fake.FakeConnectivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
                    val (head, body) = when {
                        "/s/" in target ->
                            "HTTP/1.1 301 Moved\r\nLocation: /r/recipes/comments/1abc01/lemon_orzo/?share_id=x\r\n" to ""
                        ".json" in target ->
                            "HTTP/1.1 $jsonStatus Status\r\nContent-Type: application/json; charset=utf-8\r\n" to jsonBody
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

    @Test fun `a share link is followed to the post first`() {
        val share = "$base/r/recipes/s/AbCd123"
        val result = fetch(share)
        assertEquals(share, (result as ParseResult.Success).recipe.sourceUrl)
        assertEquals("/r/recipes/s/AbCd123", requests.first())
        assertEquals("/r/recipes/comments/1abc01/lemon_orzo.json?raw_json=1&limit=200", requests.last())
    }

    @Test fun `a post with no recipe text is NoTranscription`() {
        jsonBody = RedditFixtures.PHOTO_ONLY
        val error = (fetch(postUrl) as ParseResult.Error).error
        assertTrue(error is ParseError.NoTranscription)
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

    private class RecordingSource(private val result: ParseResult) : RecipeSource {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String): ParseResult {
            fetched.add(url)
            return result
        }
    }

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
}
