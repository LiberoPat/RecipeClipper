package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.PageReply
import com.example.recipeclipper.data.model.PageReplyRetry
import com.example.recipeclipper.data.model.PageSelection
import com.example.recipeclipper.data.model.RunawayReply
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A recipe picked from a page's text by Gemini Nano on the phone (#103): ML Kit GenAI's Prompt
 * API through AICore, as a JSON reply parsed strictly ([PageSelectionJson]). Rewriting, which
 * Chef mode uses, takes only short inputs. Needs a supported phone; the model downloads on
 * first use, and until then the page is `NoRecipeFound` as before. Any failure is null. A reply
 * that runs away or can't be read is asked for once more ([PageReplyRetry], #273).
 */
@Singleton
class MlKitPageRecipeExtractor @Inject constructor() : PageRecipeExtractor {

    private val lock = Mutex()
    private var model: GenerativeModel? = null
    private var downloadStarted = false

    override suspend fun windowChars(language: String): Int? {
        if (language !in LANGUAGES) return null
        val status = attempt { lock.withLock { client().checkStatus() } }
        return when (status) {
            FeatureStatus.AVAILABLE -> if (language == "ja") WINDOW_TOKENS else WINDOW_TOKENS * 3
            FeatureStatus.DOWNLOADABLE -> { startDownload(); null }
            else -> null
        }
    }

    override suspend fun extract(text: String, language: String): PageSelection? =
        PageReplyRetry.pick { retry -> ask(text, retry) }

    /**
     * One reply, streamed so a runaway ([RunawayReply], #273) is stopped as soon as it shows
     * rather than at the reply cap. Any exception (busy, over the battery quota, blocked in the
     * background) is [PageReply.Failed], never retried.
     */
    private suspend fun ask(text: String, retry: Boolean): PageReply = attempt {
        lock.withLock {
            val instructions = if (retry) INSTRUCTIONS + " " + PageReplyRetry.REMINDER else INSTRUCTIONS
            val request = generateContentRequest(TextPart(instructions + PAGE_TEXT + text)) {
                temperature = 0f
                topK = 1
                candidateCount = 1
                maxOutputTokens = if (retry) PageReplyRetry.RETRY_REPLY_TOKENS else MAX_OUTPUT_TOKENS
            }
            val reply = StringBuilder()
            var scanned = 0
            try {
                client().generateContentStream(request).collect { chunk ->
                    reply.append(chunk.candidates.firstOrNull()?.text.orEmpty())
                    // Every few hundred characters: a scan is linear in the reply so far.
                    if (reply.length - scanned >= SCAN_EVERY) {
                        scanned = reply.length
                        if (RunawayReply.isRunaway(reply.toString())) throw RunawayStopped()
                    }
                }
            } catch (e: RunawayStopped) {
                return@withLock PageReply.Unreadable
            }
            val text = reply.toString()
            when {
                text.isBlank() -> PageReply.Failed
                RunawayReply.isRunaway(text) -> PageReply.Unreadable
                else -> PageSelectionJson.parse(text)?.let { PageReply.Picked(it) } ?: PageReply.Unreadable
            }
        }
    } ?: PageReply.Failed

    /** Thrown out of the stream to stop a runaway reply. */
    private class RunawayStopped : Exception()

    // Called under [lock].
    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    // Once per launch, in the background; AICore says AVAILABLE when it's done.
    private fun startDownload() {
        if (downloadStarted) return
        downloadStarted = true
        downloads.launch {
            val done = attempt { lock.withLock { client() }.download().collect { }; true }
            if (done == null) downloadStarted = false
        }
    }

    private val downloads = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private suspend fun <T> attempt(block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        /** The Prompt API takes under 4,000 tokens: this much page text, beside the prompt. */
        const val WINDOW_TOKENS = 3_000

        /**
         * The most the Prompt API takes (`GenerateContentRequest` rejects more than 4,096 in
         * genai-prompt 1.0.0-beta4). The lines are copied out, so the reply is as long as the
         * recipe: 1,024 cut off a 20-ingredient recipe in the #105 evaluation (#128).
         */
        const val MAX_OUTPUT_TOKENS = 4_096

        /** The recipe languages it is offered for: those Google lists for Gemini Nano's text features. */
        val LANGUAGES = setOf("en", "de", "es", "fr", "it", "ja")

        /** Characters of reply between runaway checks (#273). */
        const val SCAN_EVERY = 256

        /** The retry appends [PageReplyRetry.REMINDER] to these. */
        val INSTRUCTIONS = """
            You pick a recipe out of a web page's text. Copy every value exactly as it is written on
            the page, character for character: never write, fix, translate, shorten or summarise.
            Reply with one JSON object and nothing else:
            {"name": string, "ingredients": [string], "steps": [string], "yield": string or null,
            "prepTime": string or null, "cookTime": string or null, "totalTime": string or null}
            One ingredient line per item, one step per item, in page order. If the page holds no
            recipe, reply {}.
        """.trimIndent()

        const val PAGE_TEXT = "\n\nPage text:\n\n"
    }
}
