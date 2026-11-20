package com.example.recipeclipper.data.remote

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Why a Mealie file wasn't imported (#391): the cause, never the words (the screen picks those). */
enum class MealieError {
    /**
     * Not a zip with a Mealie recipe in it, and not a Mealie recipe's JSON: a recipe page's
     * schema.org data (read through the link), another app's file, or a file that isn't JSON.
     */
    NOT_A_MEALIE_FILE,

    /** A zip that is cut short or broken in its structure. Nothing was imported. */
    DAMAGED,

    /** More entries, or more unpacked text, than any real export holds ([MealieLimits]). */
    TOO_LARGE,

    /** The file couldn't be opened or copied. */
    READ_FAILED,

    /** The database refused the write; the import is one transaction, so nothing was written. */
    SAVE_FAILED
}

/**
 * How much of a file is believed (#391), the same numbers as the Paprika import's: a hostile file can
 * name a million entries or unpack a few kilobytes to gigabytes, so nothing is trusted, an entry's
 * declared size is checked and its real size is counted as it unpacks. Tests pass small ones.
 */
data class MealieLimits(
    val maxEntries: Int = 10_000,
    /** One recipe's unpacked JSON. */
    val maxEntryBytes: Long = 32L * 1024 * 1024,
    /** Every entry that is read, together (pictures included). */
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    /** The file itself, which the data layer copies before reading it. */
    val maxFileBytes: Long = 2L * 1024 * 1024 * 1024,
    /** One recipe's picture (#386): bigger is left out, the recipe kept. */
    val maxPhotoBytes: Int = 8 * 1024 * 1024
)

/** What a Mealie file held: its readable recipes, and how many entries weren't. */
data class MealieContents(
    val recipes: List<MealieRecipe>,
    /** JSON files that aren't recipes: shaped like one with no name or content, or not Mealie's at all. */
    val notRecipes: Int,
    /** JSON files that couldn't be unpacked or read. */
    val unreadable: Int,
    /** Pictures that came with a recipe but were left out: too big, not an image, or not stored (#386). The recipe stays. */
    val photosLeftOut: Int = 0
)

sealed interface MealieRead {
    data class Success(val contents: MealieContents) : MealieRead
    data class Failure(val error: MealieError) : MealieRead
}

/**
 * Reads a Mealie file (#391): the bulk export (a zip of `recipes/<slug>/<slug>.json`, no pictures),
 * the per-recipe download (a zip of one `.json` and one `.webp`), or a bare `.json`. `java.util.zip`
 * does the unpacking, in memory entry by entry: an entry's name is only ever compared, never used as
 * a path, and nothing is written to disk. Entries that aren't JSON or a picture (folders, macOS's
 * `__MACOSX/._…` copies, a readme) are ignored and not counted. One broken entry is counted as
 * unreadable and the rest still import; a broken zip imports nothing.
 *
 * **A picture belongs to the recipe whose JSON is in its folder** (the nearest one up the path:
 * `recipes/<slug>/images/original.webp` to `recipes/<slug>/<slug>.json`); a picture beside the only
 * JSON in the zip belongs to it wherever it sits. A picture that fits no recipe is ignored.
 */
object MealieArchive {

    private val ZIP_HEAD = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    private val PICTURE_EXTENSIONS = listOf(".webp", ".jpg", ".jpeg", ".png")

    private fun visible(name: String): Boolean {
        if (name.endsWith("/") || name.startsWith("__MACOSX/") || "/__MACOSX/" in name) return false
        return !name.substringAfterLast('/').startsWith(".")
    }

    /** True for an entry that may be a recipe: a file named `*.json`, not a hidden or macOS copy. */
    fun isRecipeEntry(name: String): Boolean = visible(name) && name.lowercase().endsWith(".json")

    /** True for an entry that may be a recipe's picture: a `.webp`, `.jpg`, `.jpeg` or `.png`, not hidden or a macOS copy. */
    fun isPictureEntry(name: String): Boolean = visible(name) && PICTURE_EXTENSIONS.any { name.lowercase().endsWith(it) }

    /**
     * Throws [IOException] only when [file] can't be read at all; a bad zip is a [MealieRead.Failure].
     *
     * [storePhoto] (#386) is handed each recipe's picture (within [MealieLimits.maxPhotoBytes]) as its
     * recipe is read, so no picture waits in memory for the rest of the file; it answers the stored
     * file's name, or null for a picture that isn't one (left out and counted, the recipe stays).
     * Called on this thread, once per recipe.
     */
    fun read(
        file: File,
        limits: MealieLimits = MealieLimits(),
        storePhoto: ((ByteArray) -> String?)? = null
    ): MealieRead {
        val head = ByteArray(4)
        val got = file.inputStream().use { it.read(head) }
        if (got == 4 && head.contentEquals(ZIP_HEAD)) return readZip(file, limits, storePhoto)
        return readBare(file, limits)
    }

    /** A bare `.json`: one recipe, or no Mealie file at all. */
    private fun readBare(file: File, limits: MealieLimits): MealieRead {
        if (file.length() > limits.maxEntryBytes) return MealieRead.Failure(MealieError.TOO_LARGE)
        val text = decode(file.readBytes()) ?: return MealieRead.Failure(MealieError.NOT_A_MEALIE_FILE)
        return when (val parsed = MealieRecipeParser.parse(text)) {
            is MealieEntry.Recipe -> MealieRead.Success(MealieContents(listOf(parsed.recipe), 0, 0))
            MealieEntry.NotARecipe -> MealieRead.Success(MealieContents(emptyList(), 1, 0))
            MealieEntry.NotMealie, MealieEntry.Unreadable -> MealieRead.Failure(MealieError.NOT_A_MEALIE_FILE)
        }
    }

