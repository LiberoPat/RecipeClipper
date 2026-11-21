package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.MealReminderKind
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.PlannedMeal
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeEntitlements
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * The evening and Tonight reminders under Pro (#397, #413). Without Pro a reminder the cook
 * switched on keeps running; the evening one's default "on" (never stored) arms nothing; with Pro
 * the default stands, as before. The alarms and the receiver's [MealReminderCoordinator.due] agree.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MealReminderProTest {

    private val today = 20_000L
    private val now = PlanDays.utcMillis(today) + 8 * 3_600_000L

    private val plan = FakeMealPlanRepository()
    private val recipes = FakeRecipeRepository()
    private val flags = FeatureFlags(FakeFeatureFlagStore(), FlagRegistry.definitions, isDebug = true)
    private val entitlements = FakeEntitlements()

    private class RecordingScheduler : MealReminderScheduler {
        val replaced = mutableListOf<List<MealReminderAlarm>>()
        override fun replaceAll(alarms: List<MealReminderAlarm>) {
            replaced += alarms
        }
        override fun arm(alarm: MealReminderAlarm) = Unit
    }

    private val scheduler = RecordingScheduler()

    private fun coordinator(preferences: FakeAppPreferences) =
        MealReminderCoordinator(plan, recipes, preferences, flags, scheduler, { now }, DefaultProAccess(flags, entitlements))
            .apply { useZone(TimeZone.getTimeZone("UTC")) }

    private fun started(coordinator: MealReminderCoordinator): TestScope = TestScope(StandardTestDispatcher()).also {
        coordinator.start(it)
        it.runCurrent()
    }

    private fun planTomorrow() {
        plan.titles[1] = "Lemon chicken"
        recipes.recipes.value += 1L to Recipe(
            name = "Lemon chicken", image = null, ingredients = listOf("1 lemon"), instructions = emptyList(),
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "https://x/1", id = 1
        )
        plan.meals.value += PlannedMeal(
            id = 1, day = today + 1, mealTypeId = DINNER, recipeId = 1, title = "Lemon chicken", imageUrl = null, servings = null, note = null
        )
    }

    @Test fun `without Pro the evening reminder's default arms nothing and sends nothing`() = runTest {
        planTomorrow()
        val coordinator = coordinator(FakeAppPreferences(eveningReminder = true, eveningReminderChosen = false))
        started(coordinator)
        assertEquals(emptyList<MealReminderAlarm>(), scheduler.replaced.last())
        assertNull(coordinator.due(MealReminderKind.EVENING, today))
    }

    @Test fun `without Pro a reminder the cook switched on keeps running`() = runTest {
        planTomorrow()
        val preferences = FakeAppPreferences(eveningReminder = true, eveningReminderChosen = true, tonightReminder = true)
        val coordinator = coordinator(preferences)
        started(coordinator)
        assertEquals(setOf(MealReminderKind.EVENING, MealReminderKind.TONIGHT), scheduler.replaced.last().map { it.kind }.toSet())
        assertNotNull(coordinator.due(MealReminderKind.EVENING, today))
    }

    @Test fun `with Pro the default evening reminder runs, as before`() = runTest {
        planTomorrow()
        entitlements.state.value = UnlockState(unlocked = true)
        val coordinator = coordinator(FakeAppPreferences(eveningReminder = true, eveningReminderChosen = false))
        started(coordinator)
        assertEquals(listOf(MealReminderKind.EVENING), scheduler.replaced.last().map { it.kind })
        assertNotNull(coordinator.due(MealReminderKind.EVENING, today))
    }

    @Test fun `buying Pro re-arms the default reminder at once`() = runTest {
        val coordinator = coordinator(FakeAppPreferences(eveningReminder = true, eveningReminderChosen = false))
        val scope = started(coordinator)
        assertTrue(scheduler.replaced.last().isEmpty())
        entitlements.state.value = UnlockState(unlocked = true)
        scope.runCurrent()
        assertEquals(listOf(MealReminderKind.EVENING), scheduler.replaced.last().map { it.kind })
    }
}
