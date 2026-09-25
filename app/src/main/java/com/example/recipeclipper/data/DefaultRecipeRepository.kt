package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.CookStateJson
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.newUid
import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.ManualRecipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.StepAlarm
import com.example.recipeclipper.data.model.UrlCleaner
import com.example.recipeclipper.data.remote.BlogRecipeSource
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.data.remote.RenderedPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real, Room-backed [RecipeRepository]. Bound to the interface with `@Binds`.
 *
 * Database failures never escape: each call runs through [ErrorLog.guard], is logged, and
 * degrades to what the contract already allows — `Error(SaveFailed)` from an import or a clip, null from
 * [open] and [delete], nothing from [setChecked], [setNotes], [setCookProgress],
 * [setServingsTarget] and [restore], none from [runningTimers], an empty list from a Flow —
 * rather than crashing `viewModelScope`. The iOS repository does the same.
 */
@Singleton
class DefaultRecipeRepository @Inject constructor(
    private val source: RecipeSource,
    private val recipeDao: RecipeDao,
    private val clock: Clock,
    private val log: ErrorLog,
    private val renderedPages: RenderedPageSource = RenderedPageSource.None
) : RecipeRepository {

    override suspend fun importFromUrl(sharedUrl: String): ParseResult {
        // Saved under the cleaned link, so tracking tags can't make one recipe into two.
        val url = UrlCleaner.clean(sharedUrl)
        // The user's version is never refreshed by a re-share (#29): open it without a fetch.
        val usersVersion = log.guard("find user's version", null) {
            recipeDao.findByUrl(url)?.takeIf { it.contentOrigin != RecipeDao.ORIGIN_PARSED }
        }
        if (usersVersion != null) {
            val viewedAt = clock.now()
            return log.guard("open user's version", ParseResult.Error(ParseError.SaveFailed)) {
                recipeDao.upsert(usersVersion.copy(lastViewedAt = viewedAt), HISTORY_LIMIT, today = today(viewedAt))
                ParseResult.Success(usersVersion.copy(lastViewedAt = viewedAt).toDomain())
            }
        }
        val parsed = fetchWithFallbacks(url)
        val now = clock.now()
        return when (parsed) {
            is ParseResult.Success -> log.guard("import save", ParseResult.Error(ParseError.SaveFailed)) {
                val id = recipeDao.upsert(parsed.recipe.toEntity(now), HISTORY_LIMIT, today = today(now))
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

    override suspend fun updateFromSource(id: Long): ParseResult {
        val existing = log.guard("updateFromSource find", null) { recipeDao.get(id) }
            ?: return ParseResult.Error(ParseError.NotSaved)
        if (!existing.toDomain().canUpdateFromSource) return ParseResult.Error(ParseError.NothingToShow)
        val parsed = fetchWithFallbacks(existing.sourceUrl)
        if (parsed !is ParseResult.Success) return parsed
        val now = clock.now()
        // Filed under the saved link, whatever the parse reports, so it lands on this row.
        val fresh = parsed.recipe.copy(sourceUrl = existing.sourceUrl).toEntity(now)
        return log.guard("updateFromSource save", ParseResult.Error(ParseError.SaveFailed)) {
            recipeDao.upsert(fresh, HISTORY_LIMIT, replaceUsersVersion = true, today = today(now))
            recipeDao.get(id)?.let { ParseResult.Success(it.toDomain()) }
                ?: ParseResult.Error(ParseError.SaveFailed)
        }
    }

    override suspend fun saveEdit(id: Long, draft: RecipeDraft): Recipe? {
        if (!draft.isValid) return null
        return log.guard("saveEdit", null) {
            val existing = recipeDao.get(id)?.toDomain() ?: return@guard null
            val edited = draft.applyTo(existing).toEntity(existing.lastViewedAt)
            val saved = recipeDao.saveEdit(id, edited, existing.origin.afterEdit().name, clock.now())
            if (saved) recipeDao.get(id)?.toDomain() else null
        }
    }

    override suspend fun addManual(draft: RecipeDraft): Recipe? {
        if (!draft.isValid) return null
        val now = clock.now()
        val recipe = draft.applyTo(
            Recipe(
                name = "", image = null, ingredients = emptyList(), instructions = emptyList(),
                prepTime = null, cookTime = null, totalTime = null, yield = null,
                sourceUrl = ManualRecipe.newSourceUrl(newUid()),
                origin = ContentOrigin.MANUAL,
                editedAt = now
            )
        )
        return log.guard("addManual", null) {
            val id = recipeDao.upsert(recipe.toEntity(now), HISTORY_LIMIT, today = today(now))
            recipeDao.get(id)?.toDomain()
        }
    }

    /**
     * [fetchWithOneRetry], then, only if that still ends [ParseError.Blocked] or
     * [ParseError.NoRecipeFound] (see [ParseError.triesRenderedPage]), one load of the page in an
     * off-screen browser, capped at [RENDER_TIMEOUT_MS], its HTML run through the same parsers.
     * Never after Offline or a timeout. A rendered page that still has no recipe, or that
     * doesn't load, leaves the direct fetch's cause standing: a block stays a block. Nothing
     * is shown for it; the import is just slower. Cancelling during it throws out of here
     * before anything is written.
     */
    private suspend fun fetchWithFallbacks(url: String): ParseResult {
        val fetched = fetchWithOneRetry(url)
        if (fetched !is ParseResult.Error || !fetched.error.triesRenderedPage) return fetched
        val html = withTimeoutOrNull(RENDER_TIMEOUT_MS) { renderedPages.render(url) } ?: return fetched
        val rendered = withContext(Dispatchers.Default) { BlogRecipeSource.parse(html, url) }
        return if (rendered is ParseResult.Success) rendered else fetched
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

    override suspend fun saveClip(recipe: Recipe): ParseResult =
        log.guard("saveClip", ParseResult.Error(ParseError.SaveFailed)) {
            // CLIPPED, so a re-share opens the clip rather than fetching the page again (#29's
            // rule); a clip replaces whatever the row held, as "Update from source" does.
            val clip = recipe.copy(
                sourceUrl = UrlCleaner.clean(recipe.sourceUrl),
                origin = ContentOrigin.CLIPPED,
                editedAt = null
            )
            val id = recipeDao.upsert(clip.toEntity(clock.now()), HISTORY_LIMIT, replaceUsersVersion = true)
            recipeDao.get(id)?.let { ParseResult.Success(it.toDomain()) }
                ?: ParseResult.Error(ParseError.SaveFailed)
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

    override suspend fun setCookProgress(id: Long, progress: CookProgress) =
        log.guard("setCookProgress", Unit) { recipeDao.setCookState(id, CookStateJson.encode(progress)) }

    override suspend fun setServingsTarget(id: Long, target: Int?) =
        log.guard("setServingsTarget", Unit) { recipeDao.setServingsTarget(id, target) }

    override suspend fun runningTimers(): List<StepAlarm> = log.guard("runningTimers", emptyList()) {
        recipeDao.cookStates().flatMap { row ->
            CookStateJson.decode(row.cookState).timers.mapNotNull { (step, timer) ->
                timer.endsAt?.let { StepAlarm(row.id, row.title, step, it) }
            }
        }
    }

    override suspend fun delete(id: Long): RecipeRepository.DeletedRecipe? = log.guard("delete", null) {
        val entity = recipeDao.get(id) ?: return@guard null
        val crossRefs = recipeDao.crossRefsFor(id)
        val planEntries = recipeDao.planEntriesFor(id)
        recipeDao.delete(id)
        RecipeRepository.DeletedRecipe(entity, crossRefs, planEntries)
    }

    override suspend fun restore(deleted: RecipeRepository.DeletedRecipe) =
        log.guard("restore", Unit) { recipeDao.restore(deleted.entity, deleted.crossRefs, deleted.planEntries) }

    override fun observeHistory(query: String): Flow<List<RecipeSummary>> =
        recipeDao.observeHistory(query).map { rows -> rows.map { it.toDomain() } }.orEmptyOnError(log, "observeHistory")

    override fun observeRecent(limit: Int): Flow<List<RecipeSummary>> =
        recipeDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }.orEmptyOnError(log, "observeRecent")

    /** Today on the user's calendar: a recipe planned for it or later is never culled (#49). */
    private fun today(now: Long): Long = PlanDays.today(now)

    companion object {
        /** How long to wait before the single automatic retry. Long enough for a momentary
         *  block to lift, short enough that the spinner doesn't feel stuck. */
        const val RETRY_PAUSE_MS = 2_000L

        /** The cap on the off-screen browser fallback, load and settle together. It comes on
         *  top of the direct fetch and its retry, so a page that never settles can't hold the
         *  spinner much longer than they did. */
        const val RENDER_TIMEOUT_MS = 20_000L
    }
}
