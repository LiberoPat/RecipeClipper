package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iOS suite (`RedditUrlsTests.swift`) has the same cases and expectations. */
class RedditUrlsTest {

    @Test fun `reddit hosts are recognised, lookalikes are not`() {
        listOf(
            "https://www.reddit.com/r/recipes/comments/abc/x/",
            "https://reddit.com/r/recipes/comments/abc/x/",
            "https://old.reddit.com/r/recipes/comments/abc/x/",
            "https://m.reddit.com/r/recipes/comments/abc/x/",
            "https://redd.it/abc",
            "https://www.reddit.com/r/recipes/s/AbCd123",
        ).forEach { assertTrue(it, RedditUrls.isReddit(it)) }
        listOf(
            "https://www.notreddit.com/r/recipes/comments/abc/x/",
            "https://reddit.com.evil.example/r/x/comments/abc/",
            "https://www.seriouseats.com/reddit-recipe",
            "not a url",
        ).forEach { assertFalse(it, RedditUrls.isReddit(it)) }
    }

    @Test fun `a post link becomes its json listing on www, query and fragment dropped`() {
        val expected = "https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo.json?raw_json=1&limit=200"
        assertEquals(expected, RedditUrls.jsonUrl("https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo/"))
        assertEquals(expected, RedditUrls.jsonUrl("https://old.reddit.com/r/recipes/comments/1abc01/lemon_orzo/?share_id=x#top"))
        assertEquals(expected, RedditUrls.jsonUrl("https://reddit.com/r/recipes/comments/1abc01/lemon_orzo"))
        assertEquals(expected, RedditUrls.jsonUrl("https://www.reddit.com/r/recipes/comments/1abc01/lemon_orzo.json"))
    }

    @Test fun `short and slugless forms`() {
        assertEquals(
            "https://www.reddit.com/comments/1abc01.json?raw_json=1&limit=200",
            RedditUrls.jsonUrl("https://redd.it/1abc01")
        )
        assertEquals(
            "https://www.reddit.com/comments/1abc03.json?raw_json=1&limit=200",
            RedditUrls.jsonUrl("https://www.reddit.com/gallery/1abc03")
        )
        assertEquals(
            "https://www.reddit.com/r/recipes/comments/1abc01.json?raw_json=1&limit=200",
            RedditUrls.jsonUrl("https://www.reddit.com/r/recipes/comments/1abc01")
        )
    }

    @Test fun `a link to one comment keeps that comment's thread`() {
        assertEquals(
            "https://www.reddit.com/r/Old_Recipes/comments/1abc02/card/k3xyz.json?raw_json=1&limit=200",
            RedditUrls.jsonUrl("https://www.reddit.com/r/Old_Recipes/comments/1abc02/card/k3xyz/")
        )
    }

    @Test fun `the base is swappable for tests`() {
        assertEquals(
            "http://127.0.0.1:8080/comments/abc.json?raw_json=1&limit=200",
            RedditUrls.jsonUrl("https://redd.it/abc", "http://127.0.0.1:8080/")
        )
    }

    @Test fun `links that aren't one post have no listing`() {
        assertNull(RedditUrls.jsonUrl("https://www.reddit.com/r/recipes/"))
        assertNull(RedditUrls.jsonUrl("https://www.reddit.com/user/someone/"))
        assertNull(RedditUrls.jsonUrl("https://www.reddit.com/r/recipes/s/AbCd123"))
        assertNull(RedditUrls.jsonUrl("https://redd.it/"))
        assertNull(RedditUrls.jsonUrl("not a url"))
    }

    @Test fun `share links are marked for a redirect`() {
        assertTrue(RedditUrls.isShareLink("https://www.reddit.com/r/recipes/s/AbCd123"))
        assertTrue(RedditUrls.isShareLink("https://reddit.com/r/Old_Recipes/s/AbCd123/"))
        assertFalse(RedditUrls.isShareLink("https://www.reddit.com/r/recipes/comments/abc/x/"))
        assertFalse(RedditUrls.isShareLink("https://redd.it/abc"))
    }
}
