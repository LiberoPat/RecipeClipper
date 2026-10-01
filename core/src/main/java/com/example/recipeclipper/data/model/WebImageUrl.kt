package com.example.recipeclipper.data.model

/**
 * A photo's address taken from a web page (#235): the photo tapped in "Clip it yourself", a
 * recipe's JSON-LD or microdata `image`, `og:image`, a Reddit post's picture. Only a web image
 * is kept: `https` with a host, `http` upgraded as [UrlCleaner] upgrades a link. Anything else
 * (`file:`, `content:`, `data:`, `javascript:`, a relative or blank address) is no image, so a
 * page can never make Coil (iOS: `ImageLoader`) load something from the device. Pure: string
 * in, string or null out; iOS's `WebImageUrl` is the same rule.
 */
object WebImageUrl {

    fun of(address: String?): String? {
        val trimmed = address?.trim().orEmpty()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != "https" && scheme != "http") return null
        val rest = trimmed.substring(schemeEnd + 3)
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@').substringBefore(':')
        if (host.isEmpty()) return null
        return "https://$rest"
    }
}
