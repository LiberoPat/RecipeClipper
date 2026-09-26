package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.ShortStepDao
import com.example.recipeclipper.data.local.entity.ShortStepEntity
import com.example.recipeclipper.data.model.LanguageWords
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.ShortStepCheck
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Chef mode's short steps (#100): written on the device, checked, and cached. */
interface ShortStepRepository {

    suspend fun support(): ChefSupport

    /** [recipe]'s saved short steps, one per step (null: show it as written), then every change. */
    fun observe(recipe: Recipe): Flow<List<String?>>

    /**
     * Writes the short steps [recipe] is missing, one at a time in order, each saved (so
     * observed) once [ShortStepCheck] has judged it, and drops rows for steps it no longer has.
     * A step the model can't do right now is asked again next time; one it failed is not.
     */
    suspend fun fill(recipe: Recipe)
}

class DefaultShortStepRepository @Inject constructor(
    private val dao: ShortStepDao,
    private val shortener: StepShortener,
    private val clock: Clock,
    private val log: ErrorLog
) : ShortStepRepository {

    override suspend fun support(): ChefSupport = shortener.support()

    override fun observe(recipe: Recipe): Flow<List<String?>> {
        val none = recipe.instructions.map { null }
        val language = LanguageWords.forRecipe(recipe)?.language ?: return flowOf(none)
        return dao.observe(recipe.id, language)
            .map { rows ->
                val byHash = rows.associate { it.stepHash to it.shortText }
                recipe.instructions.map { byHash[stepHash(it)] }
            }
            .catch { e ->
                log.error("Reading short steps failed", e)
                emit(none)
            }
    }

    override suspend fun fill(recipe: Recipe) {
        val words = LanguageWords.forRecipe(recipe) ?: return
        val language = words.language
        val hashes = recipe.instructions.map(::stepHash)
        log.guard("Pruning short steps", Unit) { dao.prune(recipe.id, language, hashes) }
        val done = log.guard("Reading short steps", null) {
            dao.observe(recipe.id, language).first().mapTo(mutableSetOf()) { it.stepHash }
        } ?: return
        recipe.instructions.forEachIndexed { i, step ->
            if (hashes[i] in done || !ShortStepCheck.worthShortening(step)) return@forEachIndexed
            val written = shortener.shorten(step, language) ?: return@forEachIndexed
            val row = ShortStepEntity(
                recipeId = recipe.id,
                stepHash = hashes[i],
                language = language,
                shortText = ShortStepCheck.accept(step, written, words),
                updatedAt = clock.now()
            )
            log.guard("Saving a short step", Unit) { dao.insert(row) }
            done += hashes[i]
        }
    }

    companion object {
        /** The step's text as stored, as SHA-256 hex: what a cached short step is keyed by. */
        fun stepHash(step: String): String =
            MessageDigest.getInstance("SHA-256").digest(step.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
