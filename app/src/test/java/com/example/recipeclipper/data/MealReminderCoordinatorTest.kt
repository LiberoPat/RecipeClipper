package com.example.recipeclipper.data

import com.example.recipeclipper.data.flags.FeatureFlags
import com.example.recipeclipper.data.flags.Flag
import com.example.recipeclipper.data.flags.FlagRegistry
import com.example.recipeclipper.data.model.MealReminderKind
import com.example.recipeclipper.data.model.MealReminders
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.Recipe
import com.example.recipeclipper.fake.FakeAppPreferences
import com.example.recipeclipper.fake.FakeFeatureFlagStore
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.BREAKFAST
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/**
 * Which reminder alarms are armed, and what the alarm that fires builds from the database (#397).
 * The receiver does only what [MealReminderCoordinator.due] says, so these are its decisions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MealReminderCoordinatorTest {

    private val today = 20_000L
    private val utc = TimeZone.getTimeZone("UTC")
    private var now = PlanDays.utcMillis(today) + 8 * 3_600_000L // 8:00 UTC

    private val plan = FakeMealPlanRepository()
    private val recipes = FakeRecipeRepository()
    private val preferences = FakeAppPreferences(eveningReminder = true, tonightReminder = false)
    private val flagStore = FakeFeatureFlagStore()
    private val flags = FeatureFlags(flagStore, FlagRegistry.definitions, isDebug = true)

    private class RecordingScheduler : MealReminderScheduler {
        val replaced = mutableListOf<List<MealReminderAlarm>>()
        val armed = mutableListOf<MealReminderAlarm>()
        override fun replaceAll(alarms: List<MealReminderAlarm>) {
            replaced += alarms
        }
        override fun arm(alarm: MealReminderAlarm) {
            armed += alarm
        }
    }

    private val scheduler = RecordingScheduler()
    private val coordinator = MealReminderCoordinator(plan, recipes, preferences, flags, scheduler, { now }).apply { useZone(utc) }

    private fun started(): TestScope = TestScope(StandardTestDispatcher()).also {
        coordinator.start(it)
        it.runCurrent()
    }

    private fun plan(id: Long, title: String, day: Long, type: Long = DINNER, steps: List<String> = emptyList(), language: String = "en") {
        plan.titles[id] = title
        recipes.recipes.value += id to Recipe(
            name = title, image = null, ingredients = listOf("1 thing"), instructions = steps,
            prepTime = null, cookTime = null, totalTime = null, yield = null, sourceUrl = "https://x/$id", id = id, language = language
        )
        plan.meals.value += com.example.recipeclipper.data.model.PlannedMeal(
            id = id, day = day, mealTypeId = type, recipeId = id, title = title, imageUrl = null, servings = null, note = null
        )
    }

    // The settings, and so the alarms

    @Test fun `the evening alarm is armed for 19 00, today while it is still ahead`() {
        val scope = started()
        assertEquals(listOf(MealReminderAlarm(MealReminderKind.EVENING, today, 19 * 60)), scheduler.replaced.last())
    }

    @Test fun `after its time the alarm is for tomorrow`() {
        now = PlanDays.utcMillis(today) + 19 * 3_600_000L
        val scope = started()
        assertEquals(today + 1, scheduler.replaced.last().single().day)
    }

    @Test fun `the tonight alarm only when its switch is on, at its own time`() {
        val scope = started()
        preferences.tonightReminder = true
        preferences.tonightReminderMinute = 17 * 60 + 30
        scope.runCurrent()
        assertEquals(
            listOf(
                MealReminderAlarm(MealReminderKind.EVENING, today, 19 * 60),
                MealReminderAlarm(MealReminderKind.TONIGHT, today, 17 * 60 + 30)
            ),
            scheduler.replaced.last()
        )
    }

    @Test fun `switching the evening reminder off cancels its alarm, and on arms it again`() {
        val scope = started()
        preferences.eveningReminder = false
        scope.runCurrent()
        assertEquals(emptyList<MealReminderAlarm>(), scheduler.replaced.last())

        preferences.eveningReminder = true
        scope.runCurrent()
        assertEquals(1, scheduler.replaced.last().size)
    }

    @Test fun `changing the time moves the alarm`() {
        val scope = started()
        preferences.eveningReminderMinute = 20 * 60
        scope.runCurrent()
        assertEquals(20 * 60, scheduler.replaced.last().single().minute)
    }

    @Test fun `the flag off arms nothing, and on arms again`() {
        val scope = started()
        flagStore.setOverride(Flag.EVENING_REMINDERS.key, false)
        scope.runCurrent()
        assertEquals(emptyList<MealReminderAlarm>(), scheduler.replaced.last())

        flagStore.setOverride(Flag.EVENING_REMINDERS.key, null)
        scope.runCurrent()
        assertEquals(1, scheduler.replaced.last().size)
    }

    @Test fun `a boot or a time change re-arms every wanted kind from now`() {
        preferences.tonightReminder = true
        now = PlanDays.utcMillis(today) + 17 * 3_600_000L // after 16:00, before 19:00
        coordinator.refresh()
        assertEquals(
            listOf(
                MealReminderAlarm(MealReminderKind.EVENING, today, 19 * 60),
                MealReminderAlarm(MealReminderKind.TONIGHT, today + 1, 16 * 60)
            ),
            scheduler.replaced.last()
        )
    }

    @Test fun `after one fires its next is a day later at the earliest, and the other is left alone`() {
        // An alarm a moment early: the clock still reads before 19:00.
        now = PlanDays.utcMillis(today) + 18 * 3_600_000L + 59 * 60_000L + 59_000L
        coordinator.rearm(MealReminderKind.EVENING, today)
        assertEquals(listOf(MealReminderAlarm(MealReminderKind.EVENING, today + 1, 19 * 60)), scheduler.armed)
        assertEquals(emptyList<List<MealReminderAlarm>>(), scheduler.replaced)
    }

    @Test fun `nothing is armed after a fire once the setting went off`() {
        preferences.eveningReminder = false
        coordinator.rearm(MealReminderKind.EVENING, today)
        assertEquals(emptyList<MealReminderAlarm>(), scheduler.armed)
    }

    // What the alarm that fires builds

    @Test fun `the evening alarm names tomorrow's meals in meal type order`() = runTest {
        plan(1, "Lemon chicken", today + 1)
        plan(2, "Porridge", today + 1, type = BREAKFAST)
        plan(3, "Today's stew", today)
        val reminder = coordinator.due(MealReminderKind.EVENING, today)!!
        assertEquals(listOf("Porridge", "Lemon chicken"), reminder.titles)
        assertEquals(2L, reminder.recipeId)
    }

    @Test fun `a planned note shows its text and opens no recipe`() = runTest {
        plan.addNote("Leftovers", today + 1, DINNER)
        val reminder = coordinator.due(MealReminderKind.EVENING, today)!!
        assertEquals(listOf("Leftovers"), reminder.titles)
        assertNull(reminder.recipeId)
    }

    @Test fun `nothing planned tomorrow is no reminder`() = runTest {
        plan(1, "Today's stew", today)
        assertNull(coordinator.due(MealReminderKind.EVENING, today))
    }

    @Test fun `a meal moved away since is not announced`() = runTest {
        plan(1, "Lemon chicken", today + 1)
        plan.move(1, today + 3, DINNER)
        assertNull(coordinator.due(MealReminderKind.EVENING, today))
    }

    @Test fun `a meal removed since is not announced`() = runTest {
        plan(1, "Lemon chicken", today + 1)
        plan.delete(1)
        assertNull(coordinator.due(MealReminderKind.EVENING, today))
    }

    @Test fun `the setting off since the alarm was armed sends nothing`() = runTest {
        plan(1, "Lemon chicken", today + 1)
        preferences.eveningReminder = false
        assertNull(coordinator.due(MealReminderKind.EVENING, today))
    }

    @Test fun `the flag off sends nothing`() = runTest {
        plan(1, "Lemon chicken", today + 1)
        flagStore.setOverride(Flag.EVENING_REMINDERS.key, false)
        assertNull(coordinator.due(MealReminderKind.EVENING, today))
    }

    @Test fun `an alarm that comes a day late sends nothing`() = runTest {
        plan(1, "Lemon chicken", today + 1)
        now = PlanDays.utcMillis(today + 1) + 3_600_000L
        assertNull(coordinator.due(MealReminderKind.EVENING, today))
    }

    @Test fun `the tonight alarm names today's dinner, not breakfast, and only when on`() = runTest {
        plan(1, "Lemon chicken", today)
        plan(2, "Porridge", today, type = BREAKFAST)
        assertNull(coordinator.due(MealReminderKind.TONIGHT, today))
        preferences.tonightReminder = true
        assertEquals(listOf("Lemon chicken"), coordinator.due(MealReminderKind.TONIGHT, today)!!.titles)
    }

    @Test fun `a planned recipe's ahead step is quoted`() = runTest {
        plan(1, "Lemon chicken", today + 1, steps = listOf("Mix the marinade.", "Marinate at least 8 hours or overnight."))
        val reminder = coordinator.due(MealReminderKind.EVENING, today)!!
        assertEquals(listOf("Marinate at least 8 hours or overnight."), reminder.ahead.map { it.quote })
        assertEquals(listOf("Lemon chicken"), reminder.ahead.map { it.title })
    }

    @Test fun `the ahead step is found in the recipe's own language`() = runTest {
        // An English phrase in a German recipe is not read with English words, and the reverse.
        plan(1, "Hähnchen", today + 1, steps = listOf("Das Fleisch über Nacht marinieren."), language = "de")
        plan(2, "Chicken", today + 1, steps = listOf("Das Fleisch über Nacht marinieren."), language = "en")
        val reminder = coordinator.due(MealReminderKind.EVENING, today)!!
        assertEquals(listOf("Hähnchen"), reminder.ahead.map { it.title })
    }

    @Test fun `the tonight reminder quotes no ahead step`() = runTest {
        plan(1, "Lemon chicken", today, steps = listOf("Marinate overnight."))
        preferences.tonightReminder = true
        assertEquals(emptyList<Any>(), coordinator.due(MealReminderKind.TONIGHT, today)!!.ahead)
    }

    @Test fun `the default times are 19 00 and 16 00`() {
        assertEquals(MealReminders.EVENING_MINUTE, FakeAppPreferences().eveningReminderMinute)
        assertEquals(MealReminders.TONIGHT_MINUTE, FakeAppPreferences().tonightReminderMinute)
    }
}
