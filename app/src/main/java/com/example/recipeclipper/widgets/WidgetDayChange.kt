package com.example.recipeclipper.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.recipeclipper.data.model.PlanDays
import java.util.Calendar
import java.util.TimeZone

/**
 * Tonight's day turns at local midnight (#329): **one** alarm then redraws the widgets, and that
 * redraw arms the next one. It replaced a refresh every 30 minutes, around the clock.
 *
 * Inexact, by decision (no exact-alarm permission and no Settings prompt, as for the timers'
 * alerts): a window of [WINDOW_MILLIS] after midnight, where a plain `set` may run hours late.
 * Not a wake-up either: with the screen off nobody sees the widget, and the alarm fires when
 * the phone next wakes. [WidgetClockReceiver] also hears the date, the time and the time zone
 * being changed, which move "today" with no midnight passing.
 *
 * Only while a Tonight widget is placed: without one the alarm is cancelled and the receiver
 * disabled, so those broadcasts never start the app for nothing.
 */
internal class WidgetDayChange(private val context: Context) {

    // What this process last set the receiver to, so a redraw only asks the system once.
    private var listening: Boolean? = null

    /** Follows the day after [today], Tonight's; null when no Tonight widget is placed. */
    fun follow(today: Long?) {
        listen(today != null)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        if (today == null) {
            operation(PendingIntent.FLAG_NO_CREATE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
            return
        }
        // Set again by every redraw: it replaces the one before, and a redraw is rare.
        operation(PendingIntent.FLAG_UPDATE_CURRENT)?.let {
            alarms.setWindow(AlarmManager.RTC, midnightMillis(today + 1), WINDOW_MILLIS, it)
        }
    }

    private fun operation(flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, 0, Intent(context, WidgetClockReceiver::class.java).setAction(ACTION_MIDNIGHT),
        flag or PendingIntent.FLAG_IMMUTABLE
    )

    // The receiver is disabled in the manifest: on only while there is a day to follow.
    private fun listen(on: Boolean) {
        if (listening == on) return
        val receiver = ComponentName(context, WidgetClockReceiver::class.java)
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        val packages = context.packageManager
        if (packages.getComponentEnabledSetting(receiver) != state) {
            packages.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP)
        }
        listening = on
    }

    companion object {
        const val ACTION_MIDNIGHT = "com.example.recipeclipper.action.WIDGET_MIDNIGHT"

        /** How late the midnight redraw may be: ten minutes, the least a window can be. */
        const val WINDOW_MILLIS = 10 * 60_000L

        /** Local midnight at the start of [day] (an epoch day). */
        fun midnightMillis(day: Long, zone: TimeZone = TimeZone.getDefault()): Long {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = PlanDays.utcMillis(day) }
            return Calendar.getInstance(zone).apply {
                clear()
                set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            }.timeInMillis
        }
    }
}

/**
 * The clock moved under the Tonight widget (#329): [WidgetDayChange]'s midnight alarm, or the
 * date, the time or the time zone was changed. Every widget is redrawn, which arms the next
 * midnight for the day it is now.
 */
class WidgetClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WidgetDayChange.ACTION_MIDNIGHT, Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> redrawWidgets(context)
        }
    }
}
