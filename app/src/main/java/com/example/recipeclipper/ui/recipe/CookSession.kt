package com.example.recipeclipper.ui.recipe

import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.model.CookProgress
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.data.model.SavedTimer
import com.example.recipeclipper.data.model.StepAlarm
import kotlin.math.ceil

/**
 * Cook mode's state machine (#169): the current, done and upcoming steps, the ingredients bar,
 * the step timers and the end of cooking (#147). Pure over [clock]: each transition takes the
 * [CookState] on screen and returns the next one, and the one thing it keeps is each running
 * timer's wall-clock deadline, so remaining time is recomputed on every tick rather than
 * counted down, and survives a pause, a closed app or a killed one. It never touches alarms:
 * the ViewModel keeps the tick loop and schedules or cancels what a transition says.
 */
class CookSession(private val clock: Clock) {

    // Step index -> wall-clock time its timer ends. Only running timers are in here.
    private val deadlines = mutableMapOf<Int, Long>()

    /** Whether any timer is running: the tick loop runs exactly while this holds. */
    val hasRunningTimers: Boolean get() = deadlines.isNotEmpty()

    /** The saved progress brought back, and the alarms its running timers need, in saved order. */
    data class Restored(val cook: CookState, val alarms: List<StepAlarm>)

    /**
     * Picks up where the cook left off, even if the app was closed or killed meanwhile. A timer
     * that was running resumes from its saved deadline, so it kept counting while closed; one
     * whose deadline passed shows as finished and already alerted (the background alert has
     * announced it, so no stale beep on reopening). Its alarm is scheduled again, which is
     * idempotent and recovers one lost to a force-stop. Indexes past the last step are dropped.
     */
    fun restore(recipe: Recipe): Restored {
        deadlines.clear()
        val saved = recipe.cook
        val count = recipe.instructions.size
        val now = clock.now()
        val alarms = mutableListOf<StepAlarm>()
        val timers = mutableMapOf<Int, StepTimer>()
        for ((step, timer) in saved.timers) {
            if (step !in 0 until count) continue
            val endsAt = timer.endsAt
            timers[step] = when {
                endsAt != null && endsAt > now -> {
                    deadlines[step] = endsAt
                    alarms += StepAlarm(recipe.id, recipe.name, step, endsAt)
                    StepTimer(timer.totalSeconds, secondsUntil(endsAt, now), running = true)
                }
                endsAt != null || timer.remainingSeconds == 0 ->
                    StepTimer(timer.totalSeconds, 0, running = false, alerted = true)
                else -> StepTimer(timer.totalSeconds, timer.remainingSeconds, running = false)
            }
        }
        val cook = CookState(
            active = saved.active && count > 0,
            currentStep = if (count > 0) saved.currentStep.coerceIn(0, count - 1) else 0,
            doneSteps = saved.doneSteps.filterTo(mutableSetOf()) { it in 0 until count },
            timers = timers
        )
        return Restored(cook, alarms)
    }

    /** Stops every running timer and returns their steps, whose alarms must go too. */
    fun stopTimers(): List<Int> {
        val steps = deadlines.keys.toList()
        deadlines.clear()
        return steps
    }

    /**
     * Cook mode on. [Start.cook] is null for a recipe with no steps, which has nothing to cook.
     * A finished run starts fresh and its timers go with it: [Start.stopped] are their steps,
     * whose alarms must go too. Anything else resumes where it was left.
     */
    data class Start(val cook: CookState?, val stopped: List<Int>)

    fun start(cook: CookState, stepCount: Int): Start {
        val finished = cook.doneSteps.size >= stepCount
        val stopped = if (finished) stopTimers() else emptyList()
        if (stepCount == 0) return Start(null, stopped)
        val from = if (finished) CookState() else cook
        return Start(from.copy(active = true, currentStep = from.currentStep.coerceIn(0, stepCount - 1)), stopped)
    }

    /** Cook mode off. Its progress is kept, so a stray tap on Exit loses nothing. */
    fun exit(cook: CookState): CookState = cook.copy(active = false)

    /** Opens or closes the ingredients bar. */
    fun toggleIngredients(cook: CookState): CookState = cook.copy(ingredientsExpanded = !cook.ingredientsExpanded)

    /** Tapping a step makes it current. Only [done] ever advances or marks progress. */
    fun select(cook: CookState, step: Int): CookState = cook.copy(currentStep = step)

    /** "Done — next step": the cook after it, and whether that was the last step left. */
    data class Done(val cook: CookState, val finished: Boolean)

