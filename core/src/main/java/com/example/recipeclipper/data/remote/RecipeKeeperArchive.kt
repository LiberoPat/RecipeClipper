package com.example.recipeclipper.data.remote

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Reads a Recipe Keeper export (#390; iOS's twin is `RecipeKeeperZipReader`): a zip with
 * `recipes.html` (at the root, or inside one wrapper folder) and `images/<recipeId>_<N>.jpg`.
 * `java.util.zip` does the unpacking in memory, entry by entry. An entry's name is only ever
 * compared, never used as a path, and nothing is written to disk. The caps are [PaprikaLimits],
 * the same as the Paprika file's: the page and each picture have a declared size that is a claim,
 * and the real size is counted as it unpacks; the entry count and the unpacked total are refused
 * for the whole file.
 *
 * The recipes come back as [PaprikaRecipe]s, the shape both imports share, with [PaprikaRecipe.uid]
 * the recipe's `recipeId` and its first readable picture stored through [storePhoto] as the file
 * is read (#386; the next one is tried if the first is missing from the zip, too big or not an
 * image). A recipe with pictures none of which could be stored keeps the recipe and counts one
 * picture left out. A page with no `div.recipe-details` at all is not Recipe Keeper's.
 */
object RecipeKeeperArchive {

    private const val PAGE = "recipes.html"
    private const val MAX_PHOTOS_TRIED = 10
    private val ZIP_HEAD = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)

    /** True for an entry that may be the page: a file called `recipes.html`, not hidden or a macOS copy. */
    fun isPageEntry(name: String): Boolean {
        if (name.endsWith("/") || name.startsWith("__MACOSX/") || "/__MACOSX/" in name) return false
        val file = name.substringAfterLast('/')
        return !file.startsWith(".") && file.lowercase() == PAGE
    }

    /**
     * Throws [IOException] only when [file] can't be read at all; a bad zip is a [PaprikaRead.Failure].
     * [storePhoto] answers the stored file's name, or null for bytes that aren't a picture.
     */
    fun read(
        file: File,
        limits: PaprikaLimits = PaprikaLimits(),
        storePhoto: ((ByteArray) -> String?)? = null
    ): PaprikaRead {
        val head = ByteArray(4)
        val got = file.inputStream().use { it.read(head) }
        if (got < 4 || !head.contentEquals(ZIP_HEAD)) return PaprikaRead.Failure(PaprikaError.NOT_A_RECIPE_KEEPER_FILE)
        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            return PaprikaRead.Failure(PaprikaError.DAMAGED)
        }
        zip.use {
            if (zip.size() > limits.maxEntries) return PaprikaRead.Failure(PaprikaError.TOO_LARGE)
            val byName = HashMap<String, ZipEntry>()
            var page: ZipEntry? = null
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                byName.putIfAbsent(entry.name.lowercase(), entry)
                // The shallowest page wins: the root's, else the wrapper folder's.
                if (isPageEntry(entry.name) && (page == null || depth(entry.name) < depth(page.name))) page = entry
            }
            val pageEntry = page ?: return PaprikaRead.Failure(PaprikaError.NOT_A_RECIPE_KEEPER_FILE)
            if (pageEntry.size > limits.maxEntryBytes || pageEntry.compressedSize > limits.maxEntryBytes) {
                return PaprikaRead.Failure(PaprikaError.TOO_LARGE)
            }
            val bytes = try {
                zip.getInputStream(pageEntry).use { it.readAtMost(limits.maxEntryBytes) }
            } catch (e: IOException) {
                return PaprikaRead.Failure(PaprikaError.DAMAGED)
            } ?: return PaprikaRead.Failure(PaprikaError.TOO_LARGE)
            var total = bytes.size().toLong()
            if (total > limits.maxTotalBytes) return PaprikaRead.Failure(PaprikaError.TOO_LARGE)

            // Lenient on purpose: one stray byte shouldn't cost a whole library. The page is UTF-8.
            val parsed = RecipeKeeperParser.parse(String(bytes.toByteArray(), Charsets.UTF_8))
            if (parsed.blocks == 0) return PaprikaRead.Failure(PaprikaError.NOT_A_RECIPE_KEEPER_FILE)

            val folder = pageEntry.name.substringBeforeLast('/', "")
            val recipes = ArrayList<PaprikaRecipe>(parsed.recipes.size)
            var photosLeftOut = 0
            for (recipe in parsed.recipes) {
                var stored: String? = null
                for (src in recipe.photos.take(MAX_PHOTOS_TRIED)) {
                    val entry = resolve(folder, src)?.let { byName[it.lowercase()] } ?: continue
                    if (entry.size > limits.maxPhotoBytes || entry.compressedSize > limits.maxPhotoBytes) continue
                    val picture = try {
                        zip.getInputStream(entry).use { it.readAtMost(limits.maxPhotoBytes.toLong()) }
                    } catch (e: IOException) {
                        null
                    } ?: continue
                    total += picture.size()
                    if (total > limits.maxTotalBytes) return PaprikaRead.Failure(PaprikaError.TOO_LARGE)
                    stored = storePhoto?.invoke(picture.toByteArray())
                    if (stored != null) break
                }
                if (stored == null && recipe.photos.isNotEmpty()) photosLeftOut++
                recipes += PaprikaRecipe(
                    uid = recipe.id,
                    name = recipe.name,
                    ingredients = recipe.ingredients,
                    directions = recipe.directions,
                    notes = recipe.notes,
                    servings = recipe.servings,
                    prepTime = recipe.prepTime,
                    cookTime = recipe.cookTime,
                    totalTime = null,
                    sourceUrl = recipe.sourceUrl,
                    imageUrl = null,
                    categories = recipe.categories,
                    hasPhoto = recipe.photos.isNotEmpty(),
                    photoFile = stored,
                    favorite = recipe.favorite
                )
            }
            return PaprikaRead.Success(PaprikaContents(recipes, parsed.notRecipes, unreadable = 0, photosLeftOut = photosLeftOut))
        }
    }

    private fun depth(name: String) = name.count { it == '/' }

    /**
     * The zip entry name a picture's `src` means, relative to the page's [folder]: `.` and `..` are
     * folded away inside the zip's own namespace, so an address can't reach outside it; a link with a
     * scheme, an absolute path or a backslash is none. A percent-encoded name is read decoded.
     * Only ever compared with entry names, never opened as a path.
     */
    internal fun resolve(folder: String, src: String): String? {
        val decoded = try {
            URLDecoder.decode(src.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            src
        }
        if (decoded.isBlank() || ':' in decoded || '\\' in decoded || decoded.startsWith("/")) return null
        val parts = ArrayList<String>()
        for (segment in "$folder/$decoded".split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
    }
}
