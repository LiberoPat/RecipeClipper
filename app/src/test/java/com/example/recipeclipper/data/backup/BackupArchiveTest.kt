package com.example.recipeclipper.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * The export with photos (#116): the shared zip fixture (written by a third tool, Python's
 * zipfile) reads here as in the iOS `BackupArchiveTests`; a zip this app writes reads back; and
 * the merge brings in only photos whose recipe and picture both came.
 */
class BackupArchiveTest {

    private val fixtureZip: ByteArray =
        requireNotNull(javaClass.classLoader?.getResource("backup/backup-v1-photos.zip")).readBytes()

    private fun temp(): File = Files.createTempDirectory("unpacked").toFile()

    private fun read(bytes: ByteArray, max: Int = 1_000_000) = BackupArchive.read(ByteArrayInputStream(bytes), temp(), max)

    @Test fun `the shared zip fixture reads its JSON and its photos`() {
        assertTrue(BackupArchive.isZip(fixtureZip))
        val pkg = (read(fixtureZip) as BackupResult.Success).value
        assertEquals(setOf("photos/p-first.jpg", "photos/p-second.jpg", "photos/p-orphan.jpg"), pkg.photos.keys)
        assertEquals("first picture", File(pkg.photos.getValue("photos/p-first.jpg")).readText())

        val backup = decodeOrFail(pkg.json)
        // The photo naming no recipe in the file is left out on reading.
        assertEquals(listOf("p-first", "p-second", "p-nopicture"), backup.cookedPhotos.map { it.id })
        assertEquals(BackupCookedPhoto("p-first", "r-soup", 20_000, "Less salt next time", 1789000000600, 1789000000700, "photos/p-first.jpg"), backup.cookedPhotos[0])
        assertEquals(null, backup.cookedPhotos[1].note)
    }

    @Test fun `the merge takes a photo only with its picture, and its recipe counts as listed`() {
        val pkg = (read(fixtureZip) as BackupResult.Success).value
        val plan = BackupMerger.plan(
            backup = decodeOrFail(pkg.json), existingRecipes = emptyList(), existingLists = emptyList(), maxSortOrder = 0,
            historyLimit = 0, newUid = { "fresh" }, availablePhotoFiles = pkg.photos.keys
        )
        assertEquals(listOf("r-soup"), plan.newRecipes.map { it.id })
        assertEquals(listOf("p-first", "p-second"), plan.newCookedPhotos.map { it.photo.id })
        assertEquals(2, plan.summary.photosAdded)

        val again = BackupMerger.plan(
            backup = decodeOrFail(pkg.json), existingRecipes = emptyList(), existingLists = emptyList(), maxSortOrder = 0,
            historyLimit = 0, newUid = { "fresh" }, existingCookedPhotoUids = setOf("p-first"), availablePhotoFiles = emptySet()
        )
        assertTrue(again.newCookedPhotos.isEmpty())
        assertTrue(again.newRecipes.isEmpty())
    }

    @Test fun `a zip this app writes reads back, and plain JSON is not a zip`() {
        val picture = Files.createTempFile("pic", ".jpg").toFile().apply { writeText("bytes") }
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, "{\"x\":1}", mapOf("photos/a.jpg" to picture, "../evil.jpg" to picture))
        val pkg = (read(out.toByteArray()) as BackupResult.Success).value
        assertEquals("{\"x\":1}", pkg.json)
        assertEquals(setOf("photos/a.jpg"), pkg.photos.keys)
        assertFalse(BackupArchive.isZip("{\"format\"".toByteArray()))
    }

    @Test fun `a zip with no JSON, or JSON over the limit, is not a backup`() {
        val noJson = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { it.putNextEntry(java.util.zip.ZipEntry("photos/a.jpg")); it.write(1) }
        }.toByteArray()
        assertEquals(BackupResult.Failure(BackupError.NotABackup), read(noJson))
        val big = ByteArrayOutputStream().also { BackupArchive.write(it, "{}", emptyMap()) }.toByteArray()
        assertEquals(BackupResult.Failure(BackupError.NotABackup), read(big, max = 1))
        assertEquals(BackupResult.Failure(BackupError.NotABackup), read("PK\u0003\u0004garbage".toByteArray()))
    }

    @Test fun `a photo's file must be a plain name under photos`() {
        val bad = """{"format":"recipe-clipper-backup","formatVersion":1,"exportedAt":1,"recipes":[
            {"id":"r","sourceUrl":"https://a.com","sourceType":"BLOG","title":"T","ingredients":["x"],"instructions":[],"lastViewedAt":1}],
            "cookedPhotos":[{"id":"p","recipeId":"r","day":1,"file":"photos/../../x.jpg"}]}"""
        assertEquals(BackupResult.Failure(BackupError.Malformed("cookedPhotos[0].file")), BackupJson.decode(bad))
    }
}
