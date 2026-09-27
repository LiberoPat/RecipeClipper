package com.example.recipeclipper.timers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.example.recipeclipper.data.TimerAlarmScheduler
import com.example.recipeclipper.data.model.StepAlarm
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One `AlarmManager` alarm per running step timer, delivered to [TimerAlarmReceiver].
 *
 * `setAlarmClock()` when the app may schedule exact alarms: exact, exempt from Doze, and shown
 * as an alarm icon in the status bar (honest: an alarm really is pending). It is **not**
 * permission-free, whatever issue #10 assumed: apps targeting API 31+ need
 * `SCHEDULE_EXACT_ALARM`, and for apps targeting 33+ Android 14 denies it by default until the
 * user allows "Alarms & reminders" in Settings. So on Android 7–13 (where the declared
 * permission is granted) this is exact, and on 14+ it is exact only if the user has allowed it.
 * Otherwise it falls back to `setAndAllowWhileIdle()`, which still fires in Doze but is
 * inexact and can arrive minutes late. Asking the user for the permission is an open product
 * question (docs/decisions.md), deliberately not answered in code.
 *
 * Each timer's PendingIntent is identified by a `recipeclipper://timer/<recipe>/<step>` data
 * URI, so rescheduling replaces it and cancelling finds it.
 */
@Singleton
class AndroidTimerAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : TimerAlarmScheduler {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(AlarmManager::class.java)

    override fun schedule(alarm: StepAlarm) {
        val manager = alarmManager ?: return
        val operation = PendingIntent.getBroadcast(
            context,
            0,
            alarmIntent(alarm.recipeId, alarm.step)
                .putExtra(EXTRA_RECIPE_ID, alarm.recipeId)
                .putExtra(EXTRA_STEP, alarm.step)
                .putExtra(EXTRA_ENDS_AT, alarm.endsAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            try {
                val info = AlarmManager.AlarmClockInfo(
                    alarm.endsAt,
                    TimerNotifications.openCookIntent(context, alarm.recipeId)
                )
                manager.setAlarmClock(info, operation)
                return
            } catch (e: SecurityException) {
                // Revoked between the check and the call: fall through to the inexact alarm.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.endsAt, operation)
    }

    override fun cancel(recipeId: Long, step: Int) {
        val existing = PendingIntent.getBroadcast(
            context,
            0,
            alarmIntent(recipeId, step),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager?.cancel(existing)
        existing.cancel()
    }

    private fun alarmIntent(recipeId: Long, step: Int) =
        Intent(context, TimerAlarmReceiver::class.java)
            .setData("recipeclipper://timer/$recipeId/$step".toUri())

    companion object {
        const val EXTRA_RECIPE_ID = "recipeId"
        const val EXTRA_STEP = "step"
        const val EXTRA_ENDS_AT = "endsAt"
    }
}
