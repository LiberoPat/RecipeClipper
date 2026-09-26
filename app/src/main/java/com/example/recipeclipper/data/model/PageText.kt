package com.example.recipeclipper.data.model

/**
 * A page's readable text, for a page with no recipe data (#103): what the on-device model may
 * pick a recipe from. Built from the HTML by `PageTextReader` (the iOS app's `PageTextReader`),
 * one line per block (paragraph, list item, heading, table row), each as Jsoup's `text()` gives
 * it, with scripts, styles, navigation and footers left out.
 */
data class PageText(
    /** The page's first `<h1>`, else its `og:title`, else its `<title>`; null if none. */
    val title: String?,
    val lines: List<String>,
    /** `<html lang>`, as declared ("en-US"), or null. */
    val language: String? = null,
    /** `og:image` as an absolute link, or null. */
    val image: String? = null
)

/**
 * What the model picked out of a page's text, field by field, before [PageRecipeCheck] keeps
 * only what is really on the page. Every string is meant to be copied from the page as written.
 */
data class PageSelection(
    val name: String?,
    val ingredients: List<String>,
    val steps: List<String>,
    val yield: String? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null
)
