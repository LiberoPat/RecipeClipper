package com.example.recipeclipper.ui

import com.example.recipeclipper.ui.common.Elapsed
import com.example.recipeclipper.ui.common.TimeAgo
import com.example.recipeclipper.ui.home.UrlInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeAgoAndUrlInputTest {

    private val now = 1_000_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    // The boundaries are what matter: the wording now lives in strings.xml and is applied
    // by Elapsed.text(), which needs resources and so is not testable here.
    @Test fun `recent times map to the right bucket`() {
        assertEquals(Elapsed.JustNow, TimeAgo.since(now - 20_000, now))
        assertEquals(Elapsed.Minutes(5), TimeAgo.since(now - 5 * minute, now))
        assertEquals(Elapsed.Minutes(59), TimeAgo.since(now - 59 * minute - 30_000, now))
        assertEquals(Elapsed.Hours(3), TimeAgo.since(now - 3 * hour, now))
        assertEquals(Elapsed.Yesterday, TimeAgo.since(now - 30 * hour, now))
        assertEquals(Elapsed.Days(4), TimeAgo.since(now - 4 * day, now))
    }

    @Test fun `each boundary falls on the later bucket`() {
        assertEquals(Elapsed.Minutes(1), TimeAgo.since(now - minute, now))
        assertEquals(Elapsed.Hours(1), TimeAgo.since(now - hour, now))
        assertEquals(Elapsed.Yesterday, TimeAgo.since(now - day, now))
        assertEquals(Elapsed.Days(2), TimeAgo.since(now - 2 * day, now))
    }

    @Test fun `a time in the future is just now`() {
        assertEquals(Elapsed.JustNow, TimeAgo.since(now + hour, now))
    }

    @Test fun `a week or more is a date, carrying the original instant`() {
        val then = now - 20 * day
        assertEquals(Elapsed.OnDate(then), TimeAgo.since(then, now))
        // Exactly seven days is already a date, not a day count.
        assertEquals(Elapsed.OnDate(now - 7 * day), TimeAgo.since(now - 7 * day, now))
    }

    @Test fun `full links pass through`() {
        assertEquals("https://example.com/r", UrlInput.normalize("https://example.com/r"))
        assertEquals("http://example.com/r", UrlInput.normalize("  http://example.com/r  "))
    }

    @Test fun `only the first word of pasted text is used`() {
        assertEquals("https://example.com/r", UrlInput.normalize("https://example.com/r check this out"))
    }

    @Test fun `a missing scheme is added`() {
        assertEquals("https://seriouseats.com/recipe", UrlInput.normalize("seriouseats.com/recipe"))
    }

    @Test fun `things that are not links are rejected`() {
        assertNull(UrlInput.normalize(""))
        assertNull(UrlInput.normalize("   "))
        assertNull(UrlInput.normalize("chicken adobo"))
        assertNull(UrlInput.normalize("https://"))
        assertNull(UrlInput.normalize("."))
    }
}
