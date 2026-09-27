package com.example.recipeclipper.reminders

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.recipeclipper.data.model.ExpiryReminder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.TimeZone

/** The Android halves of the expiry reminders (#52): the alarm, and the notification it posts. */
@RunWith(AndroidJUnit4::class)
class ExpiryReminderAlarmsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scheduler = AndroidExpiryReminderScheduler(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After
    fun cleanUp() {
        scheduler.replaceAll(emptyList())
        notifications.cancel(ExpiryNotifications.NOTIFICATION_TAG, 0)
    }

    private fun pendingAlarm(): PendingIntent? = PendingIntent.getBroadcast(
        context, 0, AndroidExpiryReminderScheduler.alarmIntent(context),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
    )

    @Test
    fun schedulesOneAlarmAndCancelsIt() {
        val tomorrow = System.currentTimeMillis() / 86_400_000L + 1
        scheduler.replaceAll(listOf(ExpiryReminder(tomorrow, emptyList(), listOf("milk"))))
        assertNotNull("an alarm is pending", pendingAlarm())

        scheduler.replaceAll(emptyList())
        assertNull("the alarm is cancelled", pendingAlarm())
    }

    @Test
    fun theAlarmIsAtNineLocalTime() {
        val zone = TimeZone.getTimeZone("Europe/Paris")
        val millis = AndroidExpiryReminderScheduler.morningMillis(20_000, zone) // 2024-10-04
        val local = Calendar.getInstance(zone).apply { timeInMillis = millis }
        assertEquals(2024, local.get(Calendar.YEAR))
        assertEquals(Calendar.OCTOBER, local.get(Calendar.MONTH))
        assertEquals(4, local.get(Calendar.DAY_OF_MONTH))
        assertEquals(9, local.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, local.get(Calendar.MINUTE))
    }

    @Test
    fun postsOneNotificationListingTheItems() {
        ExpiryNotifications.post(context, ExpiryReminder(20_000, listOf("milk"), listOf("eggs", "yogurt")))

        assertTrue("notifications allowed", notifications.areNotificationsEnabled())
        // The notification service posts asynchronously.
        var posted = notifications.activeNotifications.firstOrNull { it.tag == ExpiryNotifications.NOTIFICATION_TAG }
        val deadline = System.currentTimeMillis() + 5_000
        while (posted == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            posted = notifications.activeNotifications.firstOrNull { it.tag == ExpiryNotifications.NOTIFICATION_TAG }
        }
        assertNotNull("the reminder is posted", posted)
        posted!!
        val text = posted.notification.extras.getCharSequence("android.text").toString()
        assertEquals("Milk expires today. Eggs and yogurt expire tomorrow.", text)
        assertEquals(ExpiryNotifications.CHANNEL_ID, posted.notification.channelId)
    }
}
