package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reading a Mealie file (#391). The files in `shared/fixtures/mealie/` are built by
 * `tools/mealie-fixtures/make_fixtures.py` from Mealie's schema; iOS's `MealieZipReaderTests` reads the
 * same files and expects the same counts.
 */
class MealieArchiveTest {

    @get:Rule val folder = TemporaryFolder()

    private fun fixture(name: String): File {
        val file = folder.newFile(name)
        javaClass.getResourceAsStream("/mealie/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun success(read: MealieRead) = (read as MealieRead.Success).contents

    private fun failure(read: MealieRead) = (read as MealieRead.Failure).error

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun recipeJson(slug: String) =
        """{"slug": "$slug", "name": "R $slug", "recipe_ingredient": [{"original_text": "1 egg", "reference_id": "a"}], "recipe_instructions": []}""".toByteArray()

    @Test fun `a bulk export gives its recipes in order and counts what it skipped`() {
        val contents = success(MealieArchive.read(fixture("mealie-bulk.zip")))
        assertEquals(listOf("Pasta e Ceci", "Old Lentil Soup", "Mint Tea", "Pasta e Ceci (again)"), contents.recipes.map { it.name })
        // The nameless one, the one with only a title, and the schema.org file.
        assertEquals(3, contents.notRecipes)
        // The file that isn't JSON.
        assertEquals(1, contents.unreadable)
        assertEquals(0, contents.photosLeftOut)
        assertTrue(contents.recipes.all { it.photoFile == null })
    }

    @Test fun `a recipe's download gives its recipe and hands its picture to the store`() {
        val seen = mutableListOf<ByteArray>()
        val contents = success(MealieArchive.read(fixture("mealie-recipe-download.zip")) { bytes -> seen += bytes; "stored.jpg" })
        assertEquals(listOf("Pasta e Ceci"), contents.recipes.map { it.name })
        assertEquals(listOf("stored.jpg"), contents.recipes.map { it.photoFile })
        assertEquals(1, seen.size)
        // A WebP: RIFF....WEBP.
        assertEquals("RIFF", String(seen[0], 0, 4, Charsets.ISO_8859_1))
        assertEquals("WEBP", String(seen[0], 8, 4, Charsets.ISO_8859_1))
        assertEquals(0, contents.photosLeftOut)
    }

    @Test fun `a picture beside a recipe's json in its folder belongs to it, and with no store it is left out and counted`() {
        val file = fixture("mealie-with-pictures.zip")
        val withStore = success(MealieArchive.read(file) { "kept.jpg" })
        assertEquals(listOf("kept.jpg", null), withStore.recipes.map { it.photoFile })
        assertEquals(0, withStore.photosLeftOut)
        val without = success(MealieArchive.read(file))
        assertEquals(2, without.recipes.size)
        assertEquals(1, without.photosLeftOut)
    }

    @Test fun `a picture that is too big, not an image, or belongs to no recipe`() {
        val contents = success(MealieArchive.read(fixture("mealie-photos.zip")) { bytes -> if (String(bytes, Charsets.ISO_8859_1).startsWith("bad")) null else "good.jpg" })
        assertEquals(listOf("Toast", "Jam", "Honey", "Salt"), contents.recipes.map { it.name })
        // Toast's is stored; Jam's is refused by the store; Honey's is over 8 MiB and never offered; Salt has none; the stray one fits no recipe.
        assertEquals(listOf("good.jpg", null, null, null), contents.recipes.map { it.photoFile })
        assertEquals(2, contents.photosLeftOut)
    }

    @Test fun `a lowered picture cap leaves a picture out`() {
        val contents = success(MealieArchive.read(fixture("mealie-recipe-download.zip"), MealieLimits(maxPhotoBytes = 100)) { "kept.jpg" })
        assertEquals(1, contents.photosLeftOut)
        assertNull(contents.recipes.single().photoFile)
    }

    @Test fun `a bare json is one recipe`() {
        val contents = success(MealieArchive.read(fixture("mealie-recipe.json")))
        assertEquals(listOf("Mint Tea"), contents.recipes.map { it.name })
        assertEquals(0, contents.notRecipes)
        // The pre 3.1 file: the unparsed lines are their notes.
        val old = success(MealieArchive.read(fixture("mealie-pre31.json"))).recipes.single()
        assertEquals(listOf("2 cups red lentils", "1 onion, chopped", "2 cups water"), old.ingredients)
    }

    @Test fun `a schema_org file is not a Mealie file, alone or in a zip`() {
        assertEquals(MealieError.NOT_A_MEALIE_FILE, failure(MealieArchive.read(fixture("mealie-schema-org.json"))))
        assertEquals(MealieError.NOT_A_MEALIE_FILE, failure(MealieArchive.read(fixture("mealie-only-schema.zip"))))
    }

    @Test fun `folders, macOS copies and other files are ignored, not counted`() {
        assertFalse(MealieArchive.isRecipeEntry("__MACOSX/recipes/a/._a.json"))
        assertFalse(MealieArchive.isRecipeEntry("dir/__MACOSX/a.json"))
        assertFalse(MealieArchive.isRecipeEntry("recipes/.DS_Store.json"))
        assertFalse(MealieArchive.isRecipeEntry("readme.txt"))
        assertFalse(MealieArchive.isRecipeEntry("recipes/"))
        assertTrue(MealieArchive.isRecipeEntry("recipes/a/a.json"))
        assertTrue(MealieArchive.isRecipeEntry("A.JSON"))
        assertTrue(MealieArchive.isPictureEntry("a.webp"))
        assertTrue(MealieArchive.isPictureEntry("recipes/a/images/original.WEBP"))
        assertTrue(MealieArchive.isPictureEntry("a.jpeg"))
        assertFalse(MealieArchive.isPictureEntry("__MACOSX/._a.webp"))
        assertFalse(MealieArchive.isPictureEntry("a.json"))
    }

    @Test fun `a truncated zip is damaged and imports nothing`() {
        assertEquals(MealieError.DAMAGED, failure(MealieArchive.read(fixture("mealie-truncated.zip"))))
    }

    @Test fun `something that is not a zip or a recipe's json is not a Mealie file`() {
        val text = folder.newFile("notes.txt").apply { writeText("Dear diary, today I made soup.") }
        assertEquals(MealieError.NOT_A_MEALIE_FILE, failure(MealieArchive.read(text)))
        assertEquals(MealieError.NOT_A_MEALIE_FILE, failure(MealieArchive.read(folder.newFile())))
        // A zip with no json in it.
        assertEquals(MealieError.NOT_A_MEALIE_FILE, failure(MealieArchive.read(fixture("mealie-not-mealie.zip"))))
    }

    @Test fun `a bare json that is no recipe`() {
        val noName = folder.newFile().apply { writeText("""{"slug": "a", "name": "", "recipe_ingredient": [{"original_text": "1 egg", "reference_id": "r"}]}""") }
        val contents = success(MealieArchive.read(noName))
        assertEquals(0, contents.recipes.size)
        assertEquals(1, contents.notRecipes)
    }

    @Test fun `files that are not UTF-8, not JSON, or not an object are counted and the rest still import`() {
        val contents = success(MealieArchive.read(fixture("mealie-bad-entries.zip")))
        assertEquals(listOf("Mint Tea"), contents.recipes.map { it.name })
        assertEquals(3, contents.unreadable)
        assertEquals(0, contents.notRecipes)
    }

    @Test fun `an entry that claims to unpack to 4 GB is refused unread`() {
        val contents = success(MealieArchive.read(fixture("mealie-huge-declared.zip")))
        assertEquals(listOf("Mint Tea"), contents.recipes.map { it.name })
        assertEquals(1, contents.unreadable)
    }

    @Test fun `an entry that unpacks past the per-entry cap is dropped, however small the file`() {
        val file = fixture("mealie-bomb.zip")
        assertTrue("the bomb is small", file.length() < 100_000)
        val contents = success(MealieArchive.read(file))
        assertEquals(listOf("Mint Tea"), contents.recipes.map { it.name })
        assertEquals(1, contents.unreadable)
    }

    @Test fun `too many entries is refused before any is read`() {
        assertEquals(MealieError.TOO_LARGE, failure(MealieArchive.read(fixture("mealie-bulk.zip"), MealieLimits(maxEntries = 3))))
    }

    @Test fun `more unpacked text than the total cap is refused, and nothing is imported`() {
        assertEquals(MealieError.TOO_LARGE, failure(MealieArchive.read(fixture("mealie-bulk.zip"), MealieLimits(maxTotalBytes = 600))))
    }

    @Test fun `a bare json over the cap is too large`() {
        assertEquals(MealieError.TOO_LARGE, failure(MealieArchive.read(fixture("mealie-recipe.json"), MealieLimits(maxEntryBytes = 100))))
    }

    @Test fun `an entry name is only compared, so a path name does nothing`() {
        val zip = zipOf("../../etc/passwd.json" to recipeJson("a"), "/abs/b.json" to recipeJson("b"))
        assertEquals(2, success(MealieArchive.read(zip)).recipes.size)
    }

    @Test fun `a picture goes to the nearest recipe up its path, and a lone recipe takes any picture`() {
        val seen = mutableListOf<String>()
        val store = { bytes: ByteArray -> String(bytes).also { seen += it }; "f" }
        val nested = zipOf(
            "recipes/a/a.json" to recipeJson("a"), "recipes/a/images/min-original.webp" to "A-min".toByteArray(),
            "recipes/a/images/original.webp" to "A".toByteArray(),
            "recipes/b/b.json" to recipeJson("b"), "recipes/b/b.png" to "B".toByteArray(),
            "recipes/c/c.json" to recipeJson("c"), "recipes/other/c.webp" to "nobody".toByteArray()
        )
        val contents = success(MealieArchive.read(nested, MealieLimits(), store))
        // Of two pictures for a, the one called original wins; c's folder holds none.
        assertEquals(listOf("A", "B"), seen)
        assertEquals(listOf("f", "f", null), contents.recipes.map { it.photoFile })
        seen.clear()
        val lone = zipOf("one.json" to recipeJson("a"), "somewhere/deep/p.jpg" to "P".toByteArray())
        success(MealieArchive.read(lone, MealieLimits(), store))
        assertEquals(listOf("P"), seen)
    }
}
