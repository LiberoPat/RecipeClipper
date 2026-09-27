package com.example.recipeclipper.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.DefaultBackupRepository
import com.example.recipeclipper.data.DefaultCookedPhotoRepository
import com.example.recipeclipper.data.ErrorLog
import com.example.recipeclipper.data.backup.BackupArchive
import com.example.recipeclipper.data.backup.BackupPackage
import com.example.recipeclipper.data.backup.BackupResult
import com.example.recipeclipper.data.backup.ExportedBackup
import com.example.recipeclipper.data.local.entity.RecipeEntity
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.fake.FakeLibraryPolicy
import com.example.recipeclipper.fake.FakePhotoStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Photos in the export file (#116): an export with photos is a zip of the JSON and the
 * pictures; importing it into an empty phone brings the photos back with their recipe, even
 * past a full history (a recipe with photos is protected); and a plain JSON export still reads.
 */
@RunWith(AndroidJUnit4::class)
class CookedPhotoBackupTest {

    private fun open() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeDatabase::class.java)
        .addCallback(RecipeDatabase.SeedBuiltInLists).allowMainThreadQueries().build()

    private val log = ErrorLog { _, e -> throw e }
    private val from = open()
    private val to = open()
    private val fromStore = FakePhotoStore()
    private val toStore = FakePhotoStore()

    @After fun close() {
        from.close()
        to.close()
    }

    private fun recipe(url: String) = RecipeEntity(
        sourceUrl = url, title = "R", imageUrl = null, ingredients = listOf("1 egg"), instructions = listOf("Cook."),
        prepTime = null, cookTime = null, totalTime = null, servings = null, sourceType = "BLOG", lastViewedAt = 1
    )

    private fun zip(exported: ExportedBackup): ByteArray = ByteArrayOutputStream().also {
        BackupArchive.write(it, exported.json, exported.photos.mapValues { (_, path) -> File(path) })
    }.toByteArray()

    @Test fun photosRoundTripThroughTheZipPastAFullHistory() = runBlocking {
        val id = from.recipeDao().upsert(recipe("https://example.com/a"), LibraryLimit.Unlimited)
        val photos = DefaultCookedPhotoRepository(from.cookedPhotoDao(), fromStore, Clock { 5 }, log)
        val photo = photos.add(id, listOf("JPEG BYTES")).single()
        photos.edit(photo.id, 20_000, "Less sugar")

        val exported = (DefaultBackupRepository(from.backupDao(), { 9 }, log, photos = fromStore).export()
            as BackupResult.Success).value
        assertEquals(1, exported.photos.size)
        val bytes = zip(exported)
        assertTrue(BackupArchive.isZip(bytes.copyOf(4)))

        val unpacked = Files.createTempDirectory("import").toFile()
        val pkg = (BackupArchive.read(ByteArrayInputStream(bytes), unpacked, 1_000_000) as BackupResult.Success).value
        // A history with no free place: the recipe comes in anyway, because it has a photo.
        val into = DefaultBackupRepository(to.backupDao(), { 9 }, log, FakeLibraryPolicy(LibraryLimit.History(0)), toStore)
        val summary = (into.import(pkg) as BackupResult.Success).value
        assertEquals(1, summary.recipesAdded)
        assertEquals(1, summary.photosAdded)

        val copy = to.recipeDao().findByUrl("https://example.com/a")!!
        val imported = to.cookedPhotoDao().observeFor(copy.id).first().single()
        assertEquals(photo.uid, imported.uid)
        assertEquals(20_000L, imported.day)
        assertEquals("Less sugar", imported.note)
        assertEquals("JPEG BYTES", toStore.bytes(imported.fileName))

        // Again: the photo is already here.
        assertEquals(0, (into.import(pkg) as BackupResult.Success).value.photosAdded)
    }

    @Test fun anExportWithoutPhotosIsThePlainJsonAndAPhotoWithoutItsPictureStaysOut() = runBlocking {
        val id = from.recipeDao().upsert(recipe("https://example.com/a"), LibraryLimit.Unlimited)
        val plain = (DefaultBackupRepository(from.backupDao(), { 9 }, log, photos = fromStore).export() as BackupResult.Success).value
        assertTrue(plain.photos.isEmpty())
        assertFalse(plain.json.contains("cookedPhotos"))

        DefaultCookedPhotoRepository(from.cookedPhotoDao(), fromStore, Clock { 5 }, log).add(id, listOf("x"))
        val withPhotos = (DefaultBackupRepository(from.backupDao(), { 9 }, log, photos = fromStore).export() as BackupResult.Success).value
        // Only the JSON, as if the pictures had been lost on the way.
        val summary = (DefaultBackupRepository(to.backupDao(), { 9 }, log, photos = toStore).import(BackupPackage(withPhotos.json))
            as BackupResult.Success).value
        assertEquals(1, summary.recipesAdded)
        assertEquals(0, summary.photosAdded)
        assertTrue(toStore.files().isEmpty())
    }
}
