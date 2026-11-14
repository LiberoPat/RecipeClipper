package com.example.recipeclipper.timers

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.recipeclipper.MainActivity
import com.example.recipeclipper.R
import com.example.recipeclipper.data.model.StepAlarm

/**
 * The "time's up" notification for a step timer that ended while the app wasn't showing it,
 * and (#309) the ongoing one that counts down while it runs.
 */
object TimerNotifications {

    const val CHANNEL_ID = "cook_timers"

    /** The running timers' channel (#309): low importance, so the countdown never makes a sound. */
    const val RUNNING_CHANNEL_ID = "running_timers"

    /** MainActivity opens the recipe in cook mode for an intent with this action. */
    const val ACTION_OPEN_COOK = "com.example.recipeclipper.action.OPEN_COOK"
    const val EXTRA_RECIPE_ID = "recipeId"

    // Creating a channel is idempotent but a system call: once per process is enough.
    private val channelsCreated = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun ensureChannel(context: Context, id: String, name: Int, description: Int, importance: Int) {
        if (!channelsCreated.add(id)) return
        val channel = NotificationChannel(id, context.getString(name), importance)
            .apply { this.description = context.getString(description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** Opens [recipeId] in cook mode. One PendingIntent per recipe (the data URI differs). */
    fun openCookIntent(context: Context, recipeId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_COOK)
                .setData("recipeclipper://cook/$recipeId".toUri())
                .putExtra(EXTRA_RECIPE_ID, recipeId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * Posts the notification, or does nothing when notifications are off or (API 33+) the
     * permission was denied: the in-app beep is still there when the recipe is open.
     */
    fun post(context: Context, alarm: StepAlarm) {
        if (!mayNotify(context)) return
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(
            context, CHANNEL_ID, R.string.timer_channel_name, R.string.timer_channel_description,
            NotificationManager.IMPORTANCE_HIGH
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(context.getString(R.string.timer_notification_title, alarm.step + 1))
            .setContentText(alarm.recipeTitle)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openCookIntent(context, alarm.recipeId))
            .build()
        try {
            manager.notify(tag(alarm.recipeId), alarm.step, notification)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the call.
        }
    }

    /**
     * The running timer's ongoing notification (#309): the system's chronometer counts down to
     * the saved deadline, so nothing is posted per tick, and it removes itself at the deadline,
     * where [post]'s alert takes over. On Android 16 it asks to be a Live Update (the status
     * bar chip and the lock screen). A tap opens the recipe in cook mode. Reposting replaces it.
     */
    fun postRunning(context: Context, alarm: StepAlarm, now: Long) {
        val remaining = alarm.endsAt - now
        if (remaining <= 0 || !mayNotify(context)) return
        ensureChannel(
            context, RUNNING_CHANNEL_ID, R.string.timer_running_channel_name, R.string.timer_running_channel_description,
            NotificationManager.IMPORTANCE_LOW
        )
        val notification = NotificationCompat.Builder(context, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(context.getString(R.string.timer_running_title, alarm.step + 1))
            .setContentText(alarm.recipeTitle)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(alarm.endsAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(remaining)
            .setRequestPromotedOngoing(true)
            .setContentIntent(openCookIntent(context, alarm.recipeId))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(runningTag(alarm.recipeId), alarm.step, notification)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the call.
        }
    }

    /** Removes [step]'s countdown: paused, reset, stopped, or its alert has come. */
    fun cancelRunning(context: Context, recipeId: Long, step: Int) {
        NotificationManagerCompat.from(context).cancel(runningTag(recipeId), step)
    }

    /**
     * The countdowns now posted, each by its recipe and step to the deadline it counts to
     * (its `when`): one query, so a schedule can skip an identical repost and a reconcile can
     * find the stale ones.
     */
    fun postedCountdowns(context: Context): Map<Pair<Long, Int>, Long> =
        NotificationManagerCompat.from(context).activeNotifications
            .mapNotNull { posted ->
                val tag = posted.tag?.takeIf { it.startsWith(RUNNING_TAG_PREFIX) } ?: return@mapNotNull null
                val recipeId = tag.removePrefix(RUNNING_TAG_PREFIX).toLongOrNull() ?: return@mapNotNull null
                (recipeId to posted.id) to posted.notification.`when`
            }
            .toMap()

    private fun mayNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun tag(recipeId: Long) = "timer:$recipeId"

    private const val RUNNING_TAG_PREFIX = "running:"

    fun runningTag(recipeId: Long) = "$RUNNING_TAG_PREFIX$recipeId"
}
