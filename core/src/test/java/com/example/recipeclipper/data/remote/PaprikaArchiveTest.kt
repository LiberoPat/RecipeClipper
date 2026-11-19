package com.example.recipeclipper.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reading a Paprika export (#382). The files in `shared/fixtures/paprika/` are built by
 * `tools/paprika-fixtures/make_fixtures.py` from the format libpaprika documents (a zip of gzipped
 * JSON); iOS's `PaprikaArchiveTests` reads the same files and expects the same counts.
 */
class PaprikaArchiveTest {

    @get:Rule val folder = TemporaryFolder()

    private fun fixture(name: String): File {
        val file = folder.newFile(name)
        javaClass.getResourceAsStream("/paprika/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun success(read: PaprikaRead) = (read as PaprikaRead.Success).contents

    private fun failure(read: PaprikaRead) = (read as PaprikaRead.Failure).error

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

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

    @Test fun `a valid export gives its recipes in order and counts what it skipped`() {
        val contents = success(PaprikaArchive.read(fixture("paprika-valid.paprikarecipes")))
        assertEquals(
            listOf("Fudgy Brownies", "Gran's Lentil Soup", "Mint Tea", "Brownies again"),
            contents.recipes.map { it.name }
        )
        // The recipe with a name but no ingredients or steps, and the one with no name.
        assertEquals(2, contents.notRecipes)
        assertEquals(0, contents.unreadable)
    }

    @Test fun `each picture is handed to the store as it is read, and the recipe names the file it got`() {
        val seen = mutableListOf<String>()
        val read = PaprikaArchive.read(fixture("paprika-photos.paprikarecipes"), PaprikaLimits()) { bytes ->
            // The store's answer: a name for a picture, null for what isn't one.
            if (String(bytes).startsWith("bad")) null else "stored-${seen.size + 1}.jpg".also { seen += String(bytes, Charsets.ISO_8859_1) }
        }
        val contents = success(read)
        assertEquals(listOf("Toast", "Jam", "Butter", "Honey", "Salt"), contents.recipes.map { it.name })
        // Only the good picture reached the store as a stored file; Honey's is over the 8 MiB and
        // Butter's isn't base64, so neither was even offered, and Jam's was refused by the store.
        assertEquals(listOf("stored-1.jpg", null, null, null, null), contents.recipes.map { it.photoFile })
        assertEquals(1, seen.size)
        assertEquals(3, contents.photosLeftOut)
        assertTrue(contents.recipes.all { it.photoData == null })
    }

    @Test fun `with no store every picture is left out and counted, the recipes stay`() {
        val contents = success(PaprikaArchive.read(fixture("paprika-photos.paprikarecipes")))
        assertEquals(5, contents.recipes.size)
        assertEquals(4, contents.photosLeftOut)
    }

    @Test fun `a lowered picture cap leaves a picture out`() {
        val contents = success(PaprikaArchive.read(fixture("paprika-photos.paprikarecipes"), PaprikaLimits(maxPhotoBytes = 8)) { "kept.jpg" })
        // Toast's 31 bytes and Jam's 25 are over 8; the others were never offered.
        assertEquals(4, contents.photosLeftOut)
        assertTrue(contents.recipes.all { it.photoFile == null })
    }

    @Test fun `folders, macOS copies and other files are ignored, not counted`() {
        val contents = success(PaprikaArchive.read(fixture("paprika-valid.paprikarecipes")))
        assertEquals(0, contents.unreadable)
        assertFalse(PaprikaArchive.isRecipeEntry("__MACOSX/._A.paprikarecipe"))
        assertFalse(PaprikaArchive.isRecipeEntry("dir/__MACOSX/A.paprikarecipe"))
        assertFalse(PaprikaArchive.isRecipeEntry("._A.paprikarecipe"))
        assertFalse(PaprikaArchive.isRecipeEntry("readme.txt"))
        assertFalse(PaprikaArchive.isRecipeEntry("folder/"))
        assertTrue(PaprikaArchive.isRecipeEntry("Pasta.paprikarecipe"))
        assertTrue(PaprikaArchive.isRecipeEntry("sub/Pasta.PAPRIKARECIPE"))
    }

    @Test fun `a truncated zip is damaged and imports nothing`() {
        assertEquals(PaprikaError.DAMAGED, failure(PaprikaArchive.read(fixture("paprika-truncated.paprikarecipes"))))
    }

    @Test fun `something that is not a zip is not a Paprika file`() {
        val text = folder.newFile("notes.txt").apply { writeText("Dear diary, today I made soup.") }
        assertEquals(PaprikaError.NOT_A_PAPRIKA_FILE, failure(PaprikaArchive.read(text)))
        assertEquals(PaprikaError.NOT_A_PAPRIKA_FILE, failure(PaprikaArchive.read(folder.newFile())))
    }

    @Test fun `a zip with no recipe entries is not a Paprika file`() {
        val zip = zipOf("readme.txt" to "hi".toByteArray())
        assertEquals(PaprikaError.NOT_A_PAPRIKA_FILE, failure(PaprikaArchive.read(zip)))
    }

    @Test fun `entries that are not gzip, not JSON, or not an object are counted and the rest still import`() {
        val contents = success(PaprikaArchive.read(fixture("paprika-bad-entries.paprikarecipes")))
        assertEquals(listOf("Mint Tea"), contents.recipes.map { it.name })
        // Plain JSON (not gzipped), text, a list, and bytes that aren't UTF-8.
        assertEquals(4, contents.unreadable)
        assertEquals(0, contents.notRecipes)
    }

    @Test fun `an entry that claims to unpack to 4 GB is refused unread`() {
        val contents = success(PaprikaArchive.read(fixture("paprika-huge-declared.paprikarecipes")))
        assertEquals(listOf("Mint Tea"), contents.recipes.map { it.name })
        assertEquals(1, contents.unreadable)
    }

    @Test fun `an entry that unpacks past the per-entry cap is dropped, however small the file`() {
        val file = fixture("paprika-bomb.paprikarecipes")
        assertTrue("the bomb is tiny", file.length() < 5_000)
        val contents = success(PaprikaArchive.read(file))
        assertEquals(listOf("Mint Tea"), contents.recipes.map { it.name })
        assertEquals(1, contents.unreadable)
    }

    @Test fun `too many entries is refused before any is read`() {
        val file = fixture("paprika-valid.paprikarecipes")
        assertEquals(PaprikaError.TOO_LARGE, failure(PaprikaArchive.read(file, PaprikaLimits(maxEntries = 3))))
    }

    @Test fun `more unpacked text than the total cap is refused, and nothing is imported`() {
        val file = fixture("paprika-valid.paprikarecipes")
        assertEquals(PaprikaError.TOO_LARGE, failure(PaprikaArchive.read(file, PaprikaLimits(maxTotalBytes = 600))))
    }

    @Test fun `a lowered entry cap drops the recipes over it`() {
        val file = fixture("paprika-valid.paprikarecipes")
        val contents = success(PaprikaArchive.read(file, PaprikaLimits(maxEntryBytes = 400)))
        // Only the small ones fit; the others are unreadable, not an error.
        assertTrue(contents.unreadable > 0)
        assertEquals(contents.recipes.size + contents.notRecipes + contents.unreadable, 6)
    }

    @Test fun `an entry name is only compared, so a path name does nothing`() {
        val json = """{"name": "Soup", "ingredients": "1 onion"}"""
        val zip = zipOf("../../etc/passwd.paprikarecipe" to gzip(json), "/abs/Soup2.paprikarecipe" to gzip(json))
        val contents = success(PaprikaArchive.read(zip))
        assertEquals(2, contents.recipes.size)
    }
}
