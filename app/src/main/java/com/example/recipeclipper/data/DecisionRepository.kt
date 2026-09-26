package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.local.dao.AiDecisionDao
import com.example.recipeclipper.data.local.entity.AiDecisionEntity
import com.example.recipeclipper.data.model.DecisionKind
import com.example.recipeclipper.data.model.DecisionPrompts
import com.example.recipeclipper.data.model.DecisionQuestion
import com.example.recipeclipper.data.model.DecisionRule
import com.example.recipeclipper.data.model.Decisions
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The on-device model's typed decisions (#104): asked lazily, judged, cached. */
interface DecisionRepository {

    /** Every cached answer, then every change. [Decisions.NONE] while the `aiDecisions` flag is off. */
    fun observe(): Flow<Decisions>

    suspend fun current(): Decisions = observe().first()

    /**
     * Asks the model each of [questions] not cached yet, [DecisionRule.ASKS] times, and caches
     * the judged answer (an option or "unsure"), so it is never asked again. Nothing with the
     * flag off, or in a language the model can't do; a model that can't answer now caches nothing.
     */
    suspend fun decide(questions: Collection<DecisionQuestion>)
}

@Singleton
class DefaultDecisionRepository @Inject constructor(
    private val dao: AiDecisionDao,
    private val model: DecisionModel,
    private val flags: FeatureFlags,
    private val clock: Clock,
    private val log: ErrorLog
) : DecisionRepository {

    // One question at a time, so two screens never ask the same one twice.
    private val lock = Mutex()

    // Count brackets need their own flag too (#127): off, a cached answer is never applied.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<Decisions> =
        flags.values.map { it.isOn(Flag.AI_DECISIONS) to it.isOn(Flag.AI_COUNT_BRACKETS) }.distinctUntilChanged()
            .flatMapLatest { (on, brackets) ->
                if (!on) flowOf(Decisions.NONE)
                else dao.observe().orEmptyOnError(log, "observeDecisions").map { rows ->
                    Decisions(
                        rows.mapNotNull { row ->
                            DecisionKind.fromKey(row.kind)?.takeIf { brackets || it != DecisionKind.COUNT_BRACKET }
                                ?.let { DecisionQuestion(it, row.input, row.language) to row.answer }
                        }.toMap()
                    )
                }
            }

    override suspend fun decide(questions: Collection<DecisionQuestion>) {
        if (!flags.isOn(Flag.AI_DECISIONS)) return
        val brackets = flags.isOn(Flag.AI_COUNT_BRACKETS)
        for (question in questions.distinct().filter { brackets || it.kind != DecisionKind.COUNT_BRACKET }) {
            lock.withLock { ask(question) }
        }
    }

    private suspend fun ask(q: DecisionQuestion) {
        val cached = log.guard("readDecision", null as String?) { dao.answer(q.kind.key, q.input, q.language) }
        if (cached != null || !model.supports(q.language)) return
        val replies = (0 until DecisionRule.ASKS).map { n -> model.ask(DecisionPrompts.prompt(q, n)) ?: return }
        val row = AiDecisionEntity(
            kind = q.kind.key, input = q.input, language = q.language,
            answer = DecisionRule.judge(q.kind, replies), updatedAt = clock.now()
        )
        log.guard("saveDecision", Unit) { dao.insert(row) }
    }
}
