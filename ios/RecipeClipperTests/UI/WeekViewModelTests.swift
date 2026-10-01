import XCTest
@testable import RecipeClipper

/// Android's WeekViewModelTest: the Week tab (#49).
@MainActor
final class WeekViewModelTests: XCTestCase {
    private let plan = FakeMealPlanRepository()
    private let recipes = FakeRecipeRepository()
    private let calendar = FakePlanCalendar()
    private let today = FakePlanCalendar.wednesday
    private let thursday = FakePlanCalendar.thursday

    private func viewModel() -> WeekViewModel {
        WeekViewModel(plan: plan, recipes: recipes, calendar: calendar, sleep: immediateSleep)
    }

    private func mealCount(_ vm: WeekViewModel) -> Int { vm.uiState.days.reduce(0) { $0 + $1.meals.count } }

    // MARK: Rolling weeks (#232)

    func testOpensOnTodayAndTheWeekIsTheSevenDaysFromIt() async {
        calendar.todayValue = thursday
        let vm = viewModel()
        await settleMain()
        let state = vm.uiState
        XCTAssertEqual(state.topDay, thursday)
        XCTAssertEqual(state.weekStart, thursday)
        XCTAssertTrue(state.isThisWeek)
        // Thursday to Wednesday.
        XCTAssertEqual(state.days.map(\.day), Array(thursday...(thursday + 6)))
        XCTAssertEqual(PlanDays.dayOfWeek(thursday + 6), 4)
        // The list: a year back, two ahead.
        XCTAssertEqual(state.firstDay, thursday - 365)
        XCTAssertEqual(state.lastDay, thursday + 730)
        XCTAssertNil(state.scrollTo)
    }

    func testTheLocalesFirstDayNoLongerStartsTheWeek() async {
        calendar.firstDay = FakePlanCalendar.sundayFirst
        let vm = viewModel()
        await settleMain()
        XCTAssertEqual(vm.uiState.days.first?.day, today)
    }

    func testFreeScrollingMovesTheWeekToTheBlockAtTheTop() async {
        calendar.todayValue = thursday
        let vm = viewModel()
        await settleMain()

        vm.onTopDayChanged(thursday + 6)
        XCTAssertEqual(vm.uiState.weekStart, thursday)
        vm.onTopDayChanged(thursday + 10)
        XCTAssertEqual(vm.uiState.weekStart, thursday + 7)
        XCTAssertFalse(vm.uiState.isThisWeek)
        vm.onTopDayChanged(thursday - 1)
        XCTAssertEqual(vm.uiState.weekStart, thursday - 7)
    }

    func testArrowsSnapToTheNextOrPreviousBlockCountedFromTheOneAtTheTop() async {
        calendar.todayValue = thursday
        let vm = viewModel()
        await settleMain()
        vm.onTopDayChanged(thursday + 10) // a Sunday, mid-block

        vm.onNextWeek()
        let first = vm.uiState.scrollTo!
        XCTAssertEqual(first.day, thursday + 14)
        XCTAssertTrue(first.animate)
        XCTAssertEqual(vm.uiState.weekStart, thursday + 14)

        // Pressed again before the scroll lands: counts from where it's going.
        vm.onNextWeek()
        let second = vm.uiState.scrollTo!
        XCTAssertEqual(second.day, thursday + 21)
        vm.onScrollHandled(first.id, topDay: nil) // the first was cut short: not this one's to clear
        XCTAssertEqual(vm.uiState.scrollTo, second)
        vm.onScrollHandled(second.id, topDay: nil)
        XCTAssertNil(vm.uiState.scrollTo)
        XCTAssertEqual(vm.uiState.topDay, thursday + 21)

        // Free scrolling back into this week, then ‹: the block before this one.
        vm.onTopDayChanged(thursday + 3)
        vm.onPreviousWeek()
        XCTAssertEqual(vm.uiState.scrollTo?.day, thursday - 7)
        XCTAssertEqual(vm.uiState.weekStart, thursday - 7)
    }

    func testWhileARequestedScrollRunsTheDaysItPassesDontMoveTheWeek() async {
        calendar.todayValue = thursday
        let vm = viewModel()
        await settleMain()
        vm.onNextWeek()
        let target = vm.uiState.scrollTo!

        vm.onTopDayChanged(thursday + 3)
        XCTAssertEqual(vm.uiState.weekStart, thursday + 7)

        // Stopped on the way: the day it stopped on is the top.
        vm.onScrollHandled(target.id, topDay: thursday + 4)
        XCTAssertNil(vm.uiState.scrollTo)
        XCTAssertEqual(vm.uiState.topDay, thursday + 4)
        XCTAssertTrue(vm.uiState.isThisWeek)
    }

