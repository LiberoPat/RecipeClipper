package com.example.recipeclipper.data.model

/**
 * "Report this site": a prefilled new-issue link on the project's GitHub, offered only when a
 * page gave [ParseError.NoRecipeFound]. The app has no analytics, so this is how the owner
 * learns which sites fail. Nothing is sent: the link only opens a draft in the browser, and
 * the user decides whether to submit it. Pure: strings in, string out.
 *
 * The body is English by design, like [RecipeShareText]: it is read by the maintainer, not
 * shown as UI. The link is run through [UrlCleaner] first, so tracking tags never end up in
 * a public issue. The title names the site through [SourceDomain], the same helper the reading
 * view uses for its source credit.
 */
object SiteReportLink {

    const val NEW_ISSUE_URL = "https://github.com/LiberoPat/RecipeClipper/issues/new"
    const val LABEL = "site-report"

    /**
     * [platform] is e.g. "Android 14 (API 34)" and [appVersion] e.g. "1.0 (1)"; both come from
     * the platform through `AppInfo`, so this stays testable on the JVM.
     */
    fun issueUrl(link: String, platform: String, appVersion: String): String {
        val cleaned = UrlCleaner.clean(link)
        val title = "Site not supported: ${SourceDomain.of(cleaned) ?: cleaned}"
        val body = listOf(
            "Recipe Clipper found no recipe on this page.",
            "",
            "Link: $cleaned",
            "Platform: $platform",
            "App version: $appVersion"
        ).joinToString("\n")
        return NEW_ISSUE_URL +
            "?title=" + percentEncode(title) +
            "&body=" + percentEncode(body) +
            "&labels=" + percentEncode(LABEL)
    }

    /**
     * RFC 3986 percent-encoding of UTF-8 bytes: everything but the unreserved characters
     * (A–Z, a–z, 0–9, "-", ".", "_", "~") is escaped, so a space is "%20" (never "+"), and
     * "&", "=", "#" and "?" in the link can't break out of their query parameter.
     */
    internal fun percentEncode(value: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                out.append(c)
            } else {
                out.append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }
        return out.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
