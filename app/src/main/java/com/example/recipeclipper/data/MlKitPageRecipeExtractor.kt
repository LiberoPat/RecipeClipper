package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.PageLines
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
 * API through AICore. It reads the window with each line numbered and answers which lines are
 * the ingredients and the steps, as runs of line numbers in a JSON reply parsed strictly
 * ([PagePickJson]); the lines themselves come from the window ([PageLines], #128), so the reply
 * stays short however long the recipe. Rewriting, which Chef mode uses, takes only short inputs.
 * Needs a supported phone; the model downloads on first use, and until then the page is
 * `NoRecipeFound` as before. Any failure is null.
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
            val request = generateContentRequest(TextPart(PROMPT + PageLines.numbered(text))) {
                temperature = 0f
                topK = 1
                candidateCount = 1
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
            val reply = client().generateContent(request).candidates.firstOrNull()?.text
            reply?.let(PagePickJson::parse)?.let { PageLines.selection(text, it) }
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
        /**
         * The Prompt API takes under 4,000 tokens: this much page text, beside the prompt and the
         * line numbers (a few tokens a line; 3 characters a token underestimates English text).
         */
        const val WINDOW_TOKENS = 3_000

        /**
         * The most the Prompt API takes (`GenerateContentRequest` rejects more than 4,096 in
         * genai-prompt 1.0.0-beta4). A reply of line runs needs a few dozen; copying the lines
         * out (#103) cut off a 20-ingredient recipe at 1,024 in the #105 evaluation.
         */
        const val MAX_OUTPUT_TOKENS = 4_096

        /** The recipe languages it is offered for: those Google lists for Gemini Nano's text features. */
        val LANGUAGES = setOf("en", "de", "es", "fr", "it", "ja")

        /** The recipe as runs of the numbered window's lines (#128); the name, yield and times as text. */
        val PROMPT = """
            You find a recipe in a web page's text. Each line of the page starts with its number in
            brackets, like [12]. Reply with one JSON object and nothing else:
            {"name": string, "yield": string or null, "prepTime": string or null,
            "cookTime": string or null, "totalTime": string or null,
            "ingredients": [{"first": number, "last": number}], "steps": [{"first": number, "last": number}]}
            Copy the name, the yield and the times exactly as the page writes them. For the
            ingredients and the steps, give line numbers, not text: each run is the first and last
            line of consecutive lines that are all the recipe's ingredient lines, or all its
            method's steps, in page order. Leave out headings, notes, tips, ads and other recipes.
            If the page holds no recipe, reply {}.

            Page text:

        """.trimIndent() + "\n"
    }
}
