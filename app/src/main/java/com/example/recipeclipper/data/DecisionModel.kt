package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.DecisionPrompt
import com.example.recipeclipper.data.model.DecisionReply
import org.json.JSONException
import org.json.JSONObject

/**
 * The on-device model making typed decisions (#104), beside [StepShortener] and
 * [PageRecipeExtractor]: [MlKitDecisionModel] is the real one (ML Kit GenAI's Prompt API on
 * Gemini Nano); tests use `FakeDecisionModel`. Only [DecisionRepository] calls it.
 */
interface DecisionModel {

    /** True when it can answer now for a recipe in [language] ("en"). */
    suspend fun supports(language: String): Boolean

    /**
     * One reply to [prompt], unchecked ([com.example.recipeclipper.data.model.DecisionRule]
     * judges it), or null when the model can't answer right now (busy, in the background,
     * downloading, an error): nothing is cached and it is asked again next time.
     */
    suspend fun ask(prompt: DecisionPrompt): DecisionReply?

    companion object {
        /** Answers nothing: the default for tests that aren't about decisions. */
        val None: DecisionModel = object : DecisionModel {
            override suspend fun supports(language: String) = false
            override suspend fun ask(prompt: DecisionPrompt): DecisionReply? = null
        }
    }
}

/**
 * The Prompt API's reply, `{"answer": "...", "confidence": "..."}`, parsed strictly: anything
 * else (prose around it, a missing field) is an unsure reply, which the rule never accepts.
 */
object DecisionReplyJson {

    val UNREADABLE = DecisionReply("unsure", "low")

    fun parse(text: String): DecisionReply = try {
        val o = JSONObject(text.trim())
        val answer = o.optString("answer", "")
        val confidence = o.optString("confidence", "")
        if (answer.isEmpty() || confidence.isEmpty()) UNREADABLE else DecisionReply(answer, confidence)
    } catch (e: JSONException) {
        UNREADABLE
    }
}
