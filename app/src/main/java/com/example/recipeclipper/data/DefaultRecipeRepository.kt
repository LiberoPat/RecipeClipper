package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.UrlCleaner
import com.example.recipeclipper.data.remote.RecipeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real, Room-backed [RecipeRepository]. Bound to the interface with `@Binds`.
 *
 * Database failures never escape: each call runs through [ErrorLog.guard], is logged, and
 * degrades to what the contract already allows — `Error(SaveFailed)` from an import, null from
 * [open] and [delete], nothing from [setChecked], [setNotes] and
 * [restore], an empty list from a Flow —
 * rather than crashing `viewModelScope`. The iOS repository does the same.
 */
@Singleton
class DefaultRecipeRepository @Inject constructor(
    private val source: RecipeSource,
    private val recipeDao: RecipeDao,
    private val clock: Clock,
    private val log: ErrorLog
) : RecipeRepository {

    override suspend fun importFromUrl(sharedUrl: String): ParseResult {
        // Saved under the cleaned link, so tracking tags can't make one recipe into two.
        val url = UrlCleaner.clean(sharedUrl)
        val parsed = fetchWithOneRetry(url)
        val now = clock.now()
        return when (parsed) {
            is ParseResult.Success -> log.guard("import save", ParseResult.Error(ParseError.SaveFailed)) {
                val id = recipeDao.upsert(parsed.recipe.toEntity(now), HISTORY_LIMIT)
                recipeDao.get(id)?.let { ParseResult.Success(it.toDomain()) }
                    ?: ParseResult.Error(ParseError.SaveFailed)
            }
            is ParseResult.Error -> {
                // Offline, blocked or anything else: a link opened once still opens.
                val cached = log.guard("offline fallback", null) {
                    recipeDao.findByUrl(url)?.also { recipeDao.touch(it.id, now) }
                } ?: return parsed
                ParseResult.Success(cached.copy(lastViewedAt = now).toDomain())
            }
        }
    }

    /**
     * Fetches, and if that fails in a way that often clears on its own (a block, or a network
     * failure that wasn't a timeout; see [ParseError.shouldAutoRetry]) waits [RETRY_PAUSE_MS]
     * and fetches once more. Offline fails straight away, a timeout isn't repeated (so a dead
     * Wi-Fi costs one timeout, not two), and a page that loaded with no recipe is never
     * retried. The pause is a plain coroutine [delay], so a test's virtual time skips it, and
     * cancelling the import during it throws out of here before anything is written.
     */
    private suspend fun fetchWithOneRetry(url: String): ParseResult {
        val first = source.fetch(url)
        if (first !is ParseResult.Error || !first.error.shouldAutoRetry) return first
        delay(RETRY_PAUSE_MS)
        return source.fetch(url)
    }

    override suspend fun open(id: Long): Recipe? = log.guard("open", null) {
        val entity = recipeDao.get(id) ?: return@guard null
        val now = clock.now()
        recipeDao.touch(id, now)
        entity.copy(lastViewedAt = now).toDomain()
    }

    override suspend fun setChecked(id: Long, checked: Set<Int>) =
        log.guard("setChecked", Unit) { recipeDao.setChecked(id, checked) }

    override suspend fun setNotes(id: Long, notes: String) =
        log.guard("setNotes", Unit) { recipeDao.setNotes(id, notes.takeIf { it.isNotBlank() }) }

    override suspend fun delete(id: Long): RecipeRepository.DeletedRecipe? = log.guard("delete", null) {
        val entity = recipeDao.get(id) ?: return@guard null
        val crossRefs = recipeDao.crossRefsFor(id)
        recipeDao.delete(id)
        RecipeRepository.DeletedRecipe(entity, crossRefs)
    }

    override suspend fun restore(deleted: RecipeRepository.DeletedRecipe) =
        log.guard("restore", Unit) { recipeDao.restore(deleted.entity, deleted.crossRefs) }

    override fun observeHistory(query: String): Flow<List<RecipeSummary>> =
        recipeDao.observeHistory(query).map { rows -> rows.map { it.toDomain() } }.orEmptyOnError(log, "observeHistory")

    override fun observeRecent(limit: Int): Flow<List<RecipeSummary>> =
        recipeDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }.orEmptyOnError(log, "observeRecent")

    companion object {
        /** How long to wait before the single automatic retry. Long enough for a momentary
         *  block to lift, short enough that the spinner doesn't feel stuck. */
        const val RETRY_PAUSE_MS = 2_000L
    }
}
