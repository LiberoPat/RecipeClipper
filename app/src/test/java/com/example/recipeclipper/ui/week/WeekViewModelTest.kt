package com.example.recipeclipper.ui.week

import com.example.recipeclipper.MainDispatcherRule
import com.example.recipeclipper.data.model.Menu
import com.example.recipeclipper.data.model.PlanDays
import com.example.recipeclipper.data.model.RecipeSummary
import com.example.recipeclipper.fake.FakeMealPlanRepository
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.BREAKFAST
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.DINNER
import com.example.recipeclipper.fake.FakeMealPlanRepository.Companion.LUNCH
import com.example.recipeclipper.fake.FakePlanCalendar
import com.example.recipeclipper.fake.FakeRecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeekViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plan = FakeMealPlanRepository()
    private val recipes = FakeRecipeRepository()
    private val calendar = FakePlanCalendar()
    private val today = FakePlanCalendar.WEDNESDAY
    private val thursday = FakePlanCalendar.THURSDAY

    private fun viewModel() = WeekViewModel(plan, recipes, calendar)

    // --- Rolling weeks (#232)

    @Test
    fun `opens on today, and the week is the seven days from it`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.today = thursday
        val vm = viewModel()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(thursday, state.topDay)
        assertEquals(thursday, state.weekStart)
        assertTrue(state.isThisWeek)
        // Thursday to Wednesday.
        assertEquals((thursday..thursday + 6).toList(), state.days.map { it.day })
        assertEquals(PlanDays.dayOfWeek(thursday + 6), 4)
        // The list: a year back, two ahead.
        assertEquals(thursday - 365, state.firstDay)
        assertEquals(thursday + 730, state.lastDay)
        assertNull(state.scrollTo)
    }

    @Test
    fun `the locale's first day no longer starts the week`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.firstDayOfWeek = FakePlanCalendar.SUNDAY_FIRST
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(today, vm.uiState.value.days.first().day)
    }

    @Test
    fun `free scrolling moves the week to the block at the top`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.today = thursday
        val vm = viewModel()
        advanceUntilIdle()

        vm.onTopDayChanged(thursday + 6) // still this week's last day
        assertEquals(thursday, vm.uiState.value.weekStart)
        vm.onTopDayChanged(thursday + 10)
        assertEquals(thursday + 7, vm.uiState.value.weekStart)
        assertFalse(vm.uiState.value.isThisWeek)
        vm.onTopDayChanged(thursday - 1)
        assertEquals(thursday - 7, vm.uiState.value.weekStart)
    }

    @Test
    fun `arrows snap to the next or previous block, counted from the one at the top`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.today = thursday
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTopDayChanged(thursday + 10) // a Sunday, mid-block

        vm.onNextWeek()
        val first = vm.uiState.value.scrollTo!!
        assertEquals(thursday + 14, first.day)
        assertTrue(first.animate)
        assertEquals(thursday + 14, vm.uiState.value.weekStart)

        // Pressed again before the scroll lands: counts from where it's going.
        vm.onNextWeek()
        val second = vm.uiState.value.scrollTo!!
        assertEquals(thursday + 21, second.day)
        vm.onScrollHandled(first.id, null) // the first was cut short: not this one's to clear
        assertEquals(second, vm.uiState.value.scrollTo)
        vm.onScrollHandled(second.id, null)
        assertNull(vm.uiState.value.scrollTo)
        assertEquals(thursday + 21, vm.uiState.value.topDay)

        // Free scrolling back into this week, then ‹: the block before this one.
        vm.onTopDayChanged(thursday + 3)
        vm.onPreviousWeek()
        assertEquals(thursday - 7, vm.uiState.value.scrollTo!!.day)
        assertEquals(thursday - 7, vm.uiState.value.weekStart)
    }

    @Test
    fun `while a requested scroll runs, the days it passes don't move the week`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.today = thursday
        val vm = viewModel()
        advanceUntilIdle()
        vm.onNextWeek()
        val target = vm.uiState.value.scrollTo!!

        vm.onTopDayChanged(thursday + 3)
        assertEquals(thursday + 7, vm.uiState.value.weekStart)

        // A finger stopped it on the way: the day it stopped on is the top.
        vm.onScrollHandled(target.id, thursday + 4)
        assertNull(vm.uiState.value.scrollTo)
        assertEquals(thursday + 4, vm.uiState.value.topDay)
        assertTrue(vm.uiState.value.isThisWeek)
    }

    @Test
    fun `Today scrolls back, from today as it is now`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.today = thursday
        plan.addRecipe(8, thursday + 1, DINNER, servings = null)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onNextWeek()
        vm.onNextWeek()
        assertFalse(vm.uiState.value.isThisWeek)

        vm.onToday()
        advanceUntilIdle()
        assertEquals(thursday, vm.uiState.value.scrollTo!!.day)
        assertTrue(vm.uiState.value.scrollTo!!.animate)
        assertTrue(vm.uiState.value.isThisWeek)
        assertEquals(1, vm.uiState.value.days.sumOf { it.meals.size })

        // The next morning, the weeks start from Friday.
        calendar.today = thursday + 1
        vm.onToday()
        assertEquals(thursday + 1, vm.uiState.value.today)
        assertEquals(thursday + 1, vm.uiState.value.weekStart)
    }

    @Test
    fun `coming back on a new day puts the new today at the top`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.today = thursday
        val vm = viewModel()
        advanceUntilIdle()
        vm.onTopDayChanged(thursday + 20)

        vm.onScreenResumed() // the same day: left where it was
        assertNull(vm.uiState.value.scrollTo)
        assertEquals(thursday + 20, vm.uiState.value.topDay)

        calendar.today = thursday + 1
        vm.onScreenResumed()
        val state = vm.uiState.value
        assertEquals(thursday + 1, state.today)
        assertEquals(ScrollTarget(thursday + 1, animate = false, id = state.scrollTo!!.id), state.scrollTo)
        assertEquals(thursday + 1, state.weekStart)
    }

    @Test
    fun `only a window of meals around the top is held, however far the list scrolls`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.today = thursday
        plan.addNote("Today", thursday, DINNER)
        plan.addNote("Far", thursday + 700, DINNER)
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("Today"), vm.uiState.value.meals.values.flatten().map { it.note })

        for (day in thursday..thursday + 700 step 3) vm.onTopDayChanged(day)
        vm.onTopDayChanged(thursday + 700)
        advanceUntilIdle()
        val far = vm.uiState.value
        assertEquals(WeekViewModel.WINDOW_DAYS, far.loadedTo - far.loadedFrom + 1)
        assertTrue(far.isLoaded(far.weekStart - 7) && far.isLoaded(far.weekStart + 13))
        assertEquals(listOf("Far"), far.meals.values.flatten().map { it.note })
        assertTrue(far.meals.keys.all { far.isLoaded(it) })
        // A query every four weeks or so, never one per day scrolled.
        assertTrue(plan.observedRanges.size <= 30)
        assertTrue(plan.observedRanges.all { it.last - it.first + 1 == WeekViewModel.WINDOW_DAYS })

        for (day in thursday + 700 downTo thursday step 5) vm.onTopDayChanged(day)
        vm.onTopDayChanged(thursday)
        advanceUntilIdle()
        val back = vm.uiState.value
        assertEquals(WeekViewModel.WINDOW_DAYS, back.loadedTo - back.loadedFrom + 1)
        assertEquals(listOf("Today"), back.meals.values.flatten().map { it.note })
        assertEquals(thursday - 365, back.firstDay) // the list's days never moved
        assertEquals(thursday + 730, back.lastDay)
    }

    @Test
    fun `meals land on their day, in meal type order`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(7, today, DINNER, servings = 4)
        plan.addNote("Leftovers", today, LUNCH)
        plan.addRecipe(8, today + 7, DINNER, servings = null) // next week
        val vm = viewModel()
        advanceUntilIdle()

        val wednesday = vm.uiState.value.days.single { it.day == today }
        assertEquals(listOf("Leftovers", "Recipe 7"), wednesday.meals.map { it.note ?: it.title })
        assertEquals(2, vm.uiState.value.days.sumOf { it.meals.size })
    }

    @Test
    fun `the next block's meals show once it is at the top`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(8, today + 7, DINNER, servings = null)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onNextWeek()
        advanceUntilIdle()
        assertEquals(today + 7, vm.uiState.value.weekStart)
        assertEquals(1, vm.uiState.value.days.sumOf { it.meals.size })
    }

    @Test
    fun `plus on a day adds a recipe from history, as Dinner by default`() = runTest(mainDispatcherRule.dispatcher) {
        recipes.history.value = listOf(RecipeSummary(5, "Soup", null, null, 0, false))
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddToDay(today + 1)
        advanceUntilIdle()
        assertEquals(DINNER, vm.uiState.value.adding?.mealTypeId)
        assertEquals(listOf("Soup"), vm.uiState.value.adding?.results?.map { it.title })

        vm.onAddRecipe(5)
        advanceUntilIdle()
        assertNull(vm.uiState.value.adding)
        val meal = plan.meals.value.single()
        assertEquals(5L, meal.recipeId)
        assertEquals(today + 1, meal.day)
        assertEquals(DINNER, meal.mealTypeId)
        assertNull(meal.servings)
    }

    @Test
    fun `what is typed can be planned as a note, in the chosen meal type`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onAddToDay(today)
        vm.onAddMealTypeSelected(BREAKFAST)
        vm.onAddQueryChange("  Eat out  ")
        vm.onAddNote()
        advanceUntilIdle()

        val meal = plan.meals.value.single()
        assertEquals("Eat out", meal.note)
        assertEquals(BREAKFAST, meal.mealTypeId)
    }

    @Test
    fun `a blank note is not added`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onAddToDay(today)
        vm.onAddQueryChange("   ")
        vm.onAddNote()
        advanceUntilIdle()
        assertTrue(plan.meals.value.isEmpty())
    }

    @Test
    fun `moving offers today and the next 13 days and writes the new slot`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(7, today, DINNER, servings = 2)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onNextWeek() // the strip doesn't follow the week shown
        val meal = vm.uiState.value.mealsOn(today).single()

        vm.onMoveStart(meal)
        val moving = vm.uiState.value.moving!!
        assertEquals((today until today + 14).toList(), moving.days)
        assertEquals(today, moving.day)

        vm.onMoveDaySelected(today + 9)
        vm.onMoveMealTypeSelected(LUNCH)
        vm.onMoveConfirm()
        advanceUntilIdle()

        assertNull(vm.uiState.value.moving)
        val moved = plan.meals.value.single()
        assertEquals(today + 9, moved.day)
        assertEquals(LUNCH, moved.mealTypeId)
        assertEquals(2, moved.servings)
    }

    @Test
    fun `removing can be undone`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(7, today, DINNER, servings = null)
        val vm = viewModel()
        advanceUntilIdle()
        val meal = vm.uiState.value.days.single { it.day == today }.meals.single()

        vm.onRemove(meal)
        advanceUntilIdle()
        assertTrue(plan.meals.value.isEmpty())
        assertEquals("Recipe 7", vm.uiState.value.removed?.label)

        vm.onUndoRemove()
        advanceUntilIdle()
        assertNull(vm.uiState.value.removed)
        assertEquals(listOf(meal.id), plan.meals.value.map { it.id })
    }

    @Test
    fun `a removal stands once the snackbar goes`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(7, today, DINNER, servings = null)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onRemove(vm.uiState.value.days.single { it.day == today }.meals.single())
        advanceUntilIdle()

        vm.onSnackbarDismissed()
        vm.onUndoRemove()
        advanceUntilIdle()
        assertTrue(plan.meals.value.isEmpty())
    }

    // --- Month view (#52)

    @Test
    fun `the month view marks the days with meals, in whole locale weeks`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(7, today, DINNER, servings = null)
        plan.addNote("Leftovers", today, LUNCH)
        plan.addNote("Out", PlanDays.epochDay(2026, 10, 2), DINNER) // in the grid's last row
        plan.addNote("Far", PlanDays.epochDay(2026, 11, 20), DINNER) // outside the grid
        val vm = viewModel()
        advanceUntilIdle()

        vm.onShowMonth()
        advanceUntilIdle()
        val month = vm.uiState.value.month!!
        assertEquals(PlanDays.epochDay(2026, 9, 1), month.monthStart)
        assertTrue(month.isThisMonth)
        assertEquals(PlanDays.epochDay(2026, 8, 31), month.days.first().day) // a Monday
        assertEquals(35, month.days.size)
        assertEquals(2, month.days.single { it.day == today }.mealCount)
        val october2 = month.days.single { it.day == PlanDays.epochDay(2026, 10, 2) }
        assertFalse(october2.inMonth)
        assertEquals(1, october2.mealCount)
        assertEquals(3, month.days.sumOf { it.mealCount })
    }

    @Test
    fun `months step across the year and This month comes back`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onShowMonth()
        repeat(4) { vm.onNextMonth() }
        advanceUntilIdle()
        assertEquals(PlanDays.epochDay(2027, 1, 1), vm.uiState.value.month!!.monthStart)
        assertFalse(vm.uiState.value.month!!.isThisMonth)
        assertEquals(PlanDays.epochDay(2026, 12, 28), vm.uiState.value.month!!.days.first().day)

        vm.onThisMonth()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.month!!.isThisMonth)
    }

    @Test
    fun `the month shown is the one holding most of the week`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onNextWeek() // Sep 30 – Oct 6: mostly October
        vm.onShowMonth()
        advanceUntilIdle()
        assertEquals(PlanDays.epochDay(2026, 10, 1), vm.uiState.value.month!!.monthStart)
    }

    @Test
    fun `tapping a day in the month scrolls the days to it`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addNote("Dinner out", PlanDays.epochDay(2026, 10, 15), DINNER)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onShowMonth()
        vm.onNextMonth()
        advanceUntilIdle()

        val day = PlanDays.epochDay(2026, 10, 15) // a Thursday
        vm.onMonthDaySelected(day)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertNull(state.month)
        assertEquals(day, state.scrollTo!!.day)
        assertFalse(state.scrollTo!!.animate)
        assertEquals(day, state.topDay)
        // Its block from today (Wednesday): Wednesday 14 to Tuesday 20.
        assertEquals(today + 21, state.weekStart)
        assertEquals(listOf("Dinner out"), state.mealsOn(day).map { it.note })

        vm.onScrollHandled(state.scrollTo!!.id, null)
        assertNull(vm.uiState.value.scrollTo)
    }

    @Test
    fun `a day tapped beyond the list's days moves the list around it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onShowMonth()
        repeat(40) { vm.onNextMonth() }
        val day = today + 1_200
        vm.onMonthDaySelected(day)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(day - 365, state.firstDay)
        assertEquals(day + 730, state.lastDay)
        assertEquals(day, state.scrollTo!!.day)
        assertTrue(state.isLoaded(day))
    }

    @Test
    fun `back from the month the days stay where they were`() = runTest(mainDispatcherRule.dispatcher) {
        plan.addRecipe(7, today, DINNER, servings = null)
        val vm = viewModel()
        advanceUntilIdle()
        vm.onShowMonth()
        vm.onShowWeek()
        advanceUntilIdle()
        assertNull(vm.uiState.value.month)
        assertNull(vm.uiState.value.scrollTo)
        assertEquals(today, vm.uiState.value.weekStart)
        assertEquals(1, vm.uiState.value.days.sumOf { it.meals.size })
    }

    // --- Calendar file (#52)

    @Test
    fun `the week shown is shared as an ics file, and an empty week isn't`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasMeals)
        vm.onShareCalendar()
        assertNull(vm.uiState.value.calendarFile)

        plan.titles[7] = "Chicken Adobo"
        plan.addRecipe(7, today, DINNER, servings = 4)
        plan.addNote("Leftovers", today + 7, LUNCH) // next week: not in the file
        advanceUntilIdle()
        vm.onShareCalendar()
        val file = vm.uiState.value.calendarFile!!
        assertEquals("meal-plan-2026-09-23.ics", file.fileName) // from today, a Wednesday
        assertTrue(file.text.contains("SUMMARY:Dinner · Chicken Adobo\r\n"))
        assertTrue(file.text.contains("DTSTAMP:20260923T142500Z\r\n"))
        assertFalse(file.text.contains("Leftovers"))
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(file.text).count())

        vm.onCalendarShared()
        assertNull(vm.uiState.value.calendarFile)
    }

    // --- Menus keep weekdays across rolling weeks (#232)

    @Test
    fun `a menu saved from a Thursday week keeps its weekdays when applied to another`() = runTest(mainDispatcherRule.dispatcher) {
        calendar.today = thursday // the locale's week starts on Monday
        plan.addNote("Thursday soup", thursday, DINNER)
        plan.addNote("Monday pasta", thursday + 4, DINNER)
        val vm = viewModel()
        advanceUntilIdle()

        vm.onSaveMenu("Usual")
        advanceUntilIdle()
        val id = plan.menus.value.single().id
        // Stored from the locale's Monday, as before rolling weeks.
        assertEquals(listOf(3 to "Thursday soup", 0 to "Monday pasta"), plan.menuMeals[id]!!.map { it.dayOffset to it.meal.note })

        // Opened on Saturday 10, the week runs Saturday to Friday: Monday 12 and Thursday 15.
        calendar.today = thursday + 9
        val later = viewModel()
        advanceUntilIdle()
        later.onApplyMenu(plan.menus.value.single())
        advanceUntilIdle()
        val applied = plan.meals.value.drop(2)
        assertEquals(listOf(thursday + 14 to "Thursday soup", thursday + 11 to "Monday pasta"), applied.map { it.day to it.note })
        assertEquals(listOf(5, 2), applied.map { PlanDays.dayOfWeek(it.day) })
    }

    @Test
    fun `a menu saved before rolling weeks lands on its weekdays`() = runTest(mainDispatcherRule.dispatcher) {
        // Saved by the code before #232 from a Monday-first week: offsets from Monday.
        plan.addNote("Monday pasta", today - 2, DINNER)
        plan.addNote("Friday fish", today + 2, DINNER)
        val old = plan.meals.value
        plan.meals.value = emptyList()
        plan.menuMeals[50] = listOf(FakeMealPlanRepository.MenuMeal(0, old[0]), FakeMealPlanRepository.MenuMeal(4, old[1]))
        plan.menus.value = listOf(Menu(50, "Old", 2))

        val vm = viewModel() // today is a Wednesday: the block runs to Tuesday
        advanceUntilIdle()
        vm.onApplyMenu(plan.menus.value.single())
        advanceUntilIdle()
        assertEquals(listOf(today + 5 to "Monday pasta", today + 2 to "Friday fish"), plan.meals.value.map { it.day to it.note })
    }
}
