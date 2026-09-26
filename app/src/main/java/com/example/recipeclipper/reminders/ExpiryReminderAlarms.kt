package com.example.recipeclipper.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.recipeclipper.MainActivity
import com.example.recipeclipper.R
import com.example.recipeclipper.data.ExpiryReminderCoordinator
import com.example.recipeclipper.data.ExpiryReminderScheduler
import com.example.recipeclipper.data.model.ExpiryReminder
import com.example.recipeclipper.data.model.ExpiryReminderText
import com.example.recipeclipper.data.model.ExpiryReminders
import com.example.recipeclipper.data.model.PlanDays
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pantry's expiry reminders on Android (#52): **one** inexact `AlarmManager` alarm, at 9:00
 * on the first morning [ExpiryReminders.plan] returns. Its receiver re-reads the pantry, posts
 * that morning's notification if there still is one, then plans the next alarm. So only the
 * next morning is ever pending, and nothing stale can ring: an item used up or re-dated since
 * the plan is simply not in the notification.
 *
 * Inexact on purpose (`setAndAllowWhileIdle`): a morning note needs no exact-alarm permission
 * (#10's `SCHEDULE_EXACT_ALARM` question), and may come a few minutes after nine.
 */
@Singleton
class AndroidExpiryReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : ExpiryReminderScheduler {

    override fun replaceAll(reminders: List<ExpiryReminder>) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val next = reminders.firstOrNull()
        if (next == null) {
            PendingIntent.getBroadcast(
                context, 0, alarmIntent(context), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let {
                manager.cancel(it)
                it.cancel()
            }
            return
        }
        val operation = PendingIntent.getBroadcast(
            context, 0, alarmIntent(context).putExtra(EXTRA_DAY, next.day),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, morningMillis(next.day), operation)
    }

    companion object {
        const val EXTRA_DAY = "day"
        private const val ACTION_ALARM = "com.example.recipeclipper.action.EXPIRY_REMINDER"

        internal fun alarmIntent(context: Context) =
            Intent(context, ExpiryReminderReceiver::class.java).setAction(ACTION_ALARM)

        /** [ExpiryReminders.HOUR] o'clock local time on [day] (an epoch day). */
        fun morningMillis(day: Long, zone: TimeZone = TimeZone.getDefault()): Long {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = PlanDays.utcMillis(day) }
            return Calendar.getInstance(zone).apply {
                clear()
                set(
                    utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH),
                    ExpiryReminders.HOUR, 0, 0
                )
            }.timeInMillis
        }
    }
}

/** The reminder notification, and the tap that opens the Pantry tab. */
object ExpiryNotifications {
    const val CHANNEL_ID = "pantry_reminders"

    /** MainActivity opens the Pantry tab for an intent with this action. */
    const val ACTION_OPEN_PANTRY = "com.example.recipeclipper.action.OPEN_PANTRY"

    internal const val NOTIFICATION_TAG = "pantry:expiry"

    fun post(context: Context, reminder: ExpiryReminder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.expiry_channel_name), NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.expiry_channel_description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        val text = body(context, reminder)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_PANTRY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_pantry)
            .setContentTitle(context.getString(R.string.expiry_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            manager.notify(NOTIFICATION_TAG, 0, notification)
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the call.
        }
    }

    /** "Milk and yogurt expire tomorrow.", or both sentences when some expire today. */
    fun body(context: Context, reminder: ExpiryReminder): String {
        val locale = context.resources.configuration.locales[0]
        fun sentence(names: List<String>, one: Int, many: Int): String {
            val joined = ExpiryReminders.joinNames(names) { a, b -> context.getString(R.string.expiry_names_and, a, b) }
            return ExpiryReminders.capitalized(context.getString(if (names.size == 1) one else many, joined), locale)
        }
        return when (reminder.text) {
            ExpiryReminderText.TODAY -> sentence(reminder.today, R.string.expiry_one_today, R.string.expiry_many_today)
            ExpiryReminderText.TOMORROW ->
                sentence(reminder.tomorrow, R.string.expiry_one_tomorrow, R.string.expiry_many_tomorrow)
            ExpiryReminderText.TODAY_AND_TOMORROW ->
                sentence(reminder.today, R.string.expiry_one_today, R.string.expiry_many_today) + " " +
                    sentence(reminder.tomorrow, R.string.expiry_one_tomorrow, R.string.expiry_many_tomorrow)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ExpiryReminderEntryPoint {
    fun expiryReminderCoordinator(): ExpiryReminderCoordinator
}

/**
 * The morning alarm. Posts today's reminder as the pantry is now (nothing if it has all been
 * used up, the setting was turned off, or the alarm arrives a day late), then schedules the
 * next morning.
 */
class ExpiryReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val day = intent.getLongExtra(AndroidExpiryReminderScheduler.EXTRA_DAY, -1)
        val app = context.applicationContext
        val coordinator = EntryPointAccessors.fromApplication(app, ExpiryReminderEntryPoint::class.java)
            .expiryReminderCoordinator()
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (day == coordinator.today()) coordinator.dueToday()?.let { ExpiryNotifications.post(app, it) }
                coordinator.refresh()
            } finally {
                pending.finish()
            }
        }
    }
}
