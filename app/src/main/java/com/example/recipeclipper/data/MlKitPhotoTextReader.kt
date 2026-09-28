package com.example.recipeclipper.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import com.example.recipeclipper.data.remote.PhotoLine
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads a post's photos with ML Kit Text Recognition's Latin model from Google Play services
 * (#198): on the phone, offline once the picture and the model are here. The model isn't in the
 * app: Play services downloads it, ahead of time when the manifest's DEPENDENCIES meta-data is
 * honoured, else on first use. Until it's there (still downloading, offline on first use, or no
 * Play services) a read is [PhotoTextResult.NotReady], and asks Play services for the model so
 * Try again can work later. Each picture is fetched at full size through the app's image loader
 * (so one already shown comes from its cache), capped at [MAX_SIDE] pixels a side, which keeps a
 * 12-megapixel card's small print legible without holding a huge bitmap. Lines come in ML Kit's
 * block order, each with its confidence.
 */
@Singleton
class MlKitPhotoTextReader @Inject constructor(
    @ApplicationContext private val context: Context
) : PhotoTextReader {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    override suspend fun read(imageUrls: List<String>): PhotoTextResult {
        if (!modelReady()) return PhotoTextResult.NotReady
        var anyRead = false
        val lines = mutableListOf<PhotoLine>()
        for (url in imageUrls) {
            val text = try {
                val bitmap = load(url) ?: continue
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MlKitException) {
                // The model went missing between the check and the read (or is still arriving).
                if (e.errorCode == MlKitException.UNAVAILABLE) return PhotoTextResult.NotReady
                continue
            } catch (e: Exception) {
                continue // this picture failed; the others may still read
            }
            anyRead = true
            lines += linesOf(text)
        }
        return if (anyRead) PhotoTextResult.Read(lines) else PhotoTextResult.Failed
    }

    /** Whether Play services has the model; if not, asks it to download the model. */
    private suspend fun modelReady(): Boolean {
        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playServices != ConnectionResult.SUCCESS) return false
        val modules = ModuleInstall.getClient(context)
        return try {
            if (modules.areModulesAvailable(recognizer).await().areModulesAvailable()) {
                true
            } else {
                // Not awaited: the download outlives this read, and Try again picks it up.
                modules.installModules(ModuleInstallRequest.newBuilder().addApi(recognizer).build())
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun load(url: String): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(MAX_SIDE)
            .precision(Precision.INEXACT) // subsample a large picture, never enlarge a small one
            .allowHardware(false) // ML Kit reads the pixels
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        return (result.drawable as? BitmapDrawable)?.bitmap
    }

    private fun linesOf(text: Text): List<PhotoLine> =
        text.textBlocks.flatMap { block ->
            block.lines.map { line -> PhotoLine(line.text, line.confidence.takeIf { it > 0f }) }
        }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    private companion object {
        const val MAX_SIDE = 4096
    }
}