    func testTodayScrollsBackFromTodayAsItIsNow() async {
        calendar.todayValue = thursday
        await plan.addRecipe(recipeId: 8, day: thursday + 1, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = viewModel()
        await settleMain()
        vm.onNextWeek()
        vm.onNextWeek()
        XCTAssertFalse(vm.uiState.isThisWeek)

        vm.onToday()
        await settleMain()
        XCTAssertEqual(vm.uiState.scrollTo?.day, thursday)
        XCTAssertEqual(vm.uiState.scrollTo?.animate, true)
        XCTAssertTrue(vm.uiState.isThisWeek)
        XCTAssertEqual(mealCount(vm), 1)

        // The next morning, the weeks start from Friday.
        calendar.todayValue = thursday + 1
        vm.onToday()
        XCTAssertEqual(vm.uiState.today, thursday + 1)
        XCTAssertEqual(vm.uiState.weekStart, thursday + 1)
    }

    func testComingBackOnANewDayPutsTheNewTodayAtTheTop() async {
        calendar.todayValue = thursday
        let vm = viewModel()
        await settleMain()
        vm.onTopDayChanged(thursday + 20)

        vm.onScreenResumed() // the same day: left where it was
        XCTAssertNil(vm.uiState.scrollTo)
        XCTAssertEqual(vm.uiState.topDay, thursday + 20)

        calendar.todayValue = thursday + 1
        vm.onScreenResumed()
        XCTAssertEqual(vm.uiState.today, thursday + 1)
        XCTAssertEqual(vm.uiState.scrollTo?.day, thursday + 1)
        XCTAssertEqual(vm.uiState.scrollTo?.animate, false)
        XCTAssertEqual(vm.uiState.weekStart, thursday + 1)
    }

    func testOnlyAWindowOfMealsAroundTheTopIsHeldHoweverFarTheListScrolls() async {
        calendar.todayValue = thursday
        await plan.addNote("Today", day: thursday, mealTypeId: FakeMealPlanRepository.dinner)
        await plan.addNote("Far", day: thursday + 700, mealTypeId: FakeMealPlanRepository.dinner)
        let vm = viewModel()
        await settleMain()
        XCTAssertEqual(vm.uiState.meals.values.flatMap { $0 }.map(\.note), ["Today"])

        for day in stride(from: thursday, through: thursday + 700, by: 3) { vm.onTopDayChanged(day) }
        vm.onTopDayChanged(thursday + 700)
        await settleMain()
        let far = vm.uiState
        XCTAssertEqual(far.loadedTo - far.loadedFrom + 1, WeekViewModel.windowDays)
        XCTAssertTrue(far.isLoaded(far.weekStart - 7) && far.isLoaded(far.weekStart + 13))
        XCTAssertEqual(far.meals.values.flatMap { $0 }.map(\.note), ["Far"])
        XCTAssertTrue(far.meals.keys.allSatisfy { far.isLoaded($0) })
        // A query every four weeks or so, never one per day scrolled.
        XCTAssertLessThanOrEqual(plan.observedRanges.count, 30)
        XCTAssertTrue(plan.observedRanges.allSatisfy { Int64($0.count) == WeekViewModel.windowDays })

        for day in stride(from: thursday + 700, through: thursday, by: -5) { vm.onTopDayChanged(day) }
        vm.onTopDayChanged(thursday)
        await settleMain()
        let back = vm.uiState
        XCTAssertEqual(back.loadedTo - back.loadedFrom + 1, WeekViewModel.windowDays)
        XCTAssertEqual(back.meals.values.flatMap { $0 }.map(\.note), ["Today"])
        XCTAssertEqual(back.firstDay, thursday - 365) // the list's days never moved
        XCTAssertEqual(back.lastDay, thursday + 730)
    }

    func testTheNextBlocksMealsShowOnceItIsAtTheTop() async {
        await plan.addRecipe(recipeId: 8, day: today + 7, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = viewModel()
        await settleMain()

        vm.onNextWeek()
        await settleMain()
        XCTAssertEqual(vm.uiState.weekStart, today + 7)
        XCTAssertEqual(mealCount(vm), 1)
    }

    func testMealsLandOnTheirDayInMealTypeOrder() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: 4)
        await plan.addNote("Leftovers", day: today, mealTypeId: FakeMealPlanRepository.lunch)
        await plan.addRecipe(recipeId: 8, day: today + 7, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = viewModel()
        await settleMain()

        let wednesday = vm.uiState.days.first { $0.day == today }
        XCTAssertEqual(wednesday?.meals.map { $0.note ?? $0.title ?? "" }, ["Leftovers", "Recipe 7"])
        XCTAssertEqual(vm.uiState.days.reduce(0) { $0 + $1.meals.count }, 2)
    }

    func testPlusOnADayAddsARecipeFromHistoryAsDinnerByDefault() async {
        recipes.history.send([testSummary(5, title: "Soup")])
        let vm = viewModel()
        await settleMain()

        vm.onAddToDay(today + 1)
        await settleMain()
        XCTAssertEqual(vm.uiState.adding?.mealTypeId, FakeMealPlanRepository.dinner)
        XCTAssertEqual(vm.uiState.adding?.results?.map(\.title), ["Soup"])

        vm.onAddRecipe(5)
        await settleMain()
        XCTAssertNil(vm.uiState.adding)
        let meal = plan.meals.value.first
        XCTAssertEqual(meal?.recipeId, 5)
        XCTAssertEqual(meal?.day, today + 1)
        XCTAssertEqual(meal?.mealTypeId, FakeMealPlanRepository.dinner)
        XCTAssertNil(meal?.servings)
    }

    func testWhatIsTypedCanBePlannedAsANoteInTheChosenMealType() async {
        let vm = viewModel()
        await settleMain()
        vm.onAddToDay(today)
        vm.onAddMealTypeSelected(FakeMealPlanRepository.breakfast)
        vm.onAddQueryChange("  Eat out  ")
        vm.onAddNote()
        await settleMain()

        XCTAssertEqual(plan.meals.value.first?.note, "Eat out")
        XCTAssertEqual(plan.meals.value.first?.mealTypeId, FakeMealPlanRepository.breakfast)
    }

    func testABlankNoteIsNotAdded() async {
        let vm = viewModel()
        await settleMain()
        vm.onAddToDay(today)
        vm.onAddQueryChange("   ")
        vm.onAddNote()
        await settleMain()
        XCTAssertTrue(plan.meals.value.isEmpty)
    }

    func testMovingOffersTodayAndTheNext13DaysAndWritesTheNewSlot() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: 2)
        let vm = viewModel()
        await settleMain()
        vm.onNextWeek() // the strip doesn't follow the week shown
        let meal = vm.uiState.mealsOn(today)[0]

