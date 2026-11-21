package com.example.recipeclipper.ui.recipes

import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.TimerCountdownSync
import com.example.recipeclipper.data.model.RecipeSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Recipes deleted but not yet settled, and the one Undo they share (#102, #268, #345, #377). Used by
 * Recipes and by a list's detail: a swipe, a row's menu and a selection's Delete all end up here, so
 * a burst of them, however it was made, is one snackbar and one all-or-nothing undo.
 *
 * [delete] removes every recipe given in one transaction (a selection is all or none) and captures
 * what it removed; [undo] puts the whole burst back through `restoreAll`, which refuses it on the
 * free tier when it would take the library past its limit (#345); [dismissed] lets the deletes stand.
 * State lives here, in the ViewModel's scope, so it survives rotation.
 */
class RecipeDeletions(
    private val repository: RecipeRepository,
    /** The countdowns outside the app (#309), squared with the database after a delete (#333). */
    private val countdowns: TimerCountdownSync?,
    private val scope: CoroutineScope
) {
    /** The burst being deleted: its [batch], null when nothing is pending, and its titles. */
    data class Pending(val batch: Int? = null, val titles: List<String> = emptyList())

    private val _pending = MutableStateFlow(Pending())
    val pending: StateFlow<Pending> = _pending

    private val _restoreRefused = MutableStateFlow(false)

    /** An Undo was refused, the free library being full (#345): the screen says so once, then clears it. */
    val restoreRefused: StateFlow<Boolean> = _restoreRefused

    // What the deletes captured, keyed by recipe id so a second burst within the snackbar's few
    // seconds cannot overwrite the first and strand it. A plain field: the ViewModel survives rotation.
    private val captured = LinkedHashMap<Long, RecipeRepository.DeletedRecipe>()
    private var batches = 0

    /** Deletes [recipes] now, keeping what went so [undo] can bring it back. */
    fun delete(recipes: List<RecipeSummary>) {
        if (recipes.isEmpty()) return
        scope.launch {
            val removed = repository.deleteAll(recipes.map { it.id })
            if (removed.isEmpty()) return@launch
            // Their countdowns go with them; Undo brings the timer back when the recipe is opened.
            countdowns?.reconcile()
            val titles = removed.map { deleted ->
                captured[deleted.entity.id] = deleted
                recipes.firstOrNull { it.id == deleted.entity.id }?.title ?: deleted.entity.title
            }
            // The first of a burst starts a new batch; the rest join it.
            _pending.update { Pending(it.batch ?: ++batches, it.titles + titles) }
        }
    }

    /**
     * Restores everything still pending, oldest first so ids go back in the order they left. All or
     * nothing: one snackbar covers the burst, so there is no way to pick one out of it. On the free
     * tier at its limit (#345) none comes back: [restoreRefused].
     */
    fun undo() {
        if (captured.isEmpty()) return
        val removed = captured.values.toList()
        captured.clear()
        _pending.value = Pending()
        scope.launch {
            // The free library's limit is checked in the repository's transaction (#345): a burst
            // that wouldn't fit restores none, and the deletes stand, photo files included.
            when (repository.restoreAll(removed)) {
                RecipeRepository.RestoreOutcome.LIBRARY_FULL -> {
                    _restoreRefused.value = true
                    removed.forEach { repository.forget(it) }
                }
                RecipeRepository.RestoreOutcome.RESTORED, RecipeRepository.RestoreOutcome.FAILED -> Unit
            }
        }
    }

    /** The screen has said the Undo was refused. */
    fun refusedShown() {
        _restoreRefused.value = false
    }

    /** The snackbar timed out or was swiped away: the deletes stand. */
    fun dismissed() {
        val settled = captured.values.toList()
        captured.clear()
        _pending.value = Pending()
        // Their photos (#116) were kept only for Undo.
        if (settled.isNotEmpty()) scope.launch { settled.forEach { repository.forget(it) } }
    }
}
