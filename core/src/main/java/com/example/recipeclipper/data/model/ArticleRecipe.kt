package com.example.recipeclipper.data.model

/**
 * A recipe written inside an ordinary article (#435), found on a page whose JSON-LD and
 * microdata hold none: for the cook to check in the editor, never saved unchecked. [name] is the
 * nearest heading above its list, else the page's [headline] (neither may be the dish's name),
 * [image] the page's `og:image`, [ingredients] the list's items as written, [steps] what follows
 * it as written, [language] the language whose words found the list, and [sourceUrl] the link it
 * is saved under ([link]).
 */
data class ArticleRecipe(
    val name: String,
    val image: String?,
    val ingredients: List<String>,
    val steps: List<String>,
    val language: String,
    val sourceUrl: String,
    val headline: String = name
) {
    companion object {
        /** The query parameter that tells a page's second and later recipes apart (#435). */
        const val PARAMETER = "pith_recipe"

        /**
         * The link the page's recipe at [index] (from 0, in page order) is saved under: the page's
         * cleaned link for the first, so a page with one recipe is saved as any clip is; for each
         * later one the same link with `pith_recipe=<n>` (from 2) added, which `UrlCleaner` keeps
         * (it removes only tracking), so every recipe of the page has its own unique `sourceUrl`.
         */
        fun link(pageUrl: String, index: Int): String {
            if (index <= 0) return pageUrl
            val separator = if ('?' in pageUrl) '&' else '?'
            return "$pageUrl$separator$PARAMETER=${index + 1}"
        }
    }
}
