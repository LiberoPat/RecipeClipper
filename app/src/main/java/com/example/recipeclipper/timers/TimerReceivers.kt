package com.example.recipeclipper.timers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.recipeclipper.data.Clock
import com.example.recipeclipper.data.ExpiryReminderCoordinator
import com.example.recipeclipper.data.RecipeRepository
import com.example.recipeclipper.data.TimerAlarmScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What the receivers need from the graph. An entry point rather than `@AndroidEntryPoint`,
 *  which for a BroadcastReceiver wants a `super.onReceive` Kotlin can't call. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TimerEntryPoint {
    fun recipeRepository(): RecipeRepository
    fun timerAlarmScheduler(): TimerAlarmScheduler
    fun clock(): Clock
    fun expiryReminderCoordinator(): ExpiryReminderCoordinator
}

private fun entryPoint(context: Context): TimerEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, TimerEntryPoint::class.java)

/** Runs [block] off the main thread and keeps the receiver alive until it finishes. */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

/**
 * A step timer's alarm went off. Posts the notification only if the database still has that
 * timer running with that same deadline, so a timer that was reset, a recipe that was deleted,
 * or a re-share that changed the steps never announces a timer the app has no record of. It
 * also stays quiet while that recipe is on screen, where the in-app beep already sounds.
 */
class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val recipeId = intent.getLongExtra(AndroidTimerAlarmScheduler.EXTRA_RECIPE_ID, -1)
        val step = intent.getIntExtra(AndroidTimerAlarmScheduler.EXTRA_STEP, -1)
        val endsAt = intent.getLongExtra(AndroidTimerAlarmScheduler.EXTRA_ENDS_AT, -1)
        if (recipeId < 0 || step < 0) return
        val app = context.applicationContext
        runAsync {
            val alarm = entryPoint(app).recipeRepository().runningTimers()
                .firstOrNull { it.recipeId == recipeId && it.step == step && it.endsAt == endsAt }
            if (alarm != null && VisibleRecipe.id != recipeId) TimerNotifications.post(app, alarm)
        }
    }
}

/**
 * Alarms don't survive a reboot or an app update. Reschedules every timer still running in the
 * database whose deadline hasn't passed; one that ended meanwhile shows as finished when its
 * recipe is opened. (Opening a recipe also reschedules its timers, which covers a force-stop.)
 * It also re-arms the pantry's expiry reminder (#52).
 */
class TimerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val entry = entryPoint(context)
        runAsync {
            val now = entry.clock().now()
            val scheduler = entry.timerAlarmScheduler()
            entry.recipeRepository().runningTimers()
                .filter { it.endsAt > now }
                .forEach(scheduler::schedule)
            // The pantry's morning reminder (#52) is an alarm too.
            entry.expiryReminderCoordinator().refresh()
        }
    }
}

/**
 * The recipe whose screen is started (visible), if any: set by the recipe screen, read by
 * [TimerAlarmReceiver]. A process-wide flag because the receiver runs outside any screen.
 */
object VisibleRecipe {
    @Volatile
    var id: Long? = null

    fun clear(recipeId: Long) {
        if (id == recipeId) id = null
    }
}
