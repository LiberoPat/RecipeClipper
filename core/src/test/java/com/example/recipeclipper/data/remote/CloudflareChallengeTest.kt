package com.example.recipeclipper.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #220: Cloudflare's challenge pages (`shared/fixtures/cloudflare`, read by iOS too). */
class CloudflareChallengeTest {

    private fun page(name: String) =
        javaClass.getResourceAsStream("/cloudflare/$name.html")!!.bufferedReader().use { it.readText() }

    @Test
    fun theManagedChallengeAsFetchedIsAChallenge() {
        assertTrue(CloudflareChallenge.isChallengePage(page("managed-challenge")))
    }

    @Test
    fun theVerifyYouAreHumanBoxIsAChallenge() {
        assertTrue(CloudflareChallenge.isChallengePage(page("interactive-turnstile")))
    }

    @Test
    fun theOlderJavaScriptChallengeIsAChallenge() {
        assertTrue(CloudflareChallenge.isChallengePage(page("legacy-js-challenge")))
    }

    @Test
    fun anOrdinaryPageWithCloudflaresDetectionScriptAndATurnstileFormIsNot() {
        assertFalse(CloudflareChallenge.isChallengePage(page("recipe-with-jsd")))
    }

    @Test
    fun theFirewallBlockIsNotAChallenge() {
        assertFalse(CloudflareChallenge.isChallengePage(page("blocked-1020")))
    }

    @Test
    fun aJustAMomentTitleNeedsCloudflareOnThePage() {
        assertFalse(CloudflareChallenge.isChallengePage("<html><head><title>Just a moment...</title></head><body>Loading</body></html>"))
        assertTrue(CloudflareChallenge.isChallengePage("<html><head><title>Just a moment…</title></head><body>by Cloudflare</body></html>"))
    }

    @Test
    fun theCfMitigatedHeaderMarksAChallengeWhateverTheBody() {
        assertTrue(CloudflareChallenge.isChallengeResponse(403, "challenge", null))
        assertTrue(CloudflareChallenge.isChallengeResponse(403, " Challenge ", ""))
        assertFalse(CloudflareChallenge.isChallengeResponse(403, "block", null))
    }

    @Test
    fun aRefusalWhoseBodyIsTheChallengePageIsAChallenge() {
        assertTrue(CloudflareChallenge.isChallengeResponse(403, null, page("managed-challenge")))
        assertTrue(CloudflareChallenge.isChallengeResponse(503, null, page("legacy-js-challenge")))
    }

    @Test
    fun otherRefusalsAreNot() {
        assertFalse(CloudflareChallenge.isChallengeResponse(403, null, page("blocked-1020")))
        assertFalse(CloudflareChallenge.isChallengeResponse(403, null, "Forbidden"))
        assertFalse(CloudflareChallenge.isChallengeResponse(404, null, page("managed-challenge")))
        assertFalse(CloudflareChallenge.isChallengeResponse(403, null, null))
    }
}
