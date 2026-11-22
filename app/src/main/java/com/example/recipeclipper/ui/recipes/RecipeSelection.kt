package com.example.recipeclipper.ui.recipes

import com.example.recipeclipper.data.model.RecipeSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Selecting several recipes (#377), shared by Recipes (and its views) and a list's detail: whether
 * the screen is selecting, and the ids ticked. Held by the ViewModel, not `remember`, so a rotation
 * keeps both. [chosen] is null while not selecting; selecting with nothing ticked is a real state
 * (the top bar's Select starts it empty).
 *
 * What the actions act on is [effective], never [chosen] itself: a search that narrows the rows can
 * leave a ticked recipe out of sight, and nothing hidden is deleted or moved (the Pantry's rule,
 * #258).
 */
class RecipeSelection {
    private val ids = MutableStateFlow<Set<Long>?>(null)
    val chosen: StateFlow<Set<Long>?> = ids

    /** Starts selecting, with [id] ticked when a row's menu started it. */
    fun start(id: Long? = null) {
        ids.update { (it ?: emptySet()) + listOfNotNull(id) }
    }

    /** A tap on a row while selecting; ignored when not selecting. */
    fun toggle(id: Long) {
        ids.update { chosen -> chosen?.let { if (id in it) it - id else it + id } }
    }

    /** Select all (#449): ticks [all] as well, while selecting; ignored when not selecting. */
    fun tickAll(all: Collection<Long>) {
        ids.update { chosen -> chosen?.plus(all) }
    }

    fun exit() {
        ids.value = null
    }

    companion object {
        /** The ticked ids that are among the [listed] rows: what an action acts on. */
        fun effective(chosen: Set<Long>?, listed: List<RecipeSummary>?): Set<Long> {
            if (chosen.isNullOrEmpty() || listed == null) return emptySet()
            return listed.mapNotNullTo(LinkedHashSet()) { it.id.takeIf { id -> id in chosen } }
        }
    }
}
