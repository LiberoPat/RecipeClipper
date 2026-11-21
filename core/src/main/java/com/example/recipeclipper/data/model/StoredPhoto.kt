package com.example.recipeclipper.data.model

/**
 * A recipe's own picture stored on the phone (#386), as the screens name it to the image loader:
 * `photo://<file name>`, the name of a file in the photo store, optionally followed by `#` and the
 * recipe's web picture link, which the loader shows instead when the file isn't there (a phone
 * restored from Android's backup, a cleared store): never a blank where the link would have shown.
 * A web picture alone is still its plain link. One string, so every place that shows a recipe's
 * picture passes the same thing whichever it is, and the platform's image loader (Coil's mapper;
 * iOS's `ImageLoader`) turns it into the file or the link. The list queries build the same string in
 * SQL (`RecipeImageSql`). Pure; iOS's `StoredPhoto` is the same.
 */
object StoredPhoto {
    const val SCHEME = "photo://"
    private const val FALLBACK = '#'

    /** The model for [fileName], falling back to [fallback] (a web link; its fragment is dropped, it never matters to a picture). */
    fun model(fileName: String, fallback: String? = null): String {
        val link = fallback?.substringBefore(FALLBACK)?.takeIf { it.isNotBlank() }
        return SCHEME + fileName + (link?.let { "$FALLBACK$it" } ?: "")
    }

    /** The file name [model] names, or null if it is a web link or anything else. A name is a plain
     *  name: one with a path in it names no file. */
    fun fileName(model: String): String? =
        model.takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)?.substringBefore(FALLBACK)
            ?.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && it != ".." && it != "." }

    /** The web link [model] falls back to when its file is missing, or null for none. */
    fun fallback(model: String): String? =
        if (fileName(model) == null) null else model.substringAfter(FALLBACK, "").takeIf { it.isNotEmpty() }
}
