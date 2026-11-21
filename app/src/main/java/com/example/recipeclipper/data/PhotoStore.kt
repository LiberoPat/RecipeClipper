package com.example.recipeclipper.data

/**
 * Where "I made this" photos (#116) live: JPEG files in the app's own storage, named by the
 * store and never by the user. The only part of the feature that touches files and Android's
 * image APIs, behind an interface so repositories stay free of `Context` and tests use a fake.
 * URIs travel as strings, as in [BackupFiles].
 */
interface PhotoStore {

    /**
     * Reads the picture at [source] (a `content:` or `file:` URI, from the Photo Picker or the
     * camera), turns it upright, downscales it to [MAX_EDGE] on its long edge and writes it as
     * a JPEG. Its new file name, or null if it couldn't be read or written.
     */
    suspend fun importPicture(source: String): String?

    /**
     * Stores a picture whose bytes are in hand (#386: a Paprika file's `photo_data`), as
     * [importPicture] stores one: checked to decode as an image, turned upright, downscaled to
     * [MAX_EDGE], written as a JPEG under a fresh random name (never a name from the file). Null if
     * it is longer than [MAX_RECIPE_PHOTO_BYTES], isn't an image, or couldn't be written: the
     * caller leaves that photo out and keeps the recipe. Blocking: call it off the main thread.
     */
    fun importBytes(bytes: ByteArray): String?

    /** Copies a file that is already a stored photo (one from a backup) in; its new name, or null. */
    suspend fun adopt(path: String): String?

    /** The absolute path of the stored file [name]. */
    fun path(name: String): String

    /** Whether the file [name] is here: a phone restored from Android's backup has the rows only. */
    fun exists(name: String): Boolean

    /** Every stored file, with when it was last written (epoch millis). */
    suspend fun files(): Map<String, Long>

    suspend fun delete(names: Collection<String>)

    companion object {
        /** The long edge of a stored photo: sharp on any phone screen, a few hundred KB. */
        const val MAX_EDGE = 2048

        const val JPEG_QUALITY = 85

        /** The most a recipe's picture may be before it is decoded (#386): far more than a phone photo
         *  downscaled for a file, so a hostile one can't make the app decode a huge image. */
        const val MAX_RECIPE_PHOTO_BYTES = 8 * 1024 * 1024

        /** The most pixels a picture may declare (#386): about a 100-megapixel photo. */
        const val MAX_PIXELS = 100_000_000L

        /** A file this young may belong to an add still being written: the sweep leaves it. */
        const val SWEEP_GRACE_MILLIS = 10 * 60 * 1000L
    }
}

/** No photo storage: what a repository sees in tests that don't care about photos. */
object NoPhotoStore : PhotoStore {
    override suspend fun importPicture(source: String): String? = null
    override fun importBytes(bytes: ByteArray): String? = null
    override suspend fun adopt(path: String): String? = null
    override fun path(name: String): String = name
    override fun exists(name: String): Boolean = false
    override suspend fun files(): Map<String, Long> = emptyMap()
    override suspend fun delete(names: Collection<String>) = Unit
}
