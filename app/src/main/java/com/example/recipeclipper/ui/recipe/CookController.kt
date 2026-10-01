package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.TimerAlarmScheduler
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.StepAlarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cook mode's effects around [CookSession] (#10, #234): the alarms its timers need, the one tick
 * loop for every running timer, and saving the cook's place, on every action, through the
 * screen's one write queue ([writes]). The ViewModel still owns the state: [cook] reads the
 * [CookState] on screen and [onCook] writes the next one; [content] is the recipe on screen,
 * null while loading or showing an error. Main thread only, on [scope] (the ViewModel's).
 */
class CookController(
    clock: Clock,
    private val alarms: TimerAlarmScheduler,
    private val scope: CoroutineScope,
    private val writes: OrderedWrites,
    private val repository: RecipeRepository,
    private val content: () -> RecipeContent.Success?,
    private val cook: () -> CookState,
    private val onCook: (CookState) -> Unit
) {

    // Cook mode's steps and timers; this keeps only the tick loop and the alarm calls.
    private val session = CookSession(clock)
    private var tickJob: Job? = null

    /** Picks up where the cook left off ([CookSession.restore]), rescheduling running timers' alarms. */
    fun restore(recipe: Recipe) {
        val restored = session.restore(recipe)
        restored.alarms.forEach(alarms::schedule)
        onCook(restored.cook)
        if (session.hasRunningTimers) ensureTicking()
    }

    /**
     * Stops every running timer and cancels its alarm on recipe [recipeId]: the recipe was
     * deleted (its timers must not ring later), or its steps may have changed.
     */
    fun stopTimers(recipeId: Long) {
        session.stopTimers().forEach { alarms.cancel(recipeId, it) }
    }

    fun start() {
        val loaded = content() ?: return
        val start = session.start(cook(), loaded.instructions.size)
        // A finished run starts fresh, and its timers go with it, so their alarms must too.
        start.stopped.forEach { alarms.cancel(loaded.recipe.id, it) }
        start.cook?.let(onCook)
        save()
    }

    fun exit() {
        onCook(session.exit(cook()))
        save()
    }

    fun toggleIngredients() = onCook(session.toggleIngredients(cook()))

    /** Tapping a step makes it current. Only [done] ever advances or marks progress. */
    fun select(step: Int) {
        onCook(session.select(cook(), step))
        save()
    }

    /**
     * "Done — next step" on [current]: the cook after it, whether that was the last step left,
     * and then what was ticked in [checked] (#147), as [content] shows it, for the pantry's
     * use-up sheet; null when nothing was. The ViewModel writes it and then calls [save].
     */
    data class Done(val cook: CookState, val finished: Boolean, val finishedCook: FinishedCook?)

    fun done(current: CookState, content: RecipeContent.Success, checked: Set<Int>): Done {
        val done = session.done(current, content.instructions.size)
        // A finished run finishes again only after starting fresh, so once per cook.
        val finishedCook = if (done.finished) session.finishedCook(content, checked) else null
        return Done(done.cook, done.finished, finishedCook)
    }

    // --- Step timers. Several can run at once, since steps overlap. ---

    fun startTimer(step: Int) {
        val loaded = content() ?: return
        val total = loaded.stepTimerSeconds.getOrNull(step) ?: return
        applyTimer(session.startTimer(cook(), step, total), loaded.recipe, step)
    }

    fun toggleTimer(step: Int) {
        val recipe = content()?.recipe ?: return
        val change = session.toggleTimer(cook(), step) ?: return
        applyTimer(change, recipe, step)
    }

    // Shows a timer's change, schedules or cancels its alarm, and saves.
    private fun applyTimer(change: CookSession.TimerChange, recipe: Recipe, step: Int) {
        onCook(change.cook)
        val endsAt = change.endsAt
        if (endsAt != null) {
            alarms.schedule(StepAlarm(recipe.id, recipe.name, step, endsAt))
            ensureTicking()
        } else {
            alarms.cancel(recipe.id, step)
        }
        save()
    }

    fun resetTimer(step: Int) {
        val next = session.resetTimer(cook(), step) ?: return
        onCook(next)
        content()?.recipe?.id?.let { alarms.cancel(it, step) }
        save()
    }

    fun alerted(step: Int) {
        session.alerted(cook(), step)?.let(onCook)
    }

    /** Saves the cook's place as it now stands ([CookSession.progress]), behind every earlier write. */
    fun save() {
        val id = content()?.recipe?.id ?: return
        val progress = session.progress(cook())
        writes.enqueue { repository.setCookProgress(id, progress) }
    }

    // One loop for every running timer, ending by itself once none is; each tick recomputes
    // the remaining time from the wall clock ([CookSession.tick]) and writes nothing.
    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (session.hasRunningTimers) {
                delay(TICK_MS)
                session.tick(cook())?.let(onCook)
            }
        }
    }

    /** The screen was left: the timers stop here (their alarms still ring) and so does the loop. */
    fun close() {
        session.stopTimers()
        tickJob?.cancel()
    }

    companion object {
        /** How often a running timer's remaining time is recomputed. */
        const val TICK_MS = 250L
    }
}
