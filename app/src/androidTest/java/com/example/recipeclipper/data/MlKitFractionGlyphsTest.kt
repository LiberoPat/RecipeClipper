package com.example.recipeclipper.data

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.recipeclipper.data.remote.PhotoTextSorter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the real ML Kit makes of vulgar-fraction glyphs (#245): reads the fraction cards
 * (`shared/fixtures/reddit/photos/card-fractions-*.jpg`, pushed to `/data/local/tmp` first) and
 * logs each line under the tag `FractionOcr`, with whether the sorter lists it to check. Skipped
 * when the cards weren't pushed or Play services has no model (an emulator without it, CI).
 */
@RunWith(AndroidJUnit4::class)
class MlKitFractionGlyphsTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun readsTheFractionCards() {
        val context = instrumentation.targetContext
        val reader = MlKitPhotoTextReader(context)
        for (name in listOf("card-fractions-print", "card-fractions-hand")) {
            val bytes = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/$name.jpg")
            ).use { it.readBytes() }
            assumeTrue("push $name.jpg to /data/local/tmp", bytes.isNotEmpty())
            val file = File(context.cacheDir, "$name.jpg").apply { writeBytes(bytes) }
            var result: PhotoTextResult = PhotoTextResult.NotReady
            repeat(24) {
                result = runBlocking { reader.read(listOf("file://${file.absolutePath}")) }
                if (result != PhotoTextResult.NotReady) return@repeat
                Thread.sleep(5000)
            }
            assumeTrue("ML Kit's model isn't here", result is PhotoTextResult.Read)
            val lines = (result as PhotoTextResult.Read).lines
            val toCheck = PhotoTextSorter.sort(lines).uncertain
            for (line in lines) {
                val codes = line.text.map { "%04X".format(it.code) }.take(6).joinToString(" ")
                Log.i("FractionOcr", "$name\t${line.confidence}\t${line.text}\t$codes\tcheck=${toCheck.any { line.text.trim() in it }}\tbox=${line.box}")
            }
            // Each line says where it was read (#286): the first page, and a box on it.
            assertTrue(lines.all { it.page == 0 })
            val boxes = lines.mapNotNull { it.box }
            assertEquals(lines.size, boxes.size)
            assertTrue(boxes.all { it.left >= 0f && it.left < it.right && it.right <= 1f && it.top >= 0f && it.top < it.bottom && it.bottom <= 1f })
        }
    }
}
