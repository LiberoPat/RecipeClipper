package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.RecipeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The reading view's own writes (#10, #234): ingredient ticks, written as they change so closing
 * the app mid-cook doesn't lose them, and the user's note, written once typing pauses for
 * [saveDelayMs] (a sentence is one write rather than one per letter) or when the screen is left.
 * Main thread only, on [scope] (the ViewModel's).
 */
class NotesAndTicks(
    private val scope: CoroutineScope,
    private val repository: RecipeRepository,
    private val saveDelayMs: Long
) {

    // The note as typed but not yet written, and the debounced write that will save it.
    private var pendingNotes: String? = null
    private var notesJob: Job? = null

    /** Recipe [id]'s ticked ingredient lines are now [checked]. */
    fun ticked(id: Long, checked: Set<Int>) {
        scope.launch { repository.setChecked(id, checked) }
    }

    /** Recipe [id]'s note is now [text]: written once typing has paused. */
    fun noteChanged(id: Long, text: String) {
        pendingNotes = text
        notesJob?.cancel()
        notesJob = scope.launch {
            delay(saveDelayMs)
            flushNotes(id)
        }
    }

    /**
     * The screen was left and [scope] is cancelled by now, so a note typed just before leaving
     * is written to recipe [id] on a scope of its own. One short write that nothing needs to
     * wait for; nothing when [id] is null or no note is waiting.
     */
    fun flushAfterClose(id: Long?) {
        if (id != null && pendingNotes != null) {
            CoroutineScope(Dispatchers.Unconfined).launch { flushNotes(id) }
        }
    }

    private suspend fun flushNotes(id: Long) {
        val text = pendingNotes ?: return
        pendingNotes = null
        // Once taken off pendingNotes it must land: leaving the screen mid-write can't drop it.
        withContext(NonCancellable) { repository.setNotes(id, text) }
    }
}
