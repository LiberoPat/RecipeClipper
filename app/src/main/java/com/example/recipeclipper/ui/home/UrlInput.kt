package com.example.recipeclipper.ui.home

/** Turns what was typed or pasted into a link to fetch, or null if it isn't one. Pure. */
object UrlInput {

    fun normalize(text: String): String? {
        val token = text.trim().split(Regex("""\s+""")).firstOrNull().orEmpty()
        if (token.isEmpty()) return null
        return when {
            token.startsWith("http://", ignoreCase = true) ||
                    token.startsWith("https://", ignoreCase = true) -> token.takeIf { it.length > 8 }
            // "seriouseats.com/recipe": a host and path with the scheme left off.
            '.' in token && !token.startsWith(".") && !token.endsWith(".") -> "https://$token"
            else -> null
        }
    }
}
