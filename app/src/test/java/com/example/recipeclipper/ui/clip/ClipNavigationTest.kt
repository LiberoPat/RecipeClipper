package com.example.recipeclipper.ui.clip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iOS suite (`ClipNavigationTests.swift`) has the same cases and expectations. */
class ClipNavigationTest {

    private val post = "https://www.reddit.com/r/recipes/comments/1abc01/apple_pie/"

    private fun loads(
        target: String,
        current: String = post,
        isRedirect: Boolean = false,
        tapped: Boolean = true,
        withinSite: Boolean = false
    ) = ClipNavigation.loads(target, current, isRedirect, tapped, withinSite)

    @Test fun `reddit's check sending the page back to itself with its token loads`() {
        // Its script submits a hidden GET form to the post's own path once it has run (#213).
        assertTrue(loads("$post?solution=ab12ab12&js_challenge=1&jsc_token=f00d&jsc_orig_r=", tapped = false))
        // After a share link's redirect, the post's address carries the share query already.
        assertTrue(
            loads(
                "$post?share_id=x1&utm_source=share&solution=ab&js_challenge=1&jsc_token=f00d",
                current = "$post?share_id=x1&utm_source=share",
                tapped = false
            )
        )
    }

    @Test fun `a tapped link to the same path with another query does not load`() {
        assertFalse(loads("$post?sort=new", tapped = true))
    }

    @Test fun `a page script moving to another path or site does not load`() {
        assertFalse(loads("https://www.reddit.com/r/recipes/", tapped = false))
        assertFalse(loads("https://ads.example/landing", tapped = false))
    }

    @Test fun `an app's own link never loads, even as a redirect or while waiting on a check`() {
        listOf(
            "intent://r/recipes/comments/1abc01#Intent;scheme=reddit;package=com.reddit.frontpage;end",
            "reddit://reddit/r/recipes/comments/1abc01",
            "market://details?id=com.reddit.frontpage",
            "javascript:void(0)",
        ).forEach {
            assertFalse(it, loads(it))
            assertFalse(it, loads(it, isRedirect = true))
            assertFalse(it, loads(it, tapped = false, withinSite = true))
        }
    }

    @Test fun `redirects and fragment jumps load, links to other pages do not`() {
        assertTrue(loads("https://www.reddit.com/r/recipes/comments/1abc01/apple_pie/", current = "https://www.reddit.com/r/recipes/s/AbCd123", isRedirect = true))
        assertTrue(loads("$post#comments"))
        assertFalse(loads("https://www.reddit.com/r/recipes/comments/2def02/other/"))
        assertFalse(loads("https://hearthandcrumb.example/other", current = "https://hearthandcrumb.example/cookies"))
    }

    @Test fun `waiting on Cloudflare's check, anything on the page's site loads`() {
        val page = "https://hearthandcrumb.example/cookies"
        assertTrue(loads("https://hearthandcrumb.example/cookies?__cf_chl_tk=abc", current = page, withinSite = true))
        assertTrue(loads("https://hearthandcrumb.example/elsewhere", current = page, withinSite = true))
        assertFalse(loads("https://other.example/", current = page, withinSite = true))
    }
}
