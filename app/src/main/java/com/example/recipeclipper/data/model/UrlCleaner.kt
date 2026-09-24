package com.example.recipeclipper.data.model

/**
 * Turns a link into the form the app saves it under, so the same recipe reached through
 * different tracking tags is one recipe, not several. Pure: string in, string out.
 *
 * Only things that cannot change which page you get are removed: well-known tracking
 * parameters, the #fragment (never sent to the server), and capital letters in the scheme
 * and host (which are case-insensitive). Every other parameter is kept, in its original
 * order and spelling, because some sites use them to say which recipe you mean.
 */
object UrlCleaner {

    private val TRACKING_PARAMETERS = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "yclid", "twclid", "ttclid",
        "igshid", "mc_cid", "mc_eid", "mkt_tok", "li_fat_id", "vero_id", "_ga", "_gl",
        "oly_enc_id", "oly_anon_id", "wickedid"
    )

    fun clean(url: String): String {
        val withoutFragment = url.trim().substringBefore('#')
        val schemeEnd = withoutFragment.indexOf("://")
        if (schemeEnd < 0) return withoutFragment // not a link we understand; leave it alone

        val queryStart = withoutFragment.indexOf('?')
        val beforeQuery = if (queryStart < 0) withoutFragment else withoutFragment.substring(0, queryStart)
        val query = if (queryStart < 0) "" else withoutFragment.substring(queryStart + 1)

        // Lowercase "https://Host.com" but not the path after it, which is case-sensitive.
        val pathStart = beforeQuery.indexOf('/', schemeEnd + 3)
        val origin = if (pathStart < 0) beforeQuery else beforeQuery.substring(0, pathStart)
        val path = if (pathStart < 0) "" else beforeQuery.substring(pathStart)

        // targetSdk 34 blocks cleartext by default; upgrading the scheme (rather than
        // re-enabling cleartext) also means one recipe is one DB row whichever scheme it
        // arrived under.
        val lowerOrigin = origin.lowercase()
        val upgradedOrigin = if (lowerOrigin.startsWith("http://")) "https://" + lowerOrigin.removePrefix("http://") else lowerOrigin

        val kept = query.split('&').filter { it.isNotEmpty() && !isTracking(it.substringBefore('=')) }
        return upgradedOrigin + path + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
    }

    private fun isTracking(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("utm_") || lower in TRACKING_PARAMETERS
    }
}
