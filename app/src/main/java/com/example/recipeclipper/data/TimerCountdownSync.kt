package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one path that squares the countdowns shown outside the app (#309) with the database
 * (#333): [reconcile] hands the scheduler the timers still running there, and it removes every
 * other countdown. Called after a recipe is deleted (the Recipes swipe and the recipe screen),
 * after a re-share, an edit or "Update from source" (whose changed steps drop the cook's
 * progress), and, through [start], whenever `liveTimers` changes. The alerts need none of
 * this: their receiver checks the database itself.
 */
@Singleton
class TimerCountdownSync @Inject constructor(
    private val repository: RecipeRepository,
    private val alarms: TimerAlarmScheduler,
    private val flags: FeatureFlags
) {
    suspend fun reconcile() = alarms.reconcile(repository.runningTimers())

    /** Follows the `liveTimers` flag for as long as the process lives: off removes every countdown. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            flags.values.map { it.isOn(Flag.LIVE_TIMERS) }
                .distinctUntilChanged()
                .drop(1)
                .collect { reconcile() }
        }
    }
}
