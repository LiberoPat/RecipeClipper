package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.local.AppPreferences
import com.example.recipeclipper.data.model.ExpiryReminder
import com.example.recipeclipper.data.model.ExpiryReminders
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PlanDays
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the pantry's expiry reminders (#52). A seam like [TimerAlarmScheduler]: the real
 * one is `reminders.AndroidExpiryReminderScheduler`, tests pass a fake.
 */
fun interface ExpiryReminderScheduler {
    /** Replaces whatever was scheduled with [reminders] (soonest first); empty cancels all. */
    fun replaceAll(reminders: List<ExpiryReminder>)
}

/**
 * Keeps the scheduled reminders in step with the pantry, the setting and the `mealPlan` flag
 * (#87: the pantry is invisible without it, so it reminds of nothing). Started once with the
 * app, it reschedules on every change; the alarm's receiver and the boot receiver call
 * [refresh] for a one-off plan.
 */
@Singleton
class ExpiryReminderCoordinator @Inject constructor(
    private val pantry: PantryRepository,
    private val preferences: AppPreferences,
    private val flags: FeatureFlags,
    private val scheduler: ExpiryReminderScheduler,
    private val clock: Clock
) {
    private var zone: () -> TimeZone = { TimeZone.getDefault() }

    /** For tests: the zone "today" and 9:00 are measured in. */
    internal fun useZone(zone: TimeZone) {
        this.zone = { zone }
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(
                pantry.observeItems(),
                preferences.settings.map { it.expiryReminders }.distinctUntilChanged(),
                flags.values.map { it.isOn(Flag.MEAL_PLAN) }.distinctUntilChanged()
            ) { items, on, pantryShown -> plan(items, on && pantryShown) }
                .collect(scheduler::replaceAll)
        }
    }

    /** Plans once from the database now. */
    suspend fun refresh() {
        scheduler.replaceAll(plan(pantry.items(), enabled))
    }

    /** Whether reminders are wanted at all: the setting on and the pantry shown. */
    val enabled: Boolean
        get() = preferences.expiryReminders && flags.isOn(Flag.MEAL_PLAN)

    /** Today's reminder, for the alarm that has just gone off; null when there is none. */
    suspend fun dueToday(): ExpiryReminder? {
        if (!enabled) return null
        return ExpiryReminders.forDay(pantry.items(), today())
    }

    fun today(): Long = PlanDays.today(clock.now(), zone())

    private fun plan(items: List<PantryItem>, on: Boolean): List<ExpiryReminder> {
        if (!on) return emptyList()
        val now = clock.now()
        val zone = zone()
        val offset = zone.getOffset(now)
        val minute = Math.floorMod(now + offset, PlanDays.MILLIS_PER_DAY) / 60_000
        return ExpiryReminders.plan(items, PlanDays.epochDay(now, offset), minute.toInt())
    }
}
