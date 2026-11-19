package com.example.recipeclipper.data.model

/**
 * A recipe's own picture stored on the phone (#386), as the screens name it to the image loader:
 * `photo://<file name>`, the name of a file in the photo store. A web picture is still its plain
 * link. One string, so every place that shows a recipe's picture passes the same thing whichever
 * it is, and the platform's image loader (Coil's mapper; iOS's `ImageLoader`) turns it into the
 * file. Pure; iOS's `StoredPhoto` is the same.
 */
object StoredPhoto {
    const val SCHEME = "photo://"

    fun model(fileName: String): String = SCHEME + fileName

    /** The file name [model] names, or null if it is a web link or anything else. A name is a plain
     *  name: one with a path in it names no file. */
    fun fileName(model: String): String? =
        model.takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)
            ?.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && it != ".." && it != "." }
}