    private fun readZip(file: File, limits: MealieLimits, storePhoto: ((ByteArray) -> String?)?): MealieRead {
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            // Starts like a zip but has no readable directory: cut short or broken.
            return MealieRead.Failure(MealieError.DAMAGED)
        }
        zip.use {
            if (zip.size() > limits.maxEntries) return MealieRead.Failure(MealieError.TOO_LARGE)
            val jsons = ArrayList<ZipEntry>()
            val pictures = ArrayList<ZipEntry>()
            val all = zip.entries()
            while (all.hasMoreElements()) {
                val entry = all.nextElement()
                if (entry.isDirectory) continue
                if (isRecipeEntry(entry.name)) jsons += entry else if (isPictureEntry(entry.name)) pictures += entry
            }
            if (jsons.isEmpty()) return MealieRead.Failure(MealieError.NOT_A_MEALIE_FILE)
            val pictureOf = assign(jsons.map { it.name }, pictures)

            val recipes = ArrayList<MealieRecipe>()
            var notRecipes = 0
            var unreadable = 0
            var shaped = 0
            var photosLeftOut = 0
            var total = 0L
            for (entry in jsons) {
                // A declared size is a claim: over the cap it is refused without unpacking anything.
                if (entry.size > limits.maxEntryBytes || entry.compressedSize > limits.maxEntryBytes) {
                    unreadable++
                    continue
                }
                val bytes = try {
                    zip.getInputStream(entry).use { it.readAtMost(limits.maxEntryBytes) }
                } catch (e: IOException) {
                    null
                }
                if (bytes == null) {
                    unreadable++
                    continue
                }
                total += bytes.size()
                if (total > limits.maxTotalBytes) return MealieRead.Failure(MealieError.TOO_LARGE)
                val text = decode(bytes.toByteArray())
                if (text == null) {
                    unreadable++
                    continue
                }
                when (val parsed = MealieRecipeParser.parse(text)) {
                    is MealieEntry.Recipe -> {
                        shaped++
                        var recipe = parsed.recipe
                        val picture = pictureOf[entry.name]
                        if (picture != null) {
                            val data = readPicture(zip, picture, limits)
                            total += data?.size ?: 0
                            if (total > limits.maxTotalBytes) return MealieRead.Failure(MealieError.TOO_LARGE)
                            val stored = data?.let { storePhoto?.invoke(it) }
                            if (stored == null) photosLeftOut++
                            recipe = recipe.copy(photoFile = stored)
                        }
                        recipes += recipe
                    }
                    MealieEntry.NotARecipe -> {
                        shaped++
                        notRecipes++
                    }
                    MealieEntry.NotMealie -> notRecipes++
                    MealieEntry.Unreadable -> unreadable++
                }
            }
            // JSON files and not one of them Mealie's (a schema.org file, another app's): not a Mealie export.
            if (shaped == 0 && unreadable == 0) return MealieRead.Failure(MealieError.NOT_A_MEALIE_FILE)
            return MealieRead.Success(MealieContents(recipes, notRecipes, unreadable, photosLeftOut))
        }
    }

    /**
     * Each recipe entry's picture, by name only: the nearest recipe JSON up the picture's path, and a
     * folder-less zip's only recipe. Of several pictures for one recipe, one named "original" wins,
     * else the first in the zip.
     */
    private fun assign(jsonNames: List<String>, pictures: List<ZipEntry>): Map<String, ZipEntry> {
        val dirs = jsonNames.associateWith { it.substringBeforeLast('/', "") }
        val result = HashMap<String, ZipEntry>()
        for (picture in pictures) {
            val parent = picture.name.substringBeforeLast('/', "")
            val owner = jsonNames
                .filter { dirs.getValue(it).isNotEmpty() && (parent == dirs.getValue(it) || parent.startsWith(dirs.getValue(it) + "/")) }
                .maxByOrNull { dirs.getValue(it).length }
                ?: jsonNames.singleOrNull()
                ?: continue
            val current = result[owner]
            if (current == null || (!isOriginal(current) && isOriginal(picture))) result[owner] = picture
        }
        return result
    }

    private fun isOriginal(entry: ZipEntry) = entry.name.substringAfterLast('/').lowercase().startsWith("original")

    /** A picture's bytes, or null if it is over the cap (declared or real) or can't be unpacked. */
    private fun readPicture(zip: ZipFile, entry: ZipEntry, limits: MealieLimits): ByteArray? {
        if (entry.size > limits.maxPhotoBytes || entry.compressedSize > limits.maxPhotoBytes) return null
        return try {
            zip.getInputStream(entry).use { it.readAtMost(limits.maxPhotoBytes.toLong()) }?.toByteArray()?.takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            null
        }
    }

    /** UTF-8 text, or null if the bytes aren't valid UTF-8. */
    private fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** All of the stream's bytes, or null if there are more than [max]. */
    private fun InputStream.readAtMost(max: Long): ByteArrayOutputStream? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) return out
            out.write(buffer, 0, n)
            if (out.size() > max) return null
        }
    }
}
