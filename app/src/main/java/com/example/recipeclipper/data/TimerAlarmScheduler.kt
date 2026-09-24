package com.example.recipeclipper.data

import com.example.recipeclipper.data.model.StepAlarm

/**
 * Schedules the background "time's up" alert for a running step timer, so it still sounds
 * when the app is in the background, dozing or killed. A seam, like [Connectivity]: the
 * ViewModel calls it and never touches `AlarmManager` or `Context`, and tests pass a fake.
 * The real one is `timers.AndroidTimerAlarmScheduler`.
 *
 * Both calls are idempotent: scheduling a timer again replaces its alarm, and cancelling one
 * that isn't scheduled does nothing.
 */
interface TimerAlarmScheduler {
    fun schedule(alarm: StepAlarm)
    fun cancel(recipeId: Long, step: Int)
}
