package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceDomainTest {

    private fun of(url: String) = SourceDomain.of(url)

    @Test fun `a leading www is dropped`() {
        assertEquals("smittenkitchen.com", of("https://www.smittenkitchen.com/2024/01/some-recipe/"))
    }

    @Test fun `a host without www is kept as it is`() {
        assertEquals("smittenkitchen.com", of("https://smittenkitchen.com/2024/01/some-recipe/"))
    }

    @Test fun `other subdomains are kept`() {
        assertEquals("cooking.nytimes.com", of("https://cooking.nytimes.com/recipes/1234"))
        assertEquals("m.allrecipes.com", of("https://m.allrecipes.com/recipe/1"))
        assertEquals("www2.example.com", of("https://www2.example.com/a"))
    }

    @Test fun `only one leading www is dropped, and never one in the middle`() {
        assertEquals("www.example.com", of("https://www.www.example.com/"))
        assertEquals("shop.www.example.com", of("https://shop.www.example.com/"))
    }

    @Test fun `the host is lowercased`() {
        assertEquals("seriouseats.com", of("https://WWW.SeriousEats.com/Recipe"))
    }

    @Test fun `path, query and fragment are dropped`() {
        assertEquals("example.com", of("https://example.com/a/b?c=d#e"))
        assertEquals("example.com", of("https://example.com?c=d"))
        assertEquals("example.com", of("https://example.com#e"))
        assertEquals("example.com", of("https://example.com"))
    }

    @Test fun `port and user info are dropped`() {
        assertEquals("example.com", of("https://example.com:8443/recipe"))
        assertEquals("example.com", of("https://user:pass@www.example.com:8443/recipe"))
    }

    @Test fun `a trailing root dot is dropped`() {
        assertEquals("example.com", of("https://www.example.com./recipe"))
    }

    @Test fun `an ipv6 literal keeps its brackets and loses its port`() {
        assertEquals("[::1]", of("http://[::1]:8080/recipe"))
    }

    @Test fun `surrounding whitespace is ignored`() {
        assertEquals("example.com", of("  https://www.example.com/recipe \n"))
    }

    @Test fun `http links work too`() {
        assertEquals("example.com", of("http://www.example.com/recipe"))
    }

    @Test fun `something that is not a link has no domain`() {
        assertNull(of(""))
        assertNull(of("smittenkitchen.com"))
        assertNull(of("not a link"))
        assertNull(of("://example.com"))
    }

    @Test fun `a link with no host has no domain`() {
        assertNull(of("https://"))
        assertNull(of("https:///recipe"))
        assertNull(of("https://www./recipe"))
    }
}
