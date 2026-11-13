package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.StepAlarm

/**
 * Schedules the background "time's up" alert for a running step timer, so it still sounds
 * when the app is in the background, dozing or killed, and (#309) shows the timer counting
 * down outside the app. A seam, like [Connectivity]: the ViewModel calls it and never touches
 * `AlarmManager` or `Context`, and tests pass a fake. The real one is
 * `timers.AndroidTimerAlarmScheduler`.
 *
 * Every call is idempotent: scheduling a timer again replaces its alarm, and cancelling one
 * that isn't scheduled does nothing.
 */
interface TimerAlarmScheduler {
    fun schedule(alarm: StepAlarm)
    fun cancel(recipeId: Long, step: Int)

    /**
     * The timer ran out while its recipe was on screen, where the in-app beep sounds: its
     * countdown goes. The alert is left alone; it has fired or is firing, and the receiver's
     * check of the database keeps it quiet for the recipe on screen.
     */
    fun ended(recipeId: Long, step: Int)

    /**
     * Keeps only [running]'s countdowns, the database's running timers (#333): a deleted recipe,
     * a re-share or an edit that changed the steps, or `liveTimers` turned off (then none stay)
     * leaves nothing counting down for a timer the app has no record of. Alerts are left alone:
     * their receiver checks the database itself. Called through [TimerCountdownSync].
     */
    fun reconcile(running: List<StepAlarm>)
}
