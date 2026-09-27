package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagDefinition
import com.example.recipeclipper.data.model.Aisle
import com.example.recipeclipper.data.model.ExpiryReminder
import com.example.recipeclipper.data.model.PantryItem
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakePantryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class ExpiryReminderCoordinatorTest {

    private val today = 20_000L
    private val utc = TimeZone.getTimeZone("UTC")
    private var now = PlanDays.utcMillis(today) + 8 * 3_600_000L // 8:00 UTC

    private val pantry = FakePantryRepository(
        listOf(PantryItem(1, "milk", null, "en", Aisle.DAIRY, true, false, null, today + 1))
    )
    private val preferences = FakeAppPreferences(expiryReminders = true)
    private val flagStore = FakeFeatureFlagStore(mapOf("mealPlan" to true))
    private val flags = FeatureFlags(
        flagStore,
        listOf(FlagDefinition("mealPlan", "The meal plan", debugDefault = false, releaseDefault = false, issue = 47)),
        isDebug = true
    )
    private val scheduled = mutableListOf<List<ExpiryReminder>>()
    private val coordinator = ExpiryReminderCoordinator(
        pantry, preferences, flags, { scheduled += it }, { now }
    ).apply { useZone(utc) }

    private fun started(): TestScope = TestScope(StandardTestDispatcher()).also {
        coordinator.start(it)
        it.runCurrent()
    }

    @Test fun `plans the pantry on start and again on every change`() {
        val scope = started()
        assertEquals(listOf(today, today + 1), scheduled.last().map { it.day })

        pantry.items.value = pantry.items.value.map { it.copy(inStock = false) }
        scope.runCurrent()
        assertEquals(emptyList<ExpiryReminder>(), scheduled.last())
    }

    @Test fun `turning the setting off cancels, and on plans again`() {
        val scope = started()
        preferences.expiryReminders = false
        scope.runCurrent()
        assertEquals(emptyList<ExpiryReminder>(), scheduled.last())

        preferences.expiryReminders = true
        scope.runCurrent()
        assertEquals(2, scheduled.last().size)
    }

    @Test fun `nothing is planned while the pantry is hidden behind the flag`() {
        val scope = started()
        flags.set(Flag.MEAL_PLAN, false)
        scope.runCurrent()
        assertEquals(emptyList<ExpiryReminder>(), scheduled.last())
    }

    @Test fun `after nine, today's reminder is no longer planned`() = kotlinx.coroutines.test.runTest {
        now = PlanDays.utcMillis(today) + 10 * 3_600_000L
        coordinator.refresh()
        assertEquals(listOf(today + 1), scheduled.last().map { it.day })
    }

    @Test fun `the alarm's reminder is today's, and none when the setting is off`() = kotlinx.coroutines.test.runTest {
        assertEquals(listOf("milk"), coordinator.dueToday()!!.tomorrow)
        preferences.expiryReminders = false
        assertNull(coordinator.dueToday())
    }
}
