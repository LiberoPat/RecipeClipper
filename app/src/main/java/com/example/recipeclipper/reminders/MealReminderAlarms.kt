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
import com.example.recipeclipper.data.MealReminderAlarm
import com.example.recipeclipper.data.MealReminderCoordinator
import com.example.recipeclipper.data.MealReminderScheduler
import com.example.recipeclipper.data.model.MealReminder
import com.example.recipeclipper.data.model.MealReminderKind
import com.example.recipeclipper.data.model.MealReminderText
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.runAsync
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The evening-before and Tonight reminders on Android (#397): **one** `AlarmManager` alarm per
 * kind, for its next firing. Its receiver builds the notification from the database as it is
 * then and arms the next ([MealReminderCoordinator.due]), so only the next firing of each kind is
 * ever pending and nothing stale can ring.
 *
 * Exact where the app may schedule exact alarms (`setExactAndAllowWhileIdle`: unlike a timer's
 * `setAlarmClock`, it shows no alarm icon in the status bar, since a reminder is not an alarm
 * clock); on Android 14+ that is only if the cook has allowed "Alarms & reminders", and otherwise
 * the alarm is inexact (`setAndAllowWhileIdle`) and may come a few minutes late. Asking for the
 * permission is the same open question as the timers' (docs/decisions.md), deliberately not
 * answered in code.
 */
@Singleton
class AndroidMealReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : MealReminderScheduler {

    override fun replaceAll(alarms: List<MealReminderAlarm>) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        for (kind in MealReminderKind.entries) {
            val alarm = alarms.firstOrNull { it.kind == kind }
            if (alarm != null) {
                set(manager, alarm)
            } else {
                PendingIntent.getBroadcast(
                    context, kind.ordinal, alarmIntent(context, kind),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )?.let {
                    manager.cancel(it)
                    it.cancel()
                }
            }
        }
    }

    override fun arm(alarm: MealReminderAlarm) {
        context.getSystemService(AlarmManager::class.java)?.let { set(it, alarm) }
    }

    private fun set(manager: AlarmManager, alarm: MealReminderAlarm) {
        val operation = PendingIntent.getBroadcast(
            context, alarm.kind.ordinal, alarmIntent(context, alarm.kind).putExtra(EXTRA_DAY, alarm.day),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = fireMillis(alarm.day, alarm.minute)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                return
            } catch (e: SecurityException) {
                // Revoked between the check and the call: fall through to the inexact alarm.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_DAY = "day"
        private const val ACTION_ALARM = "com.example.recipeclipper.action.MEAL_REMINDER"

        internal fun alarmIntent(context: Context, kind: MealReminderKind) =
            Intent(context, MealReminderReceiver::class.java).setAction(ACTION_ALARM).putExtra(EXTRA_KIND, kind.name)

        /** [minute] minutes after local midnight of [day] (an epoch day), in [zone]. */
        fun fireMillis(day: Long, minute: Int, zone: TimeZone = TimeZone.getDefault()): Long {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = PlanDays.utcMillis(day) }
            return Calendar.getInstance(zone).apply {
                clear()
                set(
                    utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH),
                    minute / 60, minute % 60, 0
                )
            }.timeInMillis
        }
    }
}

/** The reminders' notification, and the tap that opens the first recipe on the Week tab. */
object MealReminderNotifications {
    const val CHANNEL_ID = "meal_reminders"

    /** MainActivity opens the Week tab, on [EXTRA_RECIPE_ID]'s recipe when it has one, for this action. */
    const val ACTION_OPEN_MEAL = "com.example.recipeclipper.action.OPEN_MEAL_REMINDER"
    const val EXTRA_RECIPE_ID = "recipeId"

    internal const val NOTIFICATION_TAG = "meals:reminder"

    fun post(context: Context, reminder: MealReminder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.meal_reminders_channel_name), NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.meal_reminders_channel_description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        val title = title(context, reminder)
        val body = body(context, reminder)
        val open = PendingIntent.getActivity(
            context, reminder.kind.ordinal,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_MEAL)
                .putExtra(EXTRA_RECIPE_ID, reminder.recipeId ?: -1L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_week)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (body != null) builder.setContentText(body.lineSequence().first()).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        try {
            manager.notify(NOTIFICATION_TAG + ":" + reminder.kind.name, 0, builder.build())
        } catch (e: SecurityException) {
            // Permission withdrawn between the check and the call.
        }
    }

    /** "Tomorrow: Porridge, Lemon chicken" / "Tonight: Lemon chicken". */
    fun title(context: Context, reminder: MealReminder): String = MealReminderText.title(
        reminder,
        tomorrow = { context.getString(R.string.meal_reminder_tomorrow, it) },
        tonight = { context.getString(R.string.meal_reminder_tonight, it) }
    )

    /** What to do ahead, quoting the step; null when nothing. */
    fun body(context: Context, reminder: MealReminder): String? = MealReminderText.body(
        reminder,
        one = { context.getString(R.string.meal_reminder_ahead_one, it) },
        header = context.getString(R.string.meal_reminder_ahead_header),
        item = { recipe, quote -> context.getString(R.string.meal_reminder_ahead_item, recipe, quote) }
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MealReminderEntryPoint {
    fun mealReminderCoordinator(): MealReminderCoordinator
}

/**
 * A reminder's alarm. Posts the reminder as the plan is now (nothing if what it names was moved
 * or removed, the setting or the flag went off, or the alarm is a day late), then arms the next.
 */
class MealReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = MealReminderKind.entries.firstOrNull { it.name == intent.getStringExtra(AndroidMealReminderScheduler.EXTRA_KIND) }
            ?: return
        val day = intent.getLongExtra(AndroidMealReminderScheduler.EXTRA_DAY, -1)
        val app = context.applicationContext
        val coordinator = EntryPointAccessors.fromApplication(app, MealReminderEntryPoint::class.java).mealReminderCoordinator()
        runAsync {
            try {
                coordinator.due(kind, day)?.let { MealReminderNotifications.post(app, it) }
            } finally {
                // However it went, the next one is armed.
                coordinator.rearm(kind, day)
            }
        }
    }
}
