package com.example.recipeclipper.data.remote

/**
 * Recognises Cloudflare's bot check (#220): the "Just a moment..." page a site behind Cloudflare
 * shows while it decides whether the visitor is a browser, and the "Verify you are human" box it
 * shows when it wants a person. Pure: text in, a yes or no out. iOS has the same
 * `CloudflareChallenge`, and both read the pages in `shared/fixtures/cloudflare`.
 *
 * Only Cloudflare's own challenge markers count, never a guess from the wording:
 * - `window._cf_chl_opt`, the challenge page's settings;
 * - a `__cf_chl_` token (`__cf_chl_tk`, `__cf_chl_rt_tk`, `__cf_chl_f_tk`, the older
 *   `__cf_chl_jschl_tk__`) in the page's own links and form;
 * - the challenge's `orchestrate` script under `/cdn-cgi/challenge-platform/`;
 * - the title "Just a moment..." on a page that names Cloudflare.
 *
 * Not a challenge: the "jsd" script Cloudflare's bot detection adds to **ordinary** pages (also
 * under `/cdn-cgi/challenge-platform/`, but `scripts/jsd/`), a Turnstile widget on a comment
 * form, or Cloudflare's firewall block ("Sorry, you have been blocked"), which no one can pass
 * and so stays an ordinary block.
 */
object CloudflareChallenge {

    private val ORCHESTRATE = Regex("""/cdn-cgi/challenge-platform/(?:[\w.-]+/)*orchestrate/""")
    private val TITLE = Regex("""<title[^>]*>\s*Just a moment(?:\.\.\.|…)\s*</title>""", RegexOption.IGNORE_CASE)

    /** Whether [html] is Cloudflare's challenge page rather than the page asked for. */
    fun isChallengePage(html: String): Boolean =
        html.contains("_cf_chl_opt") ||
            html.contains("__cf_chl_") ||
            ORCHESTRATE.containsMatchIn(html) ||
            (TITLE.containsMatchIn(html) && html.contains("cloudflare", ignoreCase = true))

    /**
     * Whether a refused plain fetch was Cloudflare's challenge: its `cf-mitigated: challenge`
     * header ([cfMitigated]), or a challenge page as the body of a 403, 429 or 503.
     */
    fun isChallengeResponse(status: Int, cfMitigated: String?, body: String?): Boolean =
        cfMitigated?.trim().equals("challenge", ignoreCase = true) ||
            (status in CHALLENGE_STATUSES && body != null && isChallengePage(body))

    private val CHALLENGE_STATUSES = setOf(403, 429, 503)
}