        vm.onMoveStart(meal)
        XCTAssertEqual(vm.uiState.moving?.days, Array(today..<(today + 14)))
        XCTAssertEqual(vm.uiState.moving?.day, today)

        vm.onMoveDaySelected(today + 9)
        vm.onMoveMealTypeSelected(FakeMealPlanRepository.lunch)
        vm.onMoveConfirm()
        await settleMain()

        XCTAssertNil(vm.uiState.moving)
        let moved = plan.meals.value.first
        XCTAssertEqual(moved?.day, today + 9)
        XCTAssertEqual(moved?.mealTypeId, FakeMealPlanRepository.lunch)
        XCTAssertEqual(moved?.servings, 2)
    }

    func testRemovingCanBeUndone() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = viewModel()
        await settleMain()
        let meal = vm.uiState.days.first { $0.day == today }!.meals[0]

        vm.onRemove(meal)
        await settleMain()
        XCTAssertTrue(plan.meals.value.isEmpty)
        XCTAssertEqual(vm.uiState.removed?.label, "Recipe 7")

        vm.onUndoRemove()
        await settleMain()
        XCTAssertNil(vm.uiState.removed)
        XCTAssertEqual(plan.meals.value.map(\.id), [meal.id])
    }

    func testARemovalStandsOnceTheSnackbarGoes() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = viewModel()
        await settleMain()
        vm.onRemove(vm.uiState.days.first { $0.day == today }!.meals[0])
        await settleMain()

        vm.onSnackbarDismissed()
        vm.onUndoRemove()
        await settleMain()
        XCTAssertTrue(plan.meals.value.isEmpty)
    }

    // MARK: Month view (#52)

    private func day(_ year: Int, _ month: Int, _ dayOfMonth: Int) -> Int64 {
        PlanDays.epochDay(year: year, month: month, dayOfMonth: dayOfMonth)
    }

    func testTheMonthViewMarksTheDaysWithMealsInWholeLocaleWeeks() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        await plan.addNote("Leftovers", day: today, mealTypeId: FakeMealPlanRepository.lunch)
        await plan.addNote("Out", day: day(2026, 10, 2), mealTypeId: FakeMealPlanRepository.dinner)
        await plan.addNote("Far", day: day(2026, 11, 20), mealTypeId: FakeMealPlanRepository.dinner)
        let vm = viewModel()
        await settleMain()

        vm.onShowMonth()
        await settleMain()
        let month = vm.uiState.month!
        XCTAssertEqual(month.monthStart, day(2026, 9, 1))
        XCTAssertTrue(month.isThisMonth)
        XCTAssertEqual(month.days.first?.day, day(2026, 8, 31))
        XCTAssertEqual(month.days.count, 35)
        XCTAssertEqual(month.days.first { $0.day == today }?.mealCount, 2)
        let october2 = month.days.first { $0.day == day(2026, 10, 2) }!
        XCTAssertFalse(october2.inMonth)
        XCTAssertEqual(october2.mealCount, 1)
        XCTAssertEqual(month.days.reduce(0) { $0 + $1.mealCount }, 3)
    }

    func testMonthsStepAcrossTheYearAndThisMonthComesBack() async {
        let vm = viewModel()
        await settleMain()
        vm.onShowMonth()
        for _ in 0..<4 { vm.onNextMonth() }
        await settleMain()
        XCTAssertEqual(vm.uiState.month?.monthStart, day(2027, 1, 1))
        XCTAssertEqual(vm.uiState.month?.isThisMonth, false)
        XCTAssertEqual(vm.uiState.month?.days.first?.day, day(2026, 12, 28))

        vm.onThisMonth()
        await settleMain()
        XCTAssertEqual(vm.uiState.month?.isThisMonth, true)
    }

    func testTheMonthShownIsTheOneHoldingMostOfTheWeek() async {
        let vm = viewModel()
        await settleMain()
        vm.onNextWeek() // Sep 30 – Oct 6: mostly October
        vm.onShowMonth()
        await settleMain()
        XCTAssertEqual(vm.uiState.month?.monthStart, day(2026, 10, 1))
    }

    func testTappingADayInTheMonthScrollsTheDaysToIt() async {
        let tapped = day(2026, 10, 15) // a Thursday
        await plan.addNote("Dinner out", day: tapped, mealTypeId: FakeMealPlanRepository.dinner)
        let vm = viewModel()
        await settleMain()
        vm.onShowMonth()
        vm.onNextMonth()
        await settleMain()

        vm.onMonthDaySelected(tapped)
        await settleMain()
        let state = vm.uiState
        XCTAssertNil(state.month)
        XCTAssertEqual(state.scrollTo?.day, tapped)
        XCTAssertEqual(state.scrollTo?.animate, false)
        XCTAssertEqual(state.topDay, tapped)
        // Its block from today (Wednesday): Wednesday 14 to Tuesday 20.
        XCTAssertEqual(state.weekStart, today + 21)
        XCTAssertEqual(state.mealsOn(tapped).map(\.note), ["Dinner out"])

        vm.onScrollHandled(state.scrollTo!.id, topDay: nil)
        XCTAssertNil(vm.uiState.scrollTo)
    }

    func testADayTappedBeyondTheListsDaysMovesTheListAroundIt() async {
        let vm = viewModel()
        await settleMain()
        vm.onShowMonth()
        let far = today + 1_200
        vm.onMonthDaySelected(far)
        await settleMain()

        let state = vm.uiState
        XCTAssertEqual(state.firstDay, far - 365)
        XCTAssertEqual(state.lastDay, far + 730)
        XCTAssertEqual(state.scrollTo?.day, far)
        XCTAssertTrue(state.isLoaded(far))
    }

    func testBackFromTheMonthTheDaysStayWhereTheyWere() async {
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: nil)
        let vm = viewModel()
        await settleMain()
        vm.onShowMonth()
        vm.onShowWeek()
        await settleMain()
        XCTAssertNil(vm.uiState.month)
        XCTAssertNil(vm.uiState.scrollTo)
        XCTAssertEqual(vm.uiState.weekStart, today)
        XCTAssertEqual(mealCount(vm), 1)
    }

    // MARK: Calendar file (#52)

    func testTheWeekShownIsSharedAsAnIcsFileAndAnEmptyWeekIsnt() async {
        let vm = viewModel()
        await settleMain()
        XCTAssertFalse(vm.uiState.hasMeals)
        vm.onShareCalendar()
        XCTAssertNil(vm.uiState.calendarFile)

        plan.titles[7] = "Chicken Adobo"
        await plan.addRecipe(recipeId: 7, day: today, mealTypeId: FakeMealPlanRepository.dinner, servings: 4)
        await plan.addNote("Leftovers", day: today + 7, mealTypeId: FakeMealPlanRepository.lunch)
        await settleMain()
        vm.onShareCalendar()
        let file = vm.uiState.calendarFile
        XCTAssertEqual(file?.fileName, "meal-plan-2026-09-23.ics")
        XCTAssertTrue(file?.text.contains("SUMMARY:Dinner · Chicken Adobo\r\n") ?? false)
        XCTAssertTrue(file?.text.contains("DTSTAMP:20260923T142500Z\r\n") ?? false)
        XCTAssertFalse(file?.text.contains("Leftovers") ?? true)
        XCTAssertEqual(file?.text.components(separatedBy: "BEGIN:VEVENT").count, 2)

        vm.onCalendarShared()
        XCTAssertNil(vm.uiState.calendarFile)
    }
}
