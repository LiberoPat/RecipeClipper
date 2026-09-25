package com.example.recipeclipper.data

import android.content.Context
import android.os.Build
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.rewriting.Rewriter
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Chef mode's short steps from Gemini Nano on the phone (#100): ML Kit GenAI's Rewriting API
 * with the SHORTEN style, through AICore. Needs API 26 and a supported phone; anything else is
 * [ChefSupport.Unsupported]. The model downloads on first use (the steps show as written
 * meanwhile), and inference only runs while the app is in front, one step at a time; any
 * failure is "not now" (null), never an error on screen.
 */
@Singleton
class MlKitStepShortener @Inject constructor(
    @ApplicationContext private val context: Context
) : StepShortener {

    private val lock = Mutex()
    private val clients = mutableMapOf<String, Rewriter>()
    private var downloadStarted = false

    override suspend fun support(): ChefSupport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ChefSupport.Unsupported
        val status = attempt { lock.withLock { client("en")?.checkFeatureStatus()?.await() } }
        return when (status) {
            FeatureStatus.AVAILABLE, FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING ->
                ChefSupport.Available(LANGUAGES.keys)
            else -> ChefSupport.Unsupported
        }
    }

    override suspend fun shorten(step: String, language: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return attempt {
            lock.withLock {
                val client = client(language) ?: return@withLock null
                when (client.checkFeatureStatus().await()) {
                    FeatureStatus.AVAILABLE ->
                        client.runInference(RewritingRequest.builder(step).build()).await()
                            .results.firstOrNull()?.text
                    FeatureStatus.DOWNLOADABLE -> {
                        startDownload(client)
                        null
                    }
                    else -> null
                }
            }
        }
    }

    // Called under [lock].
    private fun client(language: String): Rewriter? {
        val code = LANGUAGES[language] ?: return null
        return clients.getOrPut(language) {
            Rewriting.getClient(
                RewriterOptions.builder(context)
                    .setOutputType(RewriterOptions.OutputType.SHORTEN)
                    .setLanguage(code)
                    .build()
            )
        }
    }

    // Once per launch; AICore carries on by itself and says AVAILABLE when it's done.
    private fun startDownload(client: Rewriter) {
        if (downloadStarted) return
        downloadStarted = true
        client.downloadFeature(object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) = Unit
            override fun onDownloadProgress(totalBytesDownloaded: Long) = Unit
            override fun onDownloadCompleted() = Unit
            override fun onDownloadFailed(e: GenAiException) {
                downloadStarted = false
            }
        })
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: ExecutionException) {
                cont.resumeWithException(e.cause ?: e)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }, Runnable::run)
        cont.invokeOnCancellation { cancel(false) }
    }

    private companion object {
        /** The recipe languages the Rewriting API writes (no Portuguese), by our codes. */
        val LANGUAGES = mapOf(
            "en" to RewriterOptions.Language.ENGLISH,
            "de" to RewriterOptions.Language.GERMAN,
            "es" to RewriterOptions.Language.SPANISH,
            "fr" to RewriterOptions.Language.FRENCH,
            "it" to RewriterOptions.Language.ITALIAN,
            "ja" to RewriterOptions.Language.JAPANESE
        )
    }
}
