package com.example.recipeclipper.ui.recipe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The recipe screen's one write queue (#10, #234): cook progress and the chosen servings, run
 * one at a time in the order they were made, so two quick taps can never land out of order and
 * leave the older state saved. A plain queue rather than a Channel: a Channel drops an element
 * handed to a receiver that is cancelled before it runs, which is exactly the tap made just
 * before leaving the screen. Main thread only, on [scope] (the ViewModel's).
 */
class OrderedWrites(private val scope: CoroutineScope) {

    private val pending = ArrayDeque<suspend () -> Unit>()
    private var writer: Job? = null

    /** Queues [write] behind every write made before it. */
    fun enqueue(write: suspend () -> Unit) {
        pending.addLast(write)
        if (writer?.isActive != true) writer = scope.launch { drain() }
    }

    /**
     * The screen was left and [scope] is cancelled by now. A writer that had started finishes
     * the queue itself; one that never got to run is replaced here, after it, so order is kept.
     */
    fun finishAfterClose() {
        if (pending.isEmpty()) return
        val previous = writer
        CoroutineScope(Dispatchers.Unconfined).launch {
            previous?.join()
            drain()
        }
    }

    // Each write runs NonCancellable, and nothing between them suspends cancellably, so once
    // started this empties the queue even if the screen is left.
    private suspend fun drain() {
        while (pending.isNotEmpty()) {
            val write = pending.removeFirst()
            withContext(NonCancellable) { write() }
        }
    }
}