    fun done(cook: CookState, stepCount: Int): Done {
        val done = cook.doneSteps + cook.currentStep
        // Next unfinished step after this one, else the earliest one skipped, else finished.
        val next = (cook.currentStep + 1 until stepCount).firstOrNull { it !in done }
            ?: (0 until stepCount).firstOrNull { it !in done }
        return if (next != null) {
            Done(cook.copy(doneSteps = done, currentStep = next), finished = false)
        } else {
            Done(cook.copy(doneSteps = done, active = false), finished = true)
        }
    }

    /**
     * The end of cooking (#147): the [ticked] lines as [content] shows them (scaled and
     * converted), for using up the pantry; null when nothing is ticked.
     */
    fun finishedCook(content: RecipeContent.Success, ticked: Set<Int>): FinishedCook? {
        val lines = ticked.sorted().mapNotNull { content.ingredients.getOrNull(it) }
        return if (lines.isEmpty()) null else FinishedCook(content.words?.language, lines)
    }

    /** A timer's next state and its alarm: [endsAt] to schedule one then, null to cancel it. */
    data class TimerChange(val cook: CookState, val endsAt: Long?)

    /** Starts step [step]'s [totalSeconds] timer from the top. */
    fun startTimer(cook: CookState, step: Int, totalSeconds: Int): TimerChange {
        val endsAt = clock.now() + totalSeconds * 1000L
        deadlines[step] = endsAt
        return TimerChange(cook.withTimer(step, StepTimer(totalSeconds, totalSeconds, running = true)), endsAt)
    }

    /**
     * Pauses step [step]'s running timer, or resumes a paused one with time left; null when it
     * has no timer or it has finished, which leaves nothing to do.
     */
    fun toggleTimer(cook: CookState, step: Int): TimerChange? {
        val timer = cook.timers[step] ?: return null
        return when {
            timer.running -> {
                deadlines.remove(step)
                TimerChange(cook.withTimer(step, timer.copy(running = false)), endsAt = null)
            }
            timer.remainingSeconds > 0 -> {
                val endsAt = clock.now() + timer.remainingSeconds * 1000L
                deadlines[step] = endsAt
                TimerChange(cook.withTimer(step, timer.copy(running = true)), endsAt)
            }
            else -> null
        }
    }

    /** Step [step]'s timer back to its full time, stopped (its alarm goes); null with no timer. */
    fun resetTimer(cook: CookState, step: Int): CookState? {
        val timer = cook.timers[step] ?: return null
        deadlines.remove(step)
        return cook.withTimer(step, StepTimer(timer.totalSeconds, timer.totalSeconds, running = false))
    }

    /** The "time's up" sound has played for step [step]; null with no timer. */
    fun alerted(cook: CookState, step: Int): CookState? {
        val timer = cook.timers[step] ?: return null
        return cook.withTimer(step, timer.copy(alerted = true))
    }

    /**
     * Every running timer's seconds left now, from its deadline; null when none changed. A timer
     * that reaches zero stops running and keeps its alarm: the deadline has passed, so the alarm
     * has fired or is firing, and cancelling it would only race it. The alarm stays quiet on its
     * own while this recipe is on screen, where the in-app beep sounds instead.
     */
    fun tick(cook: CookState): CookState? {
        val now = clock.now()
        val remaining = deadlines.mapValues { (_, end) -> secondsUntil(end, now) }
        remaining.filterValues { it == 0 }.keys.forEach { deadlines.remove(it) }
        val timers = cook.timers.toMutableMap()
        var changed = false
        for ((step, seconds) in remaining) {
            val timer = timers[step] ?: continue
            if (timer.remainingSeconds != seconds) {
                timers[step] = timer.copy(remainingSeconds = seconds, running = seconds > 0)
                changed = true
            }
        }
        return if (changed) cook.copy(timers = timers) else null
    }

    /**
     * The cook's place as saved. A running timer is saved by its deadline, not its remaining
     * seconds, so the tick loop never needs to write and a closed app's timer keeps counting.
     * `ingredientsExpanded` and `alerted` are screen state and aren't saved.
     */
    fun progress(cook: CookState): CookProgress = CookProgress(
        active = cook.active,
        currentStep = cook.currentStep,
        doneSteps = cook.doneSteps,
        timers = cook.timers.mapValues { (step, timer) ->
            SavedTimer(timer.totalSeconds, timer.remainingSeconds, deadlines[step])
        }
    )

    private fun CookState.withTimer(step: Int, timer: StepTimer) = copy(timers = timers + (step to timer))

    companion object {
        /** Whole seconds from [now] until [endsAt], rounded up; 0 once it has passed. */
        fun secondsUntil(endsAt: Long, now: Long): Int = ceil((endsAt - now) / 1000.0).toInt().coerceAtLeast(0)
    }
}
