package com.example.recipeclipper.data.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Why a Paprika file wasn't imported (#382): the cause, never the words (the screen picks those). */
enum class PaprikaError {
    /** Not a zip, or a zip with no `.paprikarecipe` entry in it. */
    NOT_A_PAPRIKA_FILE,

    /** A zip that is cut short or broken in its structure. Nothing was imported. */
    DAMAGED,

    /** More entries, or more unpacked text, than any real export holds ([PaprikaLimits]). */
    TOO_LARGE,

    /** The file couldn't be opened or copied. */
    READ_FAILED,

    /** The database refused the write; the import is one transaction, so nothing was written. */
    SAVE_FAILED
}

/**
 * How much of a file is believed (#382). A hostile file can name a million entries or unpack a
 * few kilobytes to gigabytes, so nothing is trusted: an entry's declared size is checked, and
 * its real size is counted as it unpacks. The defaults are far beyond a real export (a library of
 * a thousand recipes with photos is a few hundred MB unpacked). Tests pass small ones.
 */
data class PaprikaLimits(
    val maxEntries: Int = 10_000,
    /** One recipe's unpacked JSON, photo included. */
    val maxEntryBytes: Long = 32L * 1024 * 1024,
    /** Every recipe's unpacked JSON together. */
    val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    /** The file itself, which the data layer copies before reading it. */
    val maxFileBytes: Long = 2L * 1024 * 1024 * 1024
)

/** What a Paprika file held: its readable recipes, and how many entries weren't. */
data class PaprikaContents(
    val recipes: List<PaprikaRecipe>,
    /** Entries that read as JSON objects but are no recipe (no name, or no ingredients and steps). */
    val notRecipes: Int,
    /** Entries that couldn't be unpacked or read as JSON. */
    val unreadable: Int
)

sealed interface PaprikaRead {
    data class Success(val contents: PaprikaContents) : PaprikaRead
    data class Failure(val error: PaprikaError) : PaprikaRead
}

/**
 * Reads a `.paprikarecipes` file (#382): a zip with one `<name>.paprikarecipe` entry per recipe,
 * each a gzip-compressed JSON object ([PaprikaRecipeParser]). `java.util.zip` does the
 * unpacking, in memory entry by entry: an entry's name is only ever compared, never used as a
 * path, and nothing is written to disk. Entries that aren't recipes (folders, macOS's
 * `__MACOSX/._…` copies, a readme) are ignored and not counted. One broken entry is counted as
 * unreadable and the rest still import; a broken zip imports nothing.
 */
object PaprikaArchive {

    private const val EXTENSION = ".paprikarecipe"
    private val ZIP_HEAD = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)

    /** True for an entry that may be a recipe: a file named `*.paprikarecipe`, not a hidden or macOS copy. */
    fun isRecipeEntry(name: String): Boolean {
        if (name.endsWith("/") || name.startsWith("__MACOSX/") || "/__MACOSX/" in name) return false
        val file = name.substringAfterLast('/')
        return !file.startsWith(".") && file.lowercase().endsWith(EXTENSION)
    }

    /** Throws [IOException] only when [file] can't be read at all; a bad zip is a [PaprikaRead.Failure]. */
    fun read(file: File, limits: PaprikaLimits = PaprikaLimits()): PaprikaRead {
        val head = ByteArray(4)
        val got = file.inputStream().use { it.read(head) }
        if (got < 4 || !head.contentEquals(ZIP_HEAD)) return PaprikaRead.Failure(PaprikaError.NOT_A_PAPRIKA_FILE)
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            // Starts like a zip but has no readable directory: cut short or broken.
            return PaprikaRead.Failure(PaprikaError.DAMAGED)
        }
        zip.use {
            if (zip.size() > limits.maxEntries) return PaprikaRead.Failure(PaprikaError.TOO_LARGE)
            val recipes = ArrayList<PaprikaRecipe>()
            var notRecipes = 0
            var unreadable = 0
            var candidates = 0
            var total = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !isRecipeEntry(entry.name)) continue
                candidates++
                // A declared size is a claim: over the cap it is refused without unpacking anything.
                if (entry.size > limits.maxEntryBytes || entry.compressedSize > limits.maxEntryBytes) {
                    unreadable++
                    continue
                }
                val text = try {
                    val gzip = zip.getInputStream(entry).use { it.readAtMost(limits.maxEntryBytes) }
                    gzip?.let { GZIPInputStream(ByteArrayInputStream(it.toByteArray())).use { s -> s.readAtMost(limits.maxEntryBytes) } }
                } catch (e: IOException) {
                    null
                }
                if (text == null) {
                    unreadable++
                    continue
                }
                total += text.size()
                if (total > limits.maxTotalBytes) return PaprikaRead.Failure(PaprikaError.TOO_LARGE)
                val json = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(text.toByteArray()))
                        .toString()
                } catch (e: CharacterCodingException) {
                    unreadable++
                    continue
                }
                when (val parsed = PaprikaRecipeParser.parse(json)) {
                    is PaprikaEntry.Recipe -> recipes += parsed.recipe
                    PaprikaEntry.NotARecipe -> notRecipes++
                    PaprikaEntry.Unreadable -> unreadable++
                }
            }
            if (candidates == 0) return PaprikaRead.Failure(PaprikaError.NOT_A_PAPRIKA_FILE)
            return PaprikaRead.Success(PaprikaContents(recipes, notRecipes, unreadable))
        }
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
