package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.DecisionPrompt
import com.example.recipeclipper.data.model.DecisionReply
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed decisions by Gemini Nano on the phone (#104): ML Kit GenAI's Prompt API, one short JSON
 * reply per ask, parsed strictly ([DecisionReplyJson]). The Prompt API's structured output is
 * alpha and needs a KSP schema compiler, as #103 found. Only when the model is already
 * downloaded ([MlKitPageRecipeExtractor] starts that); any failure is "not now".
 */
@Singleton
class MlKitDecisionModel @Inject constructor() : DecisionModel {

    private val lock = Mutex()
    private var model: GenerativeModel? = null

    override suspend fun supports(language: String): Boolean =
        language in LANGUAGES && attempt { lock.withLock { client().checkStatus() } } == FeatureStatus.AVAILABLE

    override suspend fun ask(prompt: DecisionPrompt): DecisionReply? = attempt {
        lock.withLock {
            val text = prompt.instructions + "\n" + FORMAT + "\n\n" + prompt.text
            val request = generateContentRequest(TextPart(text)) {
                temperature = 0f
                topK = 1
                candidateCount = 1
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
            client().generateContent(request).candidates.firstOrNull()?.text?.let(DecisionReplyJson::parse)
        }
    }

    // Called under [lock].
    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    private suspend fun <T> attempt(block: suspend () -> T?): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val MAX_OUTPUT_TOKENS = 40

        /** As for #103: the languages Google lists for Gemini Nano's text features. */
        val LANGUAGES = setOf("en", "de", "es", "fr", "it", "ja")

        const val FORMAT = "Reply with one JSON object and nothing else: {\"answer\": string, \"confidence\": string}"
    }
}
