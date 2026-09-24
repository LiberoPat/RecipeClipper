package com.example.recipeclipper.data.model

/**
 * The site a recipe came from, as the reading view credits it: "smittenkitchen.com" for
 * `https://www.smittenkitchen.com/2024/01/some-recipe/`. Pure: string in, string out.
 *
 * Only the host is kept: no scheme, user info, port, path, query or fragment. It is
 * lowercased, and one leading "www." is dropped because it tells a reader nothing. Every other
 * subdomain ("cooking.nytimes.com", "m.allrecipes.com") stays, since it can be the part that
 * names the site. Null when there is no recognisable host, so the screen shows no credit rather
 * than a wrong one.
 */
object SourceDomain {

    fun of(url: String): String? {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null

        val authority = trimmed.substring(schemeEnd + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val hostAndPort = authority.substringAfterLast('@')
        // An IPv6 literal carries colons of its own; its port, if any, follows the bracket.
        val host = if (hostAndPort.startsWith("[")) {
            hostAndPort.substringBefore(']') + if (hostAndPort.contains(']')) "]" else ""
        } else {
            hostAndPort.substringBefore(':')
        }
        val domain = host.lowercase().removePrefix("www.").removeSuffix(".")
        return domain.ifEmpty { null }
    }
}
