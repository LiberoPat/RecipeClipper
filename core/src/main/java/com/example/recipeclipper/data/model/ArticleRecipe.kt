package com.example.recipeclipper.data.model

/**
 * A recipe written inside an ordinary article (#435), found on a page whose JSON-LD and
 * microdata hold none: for the cook to check in the editor, never saved unchecked. [name] is
 * the page's headline (the article's, which may not be the dish's), [image] its `og:image`,
 * [ingredients] the list's items as written, [steps] what follows it as written, [language]
 * the language whose words found the list, and [sourceUrl] the page's link.
 */
data class ArticleRecipe(
    val name: String,
    val image: String?,
    val ingredients: List<String>,
    val steps: List<String>,
    val language: String,
    val sourceUrl: String
)
