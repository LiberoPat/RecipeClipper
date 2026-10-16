package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The retry for an unreadable reply and the runaway detector (#273). */
class PageReplyTest {

    private val cake = PageSelection("Cake", listOf("2 cups flour"), listOf("Bake it."))

    /** Answers each ask with the next reply, recording whether it was the retry. */
    private class Asks(vararg replies: PageReply) {
        private val queue = ArrayDeque(replies.toList())
        val retries = mutableListOf<Boolean>()
        fun ask(retry: Boolean): PageReply { retries += retry; return queue.removeFirst() }
    }

    @Test fun `a readable reply is used, with no retry`() {
        val asks = Asks(PageReply.Picked(cake))
        assertEquals(cake, PageReplyRetry.pick(asks::ask))
        assertEquals(listOf(false), asks.retries)
    }

    @Test fun `an unreadable reply is retried once`() {
        val asks = Asks(PageReply.Unreadable, PageReply.Picked(cake))
        assertEquals(cake, PageReplyRetry.pick(asks::ask))
        assertEquals(listOf(false, true), asks.retries)
    }

    @Test fun `a second unreadable reply gives up`() {
        val asks = Asks(PageReply.Unreadable, PageReply.Unreadable, PageReply.Picked(cake))
        assertNull(PageReplyRetry.pick(asks::ask))
        assertEquals(listOf(false, true), asks.retries)
    }

    @Test fun `a retry that fails gives up`() {
        val asks = Asks(PageReply.Unreadable, PageReply.Failed)
        assertNull(PageReplyRetry.pick(asks::ask))
        assertEquals(listOf(false, true), asks.retries)
    }

    @Test fun `a failure (busy, quota, background) is never retried`() {
        val asks = Asks(PageReply.Failed, PageReply.Picked(cake))
        assertNull(PageReplyRetry.pick(asks::ask))
        assertEquals(listOf(false), asks.retries)
    }

    @Test fun `a reply cut off while repeating a line runs away`() {
        val reply = "{\n  \"name\": \"Chow Mein\",\n  \"ingredients\": [\n" +
            "    \"1/4 cup (65 ml) water\",\n".repeat(4) + "    \"1/4 cup (6"
        assertTrue(RunawayReply.isRunaway(reply))
        assertFalse(RunawayReply.isRunaway(reply.substringBeforeLast("    \"1/4 cup (65 ml) water\",")))
    }

    @Test fun `a time padded with prose runs away`() {
        val reply = "{\"name\": \"Tots\", \"ingredients\": [\"1 egg\"], \"prepTime\": \"30 minutes" + " mins, 30 minutes".repeat(10)
        assertTrue(RunawayReply.isRunaway(reply))
        assertFalse(RunawayReply.isRunaway("{\"name\": \"Tots\", \"prepTime\": \"1 hour 30 minutes, plus overnight soaking\"}"))
    }

    @Test fun `a long name or line runs away`() {
        assertTrue(RunawayReply.isRunaway(PageSelection("x".repeat(201), emptyList(), emptyList())))
        assertTrue(RunawayReply.isRunaway(PageSelection("Cake", emptyList(), listOf("y".repeat(2_001)))))
        assertFalse(RunawayReply.isRunaway(PageSelection("x".repeat(200), emptyList(), listOf("y".repeat(2_000)))))
    }

    @Test fun `a line three times running, or four times apart, is a recipe`() {
        assertFalse(RunawayReply.isRunaway(PageSelection("Cake", List(3) { "1 egg" }, emptyList())))
        assertFalse(RunawayReply.isRunaway(PageSelection("Cake", listOf("1 egg", "salt", "1 egg", "salt", "1 egg", "salt", "1 egg"), emptyList())))
        assertTrue(RunawayReply.isRunaway(PageSelection("Cake", emptyList(), List(4) { "Stir." })))
    }

    @Test fun `scan reads the fields so far, leniently`() {
        val reply = "```json\n{\"steps\": [\"Mix \\\"well\\\".\", \"Bake\\u00e9\"], \"name\": \"Cake\", " +
            "\"extra\": {\"name\": \"no\", \"ingredients\": [\"no\"]}, \"yield\": null, \"cookTime\": \"PT30M\", " +
            "\"ingredients\": [\"2 cups flour\", \"1 e"
        assertEquals(
            PageSelection("Cake", listOf("2 cups flour", "1 e"), listOf("Mix \"well\".", "Bakeé"), cookTime = "PT30M"),
            RunawayReply.scan(reply)
        )
        assertEquals(PageSelection(null, emptyList(), emptyList()), RunawayReply.scan(""))
    }
}
