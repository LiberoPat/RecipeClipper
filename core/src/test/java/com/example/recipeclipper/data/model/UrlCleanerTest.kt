package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlCleanerTest {

    private val plain = "https://www.thekitchn.com/filipino-chicken-adobo-recipe-23652486"

    private fun clean(url: String) = UrlCleaner.clean(url)

    @Test fun `a link with nothing to remove is unchanged`() {
        assertEquals(plain, clean(plain))
    }

    @Test fun `utm tags are removed`() {
        assertEquals(plain, clean("$plain?utm_source=newsletter"))
        assertEquals(plain, clean("$plain?utm_source=ig&utm_medium=social&utm_campaign=spring"))
    }

    @Test fun `different tracking tags on the same page clean to the same link`() {
        val a = clean("$plain?utm_source=newsletter")
        val b = clean("$plain?utm_source=instagram&fbclid=abc123")
        assertEquals(a, b)
        assertEquals(plain, a)
    }

    @Test fun `well known click ids are removed`() {
        assertEquals(plain, clean("$plain?fbclid=1"))
        assertEquals(plain, clean("$plain?gclid=1&msclkid=2&igshid=3"))
        assertEquals(plain, clean("$plain?mc_cid=1&mc_eid=2"))
    }

    @Test fun `parameters that may choose the recipe are kept in their original order`() {
        assertEquals("https://x.com/r?id=5&slug=adobo", clean("https://x.com/r?id=5&utm_source=a&slug=adobo"))
        assertEquals("https://x.com/r?z=1&a=2", clean("https://x.com/r?z=1&a=2"))
    }

    @Test fun `only the tracking parameters are dropped from a mixed query`() {
        assertEquals("https://x.com/r?id=5", clean("https://x.com/r?fbclid=z&id=5"))
    }

    @Test fun `the fragment is removed`() {
        assertEquals(plain, clean("$plain#comments"))
        assertEquals(plain, clean("$plain?utm_source=a#recipe"))
    }

    @Test fun `scheme and host are lowercased but the path is not`() {
        assertEquals("https://example.com/Some/Path", clean("HTTPS://Example.COM/Some/Path"))
    }

    @Test fun `parameter names are matched case-insensitively`() {
        assertEquals(plain, clean("$plain?UTM_Source=a&FBCLID=b"))
    }

    @Test fun `an empty query does not leave a dangling question mark`() {
        assertEquals(plain, clean("$plain?"))
        assertEquals(plain, clean("$plain?&&"))
    }

    @Test fun `a lookalike parameter name is not treated as tracking`() {
        assertEquals("https://x.com/r?utmost=1&fbclidx=2", clean("https://x.com/r?utmost=1&fbclidx=2"))
    }

    @Test fun `a value containing an equals sign or a percent escape is kept whole`() {
        assertEquals("https://x.com/r?q=a%20b=c", clean("https://x.com/r?utm_source=z&q=a%20b=c"))
    }

    @Test fun `a link with a host and no path works`() {
        assertEquals("https://example.com", clean("https://EXAMPLE.com?utm_source=a"))
    }

    @Test fun `surrounding whitespace is trimmed and non links pass through`() {
        assertEquals(plain, clean("  $plain  "))
        assertEquals("not a link", clean("not a link"))
    }

    @Test fun `an http link is upgraded to https`() {
        assertEquals(plain, clean("http://www.thekitchn.com/filipino-chicken-adobo-recipe-23652486"))
    }

    @Test fun `an https link is left as https`() {
        assertEquals(plain, clean(plain))
    }

    @Test fun `an uppercase HTTP scheme is upgraded and lowercased`() {
        assertEquals(plain, clean("HTTP://www.thekitchn.com/filipino-chicken-adobo-recipe-23652486"))
    }

    @Test fun `a string with no scheme is left untouched`() {
        assertEquals("not a link", clean("not a link"))
    }
}
