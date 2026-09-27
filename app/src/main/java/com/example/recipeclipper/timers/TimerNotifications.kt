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

/** The "time's up" notification for a step timer that ended while the app wasn't showing it. */
object TimerNotifications {

    const val CHANNEL_ID = "cook_timers"

    /** MainActivity opens the recipe in cook mode for an intent with this action. */
    const val ACTION_OPEN_COOK = "com.example.recipeclipper.action.OPEN_COOK"
    const val EXTRA_RECIPE_ID = "recipeId"

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.timer_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = context.getString(R.string.timer_channel_description) }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
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

    private fun tag(recipeId: Long) = "timer:$recipeId"
}
