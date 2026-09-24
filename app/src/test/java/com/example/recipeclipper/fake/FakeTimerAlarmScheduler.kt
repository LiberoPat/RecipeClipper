package com.example.recipeclipper.fake

import com.example.recipeclipper.data.TimerAlarmScheduler
import com.example.recipeclipper.data.model.StepAlarm

/**
 * Keeps the alarms as real state (recipe id and step to the alarm), the way `AlarmManager`
 * does, so a test can assert what is pending after a sequence of calls; every call is also
 * recorded in order.
 */
class FakeTimerAlarmScheduler : TimerAlarmScheduler {

    val pending = mutableMapOf<Pair<Long, Int>, StepAlarm>()
    val scheduleCalls = mutableListOf<StepAlarm>()
    val cancelCalls = mutableListOf<Pair<Long, Int>>()

    override fun schedule(alarm: StepAlarm) {
        scheduleCalls += alarm
        pending[alarm.recipeId to alarm.step] = alarm
    }

    override fun cancel(recipeId: Long, step: Int) {
        cancelCalls += recipeId to step
        pending.remove(recipeId to step)
    }
}
