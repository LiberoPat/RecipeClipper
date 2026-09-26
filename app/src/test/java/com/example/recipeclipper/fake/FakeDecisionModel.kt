package com.example.recipeclipper.fake

import com.example.recipeclipper.data.DecisionModel
import com.example.recipeclipper.data.DecisionRepository
import com.example.recipeclipper.data.model.DecisionPrompt
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.DecisionReply
import com.example.recipeclipper.data.model.Decisions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The decision model, faked (#104): [reply] answers each prompt (null: "can't right now"), by
 * default the same [answer] with confidence "high". [asked] records every prompt.
 */
class FakeDecisionModel(
    var languages: Set<String> = setOf("en", "de", "fr"),
    var answer: String = "unsure",
    var reply: (DecisionPrompt) -> DecisionReply? = { DecisionReply(answer, "high") }
) : DecisionModel {
    val asked = mutableListOf<DecisionPrompt>()

    override suspend fun supports(language: String) = language in languages

    override suspend fun ask(prompt: DecisionPrompt): DecisionReply? {
        asked += prompt
        return reply(prompt)
    }
}

/**
 * [DecisionRepository] in memory: [answers] are what the model would decide; [decide] moves
 * each asked question's answer into what [observe] emits. [asked] records the questions.
 */
class FakeDecisionRepository(val answers: Map<DecisionQuestion, String> = emptyMap()) : DecisionRepository {
    val asked = mutableListOf<DecisionQuestion>()
    val cached = MutableStateFlow<Map<DecisionQuestion, String>>(emptyMap())

    override fun observe(): Flow<Decisions> = decisions

    private val decisions = MutableStateFlow(Decisions.NONE)

    override suspend fun decide(questions: Collection<DecisionQuestion>) {
        asked += questions
        cached.value = cached.value + questions.mapNotNull { q -> answers[q]?.let { q to it } }
        decisions.value = Decisions(cached.value)
    }
}
