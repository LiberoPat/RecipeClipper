package com.example.recipeclipper.fake

import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.ParseError
import com.example.recipeclipper.data.model.ParseResult
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.data.model.StepAlarm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A hand-written fake, not a mock: a recording fake reads better in a failure message than a
 * verification DSL. Backs history and recent with in-memory `MutableStateFlow`s so
 * a test can push a list and watch a ViewModel react; [importFromUrl] and [open] return
 * whatever the test stages; [setChecked], [setNotes], [setCookProgress], [setServingsTarget], [delete] and [restore]
 * record every call so tests
 * can assert on them.
 */
class FakeRecipeRepository : RecipeRepository {

    /** What [observeHistory] emits, regardless of the query passed — tests push onto this. */
    val history = MutableStateFlow<List<RecipeSummary>>(emptyList())

    /** What [observeRecent] emits. */
    val recent = MutableStateFlow<List<RecipeSummary>>(emptyList())

    /** Every query [observeHistory] was called with, in order — for the debounce test. */
    val historyQueries = mutableListOf<String>()

    /** Staged answer for [importFromUrl]. */
    var importResult: ParseResult = ParseResult.Error(ParseError.NothingToShow)

    /** Staged answer for [open]. */
    var openResult: Recipe? = null

    /** Staged per-id answers for [delete]; missing ids answer null, same as "already gone". */
    val deleteResults = mutableMapOf<Long, RecipeRepository.DeletedRecipe>()

    /** [id] to [checked] for every [setChecked] call, in order. */
    val setCheckedCalls = mutableListOf<Pair<Long, Set<Int>>>()

    /** [id] to [notes] for every [setNotes] call, in order. */
    val setNotesCalls = mutableListOf<Pair<Long, String>>()

    /** [id] to progress for every [setCookProgress] call, in order. */
    val setCookProgressCalls = mutableListOf<Pair<Long, CookProgress>>()

    /** [id] to target for every [setServingsTarget] call, in order. */
    val setServingsTargetCalls = mutableListOf<Pair<Long, Int?>>()

    /** Staged answer for [runningTimers]. */
    var runningTimersResult: List<StepAlarm> = emptyList()

    /** Every id [delete] was called with, in order. */
    val deleteCalls = mutableListOf<Long>()

    /** Every [RecipeRepository.DeletedRecipe] [restore] was called with, in order. */
    val restoreCalls = mutableListOf<RecipeRepository.DeletedRecipe>()

    /** How many times [importFromUrl] has been called — for the reload-on-reconnect tests. */
    var importCalls = 0
        private set

    override suspend fun importFromUrl(sharedUrl: String): ParseResult {
        importCalls++
        return importResult
    }

    override suspend fun open(id: Long): Recipe? = openResult

    override suspend fun setChecked(id: Long, checked: Set<Int>) {
        setCheckedCalls += id to checked
    }

    override suspend fun setNotes(id: Long, notes: String) {
        setNotesCalls += id to notes
    }

    override suspend fun setCookProgress(id: Long, progress: CookProgress) {
        setCookProgressCalls += id to progress
    }

    override suspend fun setServingsTarget(id: Long, target: Int?) {
        setServingsTargetCalls += id to target
    }

    override suspend fun runningTimers(): List<StepAlarm> = runningTimersResult

    override suspend fun delete(id: Long): RecipeRepository.DeletedRecipe? {
        deleteCalls += id
        return deleteResults[id]
    }

    override suspend fun restore(deleted: RecipeRepository.DeletedRecipe) {
        restoreCalls += deleted
    }

    override fun observeHistory(query: String): Flow<List<RecipeSummary>> {
        historyQueries += query
        return history
    }

    /**
     * Honours [limit], like the SQL `LIMIT` it stands in for. Without this a test could push
     * 20 recipes and see all 20 reach the screen, "proving" a cap that isn't there.
     */
    override fun observeRecent(limit: Int): Flow<List<RecipeSummary>> =
        recent.map { it.take(limit) }
}
