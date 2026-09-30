package com.example.recipeclipper.data

import com.example.recipeclipper.data.local.CookStateJson
import com.example.recipeclipper.data.local.dao.RecipeDao
import com.example.recipeclipper.data.local.entity.newUid
import com.example.recipeclipper.data.model.ContentOrigin
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.LibraryLimit
import com.example.recipeclipper.data.model.ManualRecipe
import com.example.recipeclipper.data.model.RecipeDraft
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.SampleRecipe
import com.example.recipeclipper.data.model.StepAlarm
import com.example.recipeclipper.data.model.UrlCleaner
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.model.PageText
import com.example.recipeclipper.data.model.RecipeTextWindow
import com.example.recipeclipper.data.remote.BlogRecipeSource
import com.example.recipeclipper.data.remote.CloudflareChallenge
import com.example.recipeclipper.data.remote.FetchedPage
import com.example.recipeclipper.data.remote.PageRecipe
import com.example.recipeclipper.data.remote.RecipeSource
import com.example.recipeclipper.data.remote.RenderedPageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicBoolean
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
    private val renderedPages: RenderedPageSource = RenderedPageSource.None,
    private val library: LibraryPolicy = LibraryPolicy.HistoryOnly,
    private val extractor: PageRecipeExtractor = PageRecipeExtractor.None,
    /** Null (tests that aren't about it) reads as every flag off. */
    private val flags: FeatureFlags? = null,
    private val photos: PhotoStore = NoPhotoStore,
    private val clearedHosts: ClearedHosts = ClearedHosts.None
) : RecipeRepository {

    override suspend fun importFromUrl(sharedUrl: String): ParseResult {
        // Saved under the cleaned link, so tracking tags can't make one recipe into two.
        val url = UrlCleaner.clean(sharedUrl)
        // The user's version is never refreshed by a re-share (#29): open it without a fetch.
        val usersVersion = log.guard("find user's version", null) {
            recipeDao.findByUrl(url)?.takeIf { !ContentOrigin.isSources(it.contentOrigin) }
        }
        if (usersVersion != null) {
            val viewedAt = clock.now()
            return log.guard("open user's version", ParseResult.Error(ParseError.SaveFailed)) {
                recipeDao.upsert(usersVersion.copy(lastViewedAt = viewedAt), library.limit, today = today(viewedAt))
                ParseResult.Success(usersVersion.copy(lastViewedAt = viewedAt).toDomain())
            }
        }
        val parsed = fetchWithFallbacks(url)
        val now = clock.now()
        return when (parsed) {
            is ParseResult.Success -> log.guard("import save", ParseResult.Error(ParseError.SaveFailed)) {
                save(parsed.recipe, now)
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
            recipeDao.upsert(fresh, library.limit, replaceUsersVersion = true, today = today(now))
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
            val id = recipeDao.upsert(recipe.toEntity(now), library.limit, today = today(now))
            if (id == RecipeDao.NOT_KEPT) recipe else recipeDao.get(id)?.toDomain()
        }
    }

    /**
     * [fetchWithOneRetry], then, only if that still ends [ParseError.Blocked] or
     * [ParseError.NoRecipeFound] (see [ParseError.triesRenderedPage]), one load of the page in an
     * off-screen browser ([renderPage]), its HTML run through the same parsers. Never after
     * Offline or a timeout. A rendered page that still has no recipe, or that doesn't load,
     * leaves the direct fetch's cause standing: a block stays a block. Nothing is shown for it;
     * the import is just slower. Cancelling during it throws out of here before anything is
     * written.
     *
     * Cloudflare's check (#220) changes that: a render that met it and got past it is the page
     * itself, so its answer stands (the recipe, or [ParseError.NoRecipeFound]); one still on the
     * check when time runs out is [ParseError.HumanCheck], for the cook to pass in the app's
     * visible browser. A host that passed lately ([ClearedHosts]) is rendered first, since its
     * plain fetch would only be refused.
     *
     * Last, only when the page loaded with no recipe data ([ParseError.NoRecipeFound]), the
     * on-device model may pick one out of its text ([extractFromPage]; the rendered page's
     * text if there is one).
     */
    private suspend fun fetchWithFallbacks(url: String): ParseResult {
        // A Reddit post (#11) is read only from its listing: no rendered page, no page text.
        val readsPages = source.readsRenderedPage(url)
        val host = hostOf(url)
        val cleared = readsPages && host != null && clearedHosts.isCleared(host)
        var rendered: Rendered? = null
        if (cleared) {
            rendered = renderPage(url, host, trusted = true)
            rendered.answer(url)?.let { return it }
        }
        val fetched = fetchWithOneRetry(url)
        val result = fetched.result
        if (result !is ParseResult.Error || !result.error.triesRenderedPage) return result
        if (!readsPages) return result
        if (rendered == null) {
            rendered = renderPage(url, host, trusted = false)
            rendered.answer(url)?.let { return it }
        }
        if (result.error != ParseError.NoRecipeFound) return result
        val page = rendered.page?.page ?: fetched.page ?: return result
        return extractFromPage(page, url) ?: result
    }

    /**
     * What one render came to. [page] is the rendered page parsed, when the browser handed one
     * back that isn't Cloudflare's check; [challenged], whether the check showed during it;
     * [trusted], whether its page stands on its own (it went there on a clearance).
     */
    private inner class Rendered(val page: FetchedPage?, val challenged: Boolean, val trusted: Boolean) {
        /**
         * The import's answer from this render, or null to go on as before (#36): its recipe;
         * [ParseError.HumanCheck] if the check still showed at the cap; the page's own
         * [ParseError.NoRecipeFound] (after the on-device model's try) if it passed the check or
         * went there on a clearance.
         */
        suspend fun answer(url: String): ParseResult? {
            val result = page?.result
            return when {
                result is ParseResult.Success -> result
                page == null -> if (challenged) ParseResult.Error(ParseError.HumanCheck) else null
                challenged || trusted -> {
                    val noRecipe = ParseResult.Error(ParseError.NoRecipeFound)
                    page.page?.let { extractFromPage(it, url) } ?: noRecipe
                }
                else -> null
            }
        }
    }

    /**
     * One load of [url] in the off-screen browser, capped at [RENDER_TIMEOUT_MS], or at
     * [CHALLENGE_TIMEOUT_MS] in all once Cloudflare's check has shown (#220): a check that
     * passes by itself takes a few seconds, and reads the page it moves on to. Its host is
     * remembered ([ClearedHosts]) when a page loads past the check, or on a clearance.
     */
    private suspend fun renderPage(url: String, host: String?, trusted: Boolean): Rendered {
        val challenged = AtomicBoolean(false)
        val html = coroutineScope {
            val render = async { renderedPages.render(url) { challenged.set(true) } }
            val html = withTimeoutOrNull(RENDER_TIMEOUT_MS) { render.await() }
                ?: if (challenged.get() && render.isActive) {
                    withTimeoutOrNull(CHALLENGE_TIMEOUT_MS - RENDER_TIMEOUT_MS) { render.await() }
                } else {
                    null
                }
            render.cancel()
            html
        }
        val page = html?.let { withContext(Dispatchers.Default) { BlogRecipeSource.parsePage(it, url) } }
        // A browser that handed back the check itself hasn't got past it either.
        if (html != null && page?.result !is ParseResult.Success && CloudflareChallenge.isChallengePage(html)) {
            return Rendered(null, challenged = true, trusted = trusted)
        }
        if (page != null && host != null && (challenged.get() || trusted)) clearedHosts.record(host)
        return Rendered(page, challenged.get(), trusted)
    }

    override suspend fun importPage(sharedUrl: String, html: String): ParseResult {
        val url = UrlCleaner.clean(sharedUrl)
        val parsed = withContext(Dispatchers.Default) { BlogRecipeSource.parsePage(html, url) }.result
        if (parsed !is ParseResult.Success && CloudflareChallenge.isChallengePage(html)) {
            return ParseResult.Error(ParseError.HumanCheck)
        }
        hostOf(url)?.let(clearedHosts::record)
        if (parsed !is ParseResult.Success) return parsed
        return log.guard("importPage save", ParseResult.Error(ParseError.SaveFailed)) {
            save(parsed.recipe.copy(sourceUrl = url), clock.now())
        }
    }

    private fun hostOf(url: String): String? =
        try { URI(url).host?.lowercase() } catch (e: URISyntaxException) { null }

    /**
     * A recipe the on-device model picked out of [page]'s text (#103), behind the
     * `llmExtraction` flag: the part of the page most likely to hold it ([RecipeTextWindow]),
     * then only what [PageRecipe.recipe] finds on the page as written. Null, and the page stays
     * [ParseError.NoRecipeFound] as before, on a phone or in a language the model can't read,
     * or when too little of what it picked is on the page.
     */
    private suspend fun extractFromPage(page: PageText, url: String): ParseResult.Success? {
        if (flags?.isOn(Flag.LLM_EXTRACTION) != true) return null
        val language = withContext(Dispatchers.Default) { PageRecipe.language(page) }
        val chars = extractor.windowChars(language) ?: return null
        val window = withContext(Dispatchers.Default) { RecipeTextWindow.window(page, chars) } ?: return null
        val picked = extractor.extract(window, language) ?: return null
        val recipe = withContext(Dispatchers.Default) { PageRecipe.recipe(window, picked, page, url) }
        return recipe?.let { ParseResult.Success(it) }
    }

    /**
     * Fetches, and if that fails in a way that often clears on its own (a block, or a network
     * failure that wasn't a timeout; see [ParseError.shouldAutoRetry]) waits [RETRY_PAUSE_MS]
     * and fetches once more. Offline fails straight away, a timeout isn't repeated (so a dead
     * Wi-Fi costs one timeout, not two), and a page that loaded with no recipe is never
     * retried. The pause is a plain coroutine [delay], so a test's virtual time skips it, and
     * cancelling the import during it throws out of here before anything is written.
     */
    private suspend fun fetchWithOneRetry(url: String): FetchedPage {
        val first = source.fetchPage(url)
        val error = (first.result as? ParseResult.Error)?.error
        // Cloudflare's check (#220) refuses a plain fetch every time: straight on to the browser.
        if (error == null || !error.shouldAutoRetry || first.challenge) return first
        delay(RETRY_PAUSE_MS)
        return source.fetchPage(url)
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
            val now = clock.now()
            val id = recipeDao.upsert(clip.toEntity(now), library.limit, replaceUsersVersion = true, today = today(now))
            saved(id, clip)
        }

    override suspend fun keep(recipe: Recipe): ParseResult =
        log.guard("keep", ParseResult.Error(ParseError.SaveFailed)) { save(recipe, clock.now()) }

    /** Saves a parsed recipe as a view at [now], under the library's limit (#107). */
    private suspend fun save(recipe: Recipe, now: Long): ParseResult =
        saved(recipeDao.upsert(recipe.toEntity(now), library.limit, today = today(now)), recipe)

    /** The row [id] as saved, or [shown] not kept when the full library had no room. */
    private suspend fun saved(id: Long, shown: Recipe): ParseResult =
        if (id == RecipeDao.NOT_KEPT) {
            ParseResult.Success(shown.copy(id = 0), kept = false)
        } else {
            recipeDao.get(id)?.let { ParseResult.Success(it.toDomain()) }
                ?: ParseResult.Error(ParseError.SaveFailed)
        }

    override suspend fun sampleId(): Long? = log.guard("sampleId", null) {
        recipeDao.findByUrl(SampleRecipe.SOURCE_URL)?.id
    }

    override suspend fun addSample(recipe: Recipe): Long? = log.guard("addSample", null) {
        val now = clock.now()
        val sample = recipe.copy(sourceUrl = SampleRecipe.SOURCE_URL, origin = ContentOrigin.MANUAL)
        // Unlimited: the sample counts toward no limit, so adding it never removes a recipe.
        recipeDao.upsert(sample.toEntity(now), LibraryLimit.Unlimited).takeIf { it != RecipeDao.NOT_KEPT }
    }

    override suspend fun formatSampleTimes() = log.guard("formatSampleTimes", Unit) {
        val sample = recipeDao.findByUrl(SampleRecipe.SOURCE_URL)?.takeIf { it.editedAt == null } ?: return@guard
        val times = listOf(sample.prepTime, sample.cookTime, sample.totalTime)
        val formatted = times.map { SampleRecipe.formatTime(it, sample.language) }
        if (formatted != times) recipeDao.setTimes(sample.id, formatted[0], formatted[1], formatted[2])
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
        val menuEntries = recipeDao.menuEntriesFor(id)
        val cookedPhotos = recipeDao.cookedPhotosFor(id)
        recipeDao.delete(id)
        RecipeRepository.DeletedRecipe(entity, crossRefs, planEntries, menuEntries, cookedPhotos)
    }

    override suspend fun restore(deleted: RecipeRepository.DeletedRecipe) =
        log.guard("restore", Unit) { recipeDao.restore(deleted.entity, deleted.crossRefs, deleted.planEntries, deleted.menuEntries, deleted.cookedPhotos) }

    // The photos belong to the recipe (#116): once its delete stands, their files go too.
    override suspend fun forget(deleted: RecipeRepository.DeletedRecipe) {
        val files = deleted.cookedPhotos.mapNotNull { it.fileName }
        if (files.isNotEmpty()) photos.delete(files)
    }

    override fun observeHistory(query: String): Flow<List<RecipeSummary>> =
        recipeDao.observeHistory(query).map { rows -> rows.map { it.toDomain() } }.orEmptyOnError(log, "observeHistory")

    override fun observeRecent(limit: Int): Flow<List<RecipeSummary>> =
        recipeDao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }.orEmptyOnError(log, "observeRecent")

    override fun observeCount(): Flow<Int> = recipeDao.observeCount().catch { e ->
        log.error("observeCount failed", e)
        emit(0)
    }

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

        /** The render's cap in all once Cloudflare's check has shown (#220): long enough for a
         *  check that passes by itself, and the page it then loads, to settle. */
        const val CHALLENGE_TIMEOUT_MS = 30_000L
    }
}
