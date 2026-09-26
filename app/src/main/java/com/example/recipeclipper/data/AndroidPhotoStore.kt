package com.example.recipeclipper.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * [PhotoStore] in `filesDir/cooked_photos/`: private to the app, kept until the app is removed,
 * and (like every file but the database and settings) outside Android's Auto Backup, whose 25 MB
 * quota photos would overrun, stopping the whole app's backup. The export file carries them
 * instead (#26, #116); a phone restored from Auto Backup has the rows without their files.
 */
class AndroidPhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: ErrorLog
) : PhotoStore {

    private fun dir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    override suspend fun importPicture(source: String): String? = withContext(Dispatchers.IO) {
        safely("importPicture") {
            val uri = source.toUri()
            fun open(): InputStream = checkNotNull(context.contentResolver.openInputStream(uri))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().use { BitmapFactory.decodeStream(it, null, bounds) }
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            if (longEdge <= 0) return@safely null
            // Decode at the smallest power-of-two reduction still at least MAX_EDGE long.
            var sample = 1
            while (longEdge / (sample * 2) >= PhotoStore.MAX_EDGE) sample *= 2
            val decoded = open().use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@safely null
            val degrees = open().use { rotation(ExifInterface(it)) }
            val scale = min(1f, PhotoStore.MAX_EDGE.toFloat() / max(decoded.width, decoded.height))
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postRotate(degrees.toFloat())
            }
            val upright = if (scale == 1f && degrees == 0) decoded else Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true
            )
            write { out -> upright.compress(Bitmap.CompressFormat.JPEG, PhotoStore.JPEG_QUALITY, out) }
        }
    }

    override suspend fun adopt(path: String): String? = withContext(Dispatchers.IO) {
        safely("adopt") {
            val from = File(path)
            if (!from.isFile) return@safely null
            write { out -> from.inputStream().use { it.copyTo(out) } }
        }
    }

    override fun path(name: String): String = File(File(context.filesDir, DIR), name).absolutePath

    override fun exists(name: String): Boolean = File(path(name)).isFile

    override suspend fun files(): Map<String, Long> = withContext(Dispatchers.IO) {
        dir().listFiles().orEmpty().filter { it.isFile && it.name.endsWith(EXTENSION) }
            .associate { it.name to it.lastModified() }
    }

    override suspend fun delete(names: Collection<String>) = withContext(Dispatchers.IO) {
        // Only plain names in the store: a name is never a path, so nothing outside it goes.
        names.filter { it.isNotEmpty() && '/' !in it && it != ".." }.forEach { File(dir(), it).delete() }
    }

    /** Writes a new file through a temporary one, so a failure never leaves half a JPEG. */
    private fun write(body: (java.io.OutputStream) -> Unit): String? {
        val name = UUID.randomUUID().toString() + EXTENSION
        val temp = File(dir(), "$name.part")
        temp.outputStream().use(body)
        return if (temp.renameTo(File(dir(), name))) name else null.also { temp.delete() }
    }

    private inline fun safely(what: String, body: () -> String?): String? = try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("$what failed", e)
        null
    } catch (e: OutOfMemoryError) {
        log.error("$what ran out of memory", e)
        null
    }

    private fun rotation(exif: ExifInterface): Int = when (
        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    companion object {
        const val DIR = "cooked_photos"
        const val EXTENSION = ".jpg"
    }
}
