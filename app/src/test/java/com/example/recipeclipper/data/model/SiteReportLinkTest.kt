package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class SiteReportLinkTest {

    private fun url(link: String) = SiteReportLink.issueUrl(link, "Android 14 (API 34)", "1.0 (1)")

    /** The query parameters, decoded, the way GitHub reads them. */
    private fun params(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate {
            val (name, value) = it.split('=', limit = 2)
            name to URLDecoder.decode(value, "UTF-8")
        }

    @Test fun `the whole link, exactly`() {
        assertEquals(
            "https://github.com/LiberoPat/RecipeClipper/issues/new" +
                "?title=Site%20not%20supported%3A%20example.com" +
                "&body=Recipe%20Clipper%20found%20no%20recipe%20on%20this%20page.%0A%0A" +
                "Link%3A%20https%3A%2F%2Fexample.com%2Frecipe%3Fid%3D1%26x%3D2%0A" +
                "Platform%3A%20Android%2014%20%28API%2034%29%0A" +
                "App%20version%3A%201.0%20%281%29" +
                "&labels=site-report",
            url("https://example.com/recipe?id=1&x=2")
        )
    }

    @Test fun `title, body and label decode to what the issue shows`() {
        val p = params(url("https://www.Smitten-Kitchen.com/2024/01/soup/?print=1"))
        assertEquals("Site not supported: smitten-kitchen.com", p["title"])
        assertEquals(
            "Recipe Clipper found no recipe on this page.\n\n" +
                "Link: https://www.smitten-kitchen.com/2024/01/soup/?print=1\n" +
                "Platform: Android 14 (API 34)\n" +
                "App version: 1.0 (1)",
            p["body"]
        )
        assertEquals("site-report", p["labels"])
        assertEquals(setOf("title", "body", "labels"), p.keys)
    }

    @Test fun `ampersands, equals signs and hashes in the link stay inside the body`() {
        val link = "https://example.com/r?a=1&b=2&title=x"
        val raw = url(link)
        assertEquals(3, URI(raw).rawQuery.split('&').size)
        assertTrue(params(raw)["body"]!!.contains("Link: $link\n"))
        assertFalse(raw.substringAfter('?').contains('#'))
    }

    @Test fun `tracking tags and the fragment never reach a public issue`() {
        val body = params(url("https://example.com/r?utm_source=x&id=7&fbclid=abc#step-2"))["body"]!!
        assertTrue(body.contains("Link: https://example.com/r?id=7\n"))
        assertFalse(body.contains("utm_"))
        assertFalse(body.contains("fbclid"))
        assertFalse(body.contains("step-2"))
    }

    @Test fun `spaces are percent-encoded, never plus`() {
        val raw = url("https://example.com/r")
        assertFalse(raw.contains('+'))
        assertFalse(raw.contains(' '))
    }

    @Test fun `non-ASCII is encoded as UTF-8 bytes`() {
        assertEquals("caf%C3%A9%20%E2%80%94%20%F0%9F%8D%B2", SiteReportLink.percentEncode("café — 🍲"))
        assertEquals("AZaz09-._~", SiteReportLink.percentEncode("AZaz09-._~"))
        assertEquals("%21%2A%27%28%29%3B%3A%40%26%3D%2B%24%2C%2F%3F%23%5B%5D%25", SiteReportLink.percentEncode("!*'();:@&=+\$,/?#[]%"))
    }

    @Test fun `the title names the site`() {
        assertEquals("cooking.nytimes.com", SiteReportLink.domainOf("https://cooking.nytimes.com/recipes/1"))
        assertEquals("example.com", SiteReportLink.domainOf("https://user@www.example.com:8443/x"))
        assertEquals("example.com", SiteReportLink.domainOf("https://example.com"))
        assertEquals("example.com", SiteReportLink.domainOf("https://example.com?x=1"))
        assertNull(SiteReportLink.domainOf("not a link"))
        assertNull(SiteReportLink.domainOf("https:///path"))
    }

    @Test fun `a link with no host is named in full rather than guessed`() {
        assertEquals("Site not supported: not a link", params(url("not a link"))["title"])
    }
}
