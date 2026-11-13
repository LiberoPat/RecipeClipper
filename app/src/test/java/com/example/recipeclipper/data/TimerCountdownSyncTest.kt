package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.StepAlarm
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeRecipeRepository
import com.example.recipeclipper.fake.FakeTimerAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one path that squares the countdowns outside the app with the database (#333). */
@OptIn(ExperimentalCoroutinesApi::class)
class TimerCountdownSyncTest {

    private val repository = FakeRecipeRepository()
    private val alarms = FakeTimerAlarmScheduler()
    private val store = FakeFeatureFlagStore()
    private val sync = TimerCountdownSync(repository, alarms, FeatureFlags(store, FlagRegistry.definitions, isDebug = true))
    private val running = listOf(StepAlarm(7, "Focaccia", 2, 90_000))

    @Test fun `reconciling hands the scheduler the database's running timers`() = runTest {
        repository.runningTimersResult = running

        sync.reconcile()

        assertEquals(listOf(running), alarms.reconcileCalls)
    }

    @Test fun `a change of the liveTimers flag reconciles, its current value doesn't`() = runTest {
        repository.runningTimersResult = running
        // Unconfined, so each change is collected as it is made, like the app's own scope.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        sync.start(scope)
        assertTrue(alarms.reconcileCalls.isEmpty())

        store.setOverride(Flag.LIVE_TIMERS.key, false)
        assertEquals(1, alarms.reconcileCalls.size)

        store.setOverride(Flag.WIDGETS.key, false)
        assertEquals("another flag is not this one", 1, alarms.reconcileCalls.size)

        store.setOverride(Flag.LIVE_TIMERS.key, null)
        assertEquals(2, alarms.reconcileCalls.size)
        scope.cancel()
    }
}
