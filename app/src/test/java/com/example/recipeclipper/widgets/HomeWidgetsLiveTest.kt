package com.example.recipeclipper.widgets

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.recipeclipper.R
import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.NewGroceryLine
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeGroceryRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakePlanCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.TimeZone

/**
 * The home-screen widgets (#309) kept current while the process lives, and over midnight
 * (#329): what [HomeWidgets.start] follows, and [WidgetDayChange]'s alarm.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HomeWidgetsLiveTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val host = WidgetHost(app)
    private val plan = FakeMealPlanRepository()
    private val groceries = FakeGroceryRepository()
    private val calendar = FakePlanCalendar()
    private val store = FakeFeatureFlagStore()
    private val widgets = HomeWidgets(
        app, plan, groceries, calendar, FeatureFlags(store, FlagRegistry.definitions, isDebug = true)
    )
    private val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val clockReceiver = ComponentName(app, WidgetClockReceiver::class.java)

    private fun placeTonight() = host.place(TonightWidgetProvider::class.java, 1)
    private fun placeList() = host.place(GroceriesWidgetProvider::class.java, 2)

    private fun meal(id: Long, day: Long, type: Long, title: String) =
        PlannedMeal(id, day, type, recipeId = id, title = title, imageUrl = null, servings = null, note = null)

    private fun title(id: Int) = host.view(id).findViewById<TextView>(R.id.widget_title)

    // Past the redraws' pause. Not advanceUntilIdle: the widgets are followed in the background
    // scope, which that doesn't run.
    private fun TestScope.settle() {
        advanceTimeBy(2_000)
        runCurrent()
    }

    @Test
    fun withNoWidgetPlacedNothingIsObservedOrScheduled() = runTest {
        widgets.start(backgroundScope)
        plan.meals.value = listOf(meal(1, calendar.today, FakeMealPlanRepository.DINNER, "Focaccia"))
        groceries.add(listOf(NewGroceryLine("2 onions", "en")))
        settle()

        assertEquals(0, plan.meals.subscriptionCount.value)
        assertEquals(0, groceries.items.subscriptionCount.value)
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun aWidgetPlacedLaterIsFollowedFromThenOn() = runTest {
        widgets.start(backgroundScope)
        settle()

        val list = placeList()
        widgets.update() // What its provider does when it is placed.
        groceries.add(listOf(NewGroceryLine("2 onions", "en")))
        settle()

        assertEquals(listOf("Groceries", "2 onions"), host.texts(list))
        assertEquals("only the list is placed: the plan isn't read", 0, plan.meals.subscriptionCount.value)
    }

    @Test
    fun aWriteThatChangesNothingShownRedrawsNothing() = runTest {
        val tonight = placeTonight()
        val list = placeList()
        val today = calendar.today
        widgets.start(backgroundScope)
        settle()
        assertEquals(listOf("Tonight", "Nothing planned for today"), host.texts(tonight))

        // Marked, so a redraw would show: drawing sets the title again.
        title(tonight).text = "not redrawn"
        title(list).text = "not redrawn"
        plan.meals.value = listOf(meal(1, today + 3, FakeMealPlanRepository.DINNER, "Another day's soup"))
        settle()
        assertEquals("not redrawn", title(tonight).text.toString())
        assertEquals("not redrawn", title(list).text.toString())

        plan.meals.value += meal(2, today, FakeMealPlanRepository.DINNER, "Focaccia")
        settle()
        assertEquals(listOf("Tonight", "Dinner", "Focaccia"), host.texts(tonight))
        assertEquals(listOf("Groceries", "Nothing left to buy"), host.texts(list))
    }

    @Test
    fun aRenamedMealTypeAndTheFlagRedraw() = runTest {
        val tonight = placeTonight()
        plan.meals.value = listOf(meal(1, calendar.today, FakeMealPlanRepository.DINNER, "Focaccia"))
        widgets.start(backgroundScope)
        settle()

        plan.types.value = plan.types.value.map { if (it.id == FakeMealPlanRepository.DINNER) it.copy(name = "Supper") else it }
        settle()
        assertEquals(listOf("Tonight", "Supper", "Focaccia"), host.texts(tonight))

        store.setOverride(Flag.WIDGETS.key, false)
        settle()
        assertEquals(listOf("Recipe Clipper"), host.texts(tonight))
        assertEquals("with the flag off the plan isn't read", 0, plan.meals.subscriptionCount.value)
    }

    // #329: one alarm at local midnight, in place of a refresh every 30 minutes.
    @Test
    fun tonightIsRedrawnAtMidnightAndFollowsTheNewDay() = runTest {
        val tonight = placeTonight()
        val today = calendar.today
        plan.meals.value = listOf(
            meal(1, today, FakeMealPlanRepository.DINNER, "Focaccia"),
            meal(2, today + 1, FakeMealPlanRepository.DINNER, "Tomorrow's soup")
        )
        widgets.start(backgroundScope)
        settle()
        assertEquals(listOf("Tonight", "Dinner", "Focaccia"), host.texts(tonight))

        val alarm = alarms.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC, alarm.type)
        assertEquals(WidgetDayChange.midnightMillis(today + 1), alarm.triggerAtMs)
        assertEquals(WidgetDayChange.WINDOW_MILLIS, alarm.windowLengthMs)
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, app.packageManager.getComponentEnabledSetting(clockReceiver)
        )

        calendar.today = today + 1
        widgets.update() // What the alarm's receiver does.
        assertEquals(listOf("Tonight", "Dinner", "Tomorrow's soup"), host.texts(tonight))
        assertEquals(WidgetDayChange.midnightMillis(today + 2), alarms.scheduledAlarms.single().triggerAtMs)

        // The plan is followed for the day it is now, not the day the process started on.
        plan.meals.value += meal(3, today + 1, FakeMealPlanRepository.BREAKFAST, "Porridge")
        settle()
        assertEquals(listOf("Tonight", "Breakfast", "Porridge", "Dinner", "Tomorrow's soup"), host.texts(tonight))
    }

    @Test
    fun theListAloneNeedsNoMidnight() = runTest {
        placeList()
        widgets.update()

        assertNull(alarms.peekNextScheduledAlarm())
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, app.packageManager.getComponentEnabledSetting(clockReceiver)
        )
    }

    @Test
    fun midnightIsTheLocalOne() {
        val day = 20_719L // 2026-09-23
        assertEquals(day * 86_400_000L, WidgetDayChange.midnightMillis(day, TimeZone.getTimeZone("UTC")))
        assertEquals(day * 86_400_000L - 2 * 3_600_000L, WidgetDayChange.midnightMillis(day, TimeZone.getTimeZone("Europe/Paris")))
        assertEquals(day * 86_400_000L + 4 * 3_600_000L, WidgetDayChange.midnightMillis(day, TimeZone.getTimeZone("America/New_York")))
    }
}
