package com.example.recipeclipper.data.remote

import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
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
import kotlin.concurrent.thread

/**
 * How [BlogRecipeSource.fetch] names a failure, against a real local HTTP responder, so
 * Jsoup's actual `HttpStatusException` path is what runs. The responder is a bare
 * [ServerSocket]: the JDK's HttpServer isn't on the Android unit-test classpath.
 */
class BlogRecipeSourceStatusTest {

    private lateinit var server: ServerSocket
    @Volatile private var status = 200
    @Volatile private var body = "<html><body>Just a story.</body></html>"

    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { } // skip the request
                    val bytes = body.toByteArray()
                    val head = "HTTP/1.1 $status Status\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    it.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                }
            }
        }
    }

    @After fun stop() = server.close()

    private fun fetch(): ParseResult = runBlocking {
        BlogRecipeSource(FakeConnectivity()).fetch("http://127.0.0.1:${server.localPort}/recipe")
    }

    private fun fetchWithStatus(code: Int): ParseResult {
        status = code
        return fetch()
    }

    @Test fun `403 is Blocked`() = assertEquals(ParseResult.Error(ParseError.Blocked(403)), fetchWithStatus(403))

    @Test fun `404 is Blocked, since sites disguise blocks as not found`() =
        assertEquals(ParseResult.Error(ParseError.Blocked(404)), fetchWithStatus(404))

    @Test fun `429 is Blocked`() = assertEquals(ParseResult.Error(ParseError.Blocked(429)), fetchWithStatus(429))

    @Test fun `500 and 503 are Blocked`() {
        assertEquals(ParseResult.Error(ParseError.Blocked(500)), fetchWithStatus(500))
        assertEquals(ParseResult.Error(ParseError.Blocked(503)), fetchWithStatus(503))
    }

    @Test fun `400 stays a fetch failure naming the status`() =
        assertEquals(ParseResult.Error(ParseError.FetchFailed("HTTP 400")), fetchWithStatus(400))

    @Test fun `a page with no recipe is NoRecipeFound`() =
        assertEquals(ParseResult.Error(ParseError.NoRecipeFound), fetchWithStatus(200))

    @Test fun `a page with only microdata is read through the fallback`() {
        body = MicrodataFixtures.JETPACK
        val result = fetchWithStatus(200)
        assertEquals("Tomato Soup with Crispy Onions", (result as ParseResult.Success).recipe.name)
    }

    @Test fun `JSON-LD wins over microdata on a page with both`() {
        body = MicrodataFixtures.JETPACK.replace(
            "</head>",
            """<script type="application/ld+json">{"@type":"Recipe","name":"From JSON-LD","recipeIngredient":["1 egg"]}</script></head>"""
        )
        val result = fetchWithStatus(200)
        assertEquals("From JSON-LD", (result as ParseResult.Success).recipe.name)
    }

    @Test fun `a refused connection is a fetch failure, not Blocked`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val result = runBlocking { BlogRecipeSource(FakeConnectivity(online = true)).fetch("http://127.0.0.1:$closedPort/recipe") }
        assertTrue(result.toString(), (result as ParseResult.Error).error is ParseError.FetchFailed)
    }

    @Test fun `which statuses count as a block`() {
        listOf(403, 404, 429, 500, 502, 503, 599).forEach { assertTrue("$it", ParseError.isBlockStatus(it)) }
        listOf(400, 401, 410, 418, 499, 600).forEach { assertFalse("$it", ParseError.isBlockStatus(it)) }
    }

    @Test fun `a network failure with no connection is Offline`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val result = runBlocking {
            BlogRecipeSource(FakeConnectivity(online = false)).fetch("http://127.0.0.1:$closedPort/recipe")
        }
        assertEquals(ParseResult.Error(ParseError.Offline), result)
    }

    @Test fun `a timeout is a FetchFailed marked timedOut`() {
        // Bound but never accepting: the OS completes the handshake from the backlog, then
        // nothing is ever sent, so the read times out.
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { silent ->
            val result = runBlocking {
                BlogRecipeSource(FakeConnectivity(), timeoutMs = 300)
                    .fetch("http://127.0.0.1:${silent.localPort}/recipe")
            }
            val error = (result as ParseResult.Error).error
            assertTrue("$error", error is ParseError.FetchFailed && error.timedOut)
        }
    }

    @Test fun `which causes are retried automatically and which reload on reconnect`() {
        assertTrue(ParseError.Blocked(403).shouldAutoRetry)
        assertTrue(ParseError.FetchFailed(null).shouldAutoRetry)
        assertFalse(ParseError.FetchFailed(null, timedOut = true).shouldAutoRetry)
        assertFalse(ParseError.Offline.shouldAutoRetry)
        assertFalse(ParseError.NoRecipeFound.shouldAutoRetry)
        assertFalse(ParseError.SaveFailed.shouldAutoRetry)

        assertTrue(ParseError.Offline.reloadsOnReconnect)
        assertTrue(ParseError.FetchFailed("x", timedOut = true).reloadsOnReconnect)
        assertFalse(ParseError.Blocked(403).reloadsOnReconnect)
        assertFalse(ParseError.NoRecipeFound.reloadsOnReconnect)
    }
}
