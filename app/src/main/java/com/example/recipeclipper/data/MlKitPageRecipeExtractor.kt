package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.PageSelection
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
 * first use, and until then the page is `NoRecipeFound` as before. Any failure is null.
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

    override suspend fun extract(text: String, language: String): PageSelection? = attempt {
        lock.withLock {
            val request = generateContentRequest(TextPart(PROMPT + text)) {
                temperature = 0f
                topK = 1
                candidateCount = 1
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
            val reply = client().generateContent(request).candidates.firstOrNull()?.text
            reply?.let(PageSelectionJson::parse)
        }
    }

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
        const val MAX_OUTPUT_TOKENS = 1_024

        /** The recipe languages it is offered for: those Google lists for Gemini Nano's text features. */
        val LANGUAGES = setOf("en", "de", "es", "fr", "it", "ja")

        val PROMPT = """
            You pick a recipe out of a web page's text. Copy every value exactly as it is written on
            the page, character for character: never write, fix, translate, shorten or summarise.
            Reply with one JSON object and nothing else:
            {"name": string, "ingredients": [string], "steps": [string], "yield": string or null,
            "prepTime": string or null, "cookTime": string or null, "totalTime": string or null}
            One ingredient line per item, one step per item, in page order. If the page holds no
            recipe, reply {}.

            Page text:

        """.trimIndent() + "\n"
    }
}
