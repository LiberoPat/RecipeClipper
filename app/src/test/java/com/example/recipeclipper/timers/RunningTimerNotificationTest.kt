package com.example.recipeclipper.timers

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.StepAlarm
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** A running timer's ongoing countdown (#309), posted and removed with its alarm (Robolectric). */
@RunWith(AndroidJUnit4::class)
class RunningTimerNotificationTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val now = 1_000_000L
    private val store = FakeFeatureFlagStore()
    private val scheduler = AndroidTimerAlarmScheduler(
        app,
        FeatureFlags(store, FlagRegistry.definitions, isDebug = true),
        clock = { now }
    )
    private val alarm = StepAlarm(recipeId = 7, recipeTitle = "Focaccia", step = 2, endsAt = now + 90_000)

    @Before
    fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted() = shadowOf(manager).allNotifications

    @Test
    fun scheduling_postsAnOngoingCountdownToTheDeadline() {
        scheduler.schedule(alarm)

        val notification = posted().single()
        assertEquals(TimerNotifications.RUNNING_CHANNEL_ID, notification.channelId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(alarm.endsAt, notification.`when`)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals(90_000L, notification.timeoutAfter)
        assertEquals("Step 3 timer", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Focaccia", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun rescheduling_replacesTheCountdown() {
        scheduler.schedule(alarm)
        scheduler.schedule(alarm.copy(endsAt = now + 30_000))

        assertEquals(now + 30_000, posted().single().`when`)
    }

    @Test
    fun cancelling_removesTheCountdown() {
        scheduler.schedule(alarm)
        scheduler.schedule(alarm.copy(step = 4))
        scheduler.cancel(alarm.recipeId, alarm.step)

        assertEquals(listOf(4), shadowOf(manager).activeNotifications.map { it.id })
    }

    @Test
    fun aDeadlineAlreadyPassed_postsNothing() {
        scheduler.schedule(alarm.copy(endsAt = now))

        assertTrue(posted().isEmpty())
    }

    @Test
    fun withTheFlagOff_onlyTheAlarmIsScheduled() {
        store.setOverride(Flag.LIVE_TIMERS.key, false)

        scheduler.schedule(alarm)

        assertTrue(posted().isEmpty())
        assertTrue(shadowOf(app.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms.isNotEmpty())
    }
}
