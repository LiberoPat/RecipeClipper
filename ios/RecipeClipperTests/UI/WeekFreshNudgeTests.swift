import XCTest
@testable import RecipeClipper

/// Fresh food's notes on the Week's meals (#396): which meals, the swap, and the flag.
/// Android's `WeekFreshNudgeTest`.
@MainActor
final class WeekFreshNudgeTests: XCTestCase {
    private let today = FakePlanCalendar.wednesday
    private let calendar = FakePlanCalendar()
    private let plan = FakeMealPlanRepository()
    private let groceries = FakeGroceryRepository()
    private let lunch = FakeMealPlanRepository.lunch
    private let dinner = FakeMealPlanRepository.dinner

    private func viewModel(freshFood: Bool = true) -> WeekViewModel {
        WeekViewModel(plan: plan, recipes: FakeRecipeRepository(), calendar: calendar, sleep: immediateSleep,
                      groceries: groceries, freshFood: freshFood)
    }

    private func planned(_ recipeId: Int64, _ day: Int64, _ type: Int64, _ lines: String...) async {
        await plan.addRecipe(recipeId: recipeId, day: day, mealTypeId: type, servings: nil)
        let meal = plan.meals.value.last!
        groceries.planned.append(PlannedIngredients(
            entryId: meal.id, day: day, servings: nil, recipeId: recipeId, title: "Recipe \(recipeId)",
            ingredients: lines, yield: nil, language: "en"
        ))
    }

    func testTheSwapTradesTwoMealsDaysAndEachKeepsItsMealType() async {
        await planned(7, today, lunch, "1 lb spaghetti")
        await planned(9, today + 6, dinner, "2 salmon fillets")
        let pasta = plan.meals.value[0], salmon = plan.meals.value[1]
        let vm = viewModel()
        await settleMain()

        let nudge = vm.uiState.freshNudges[salmon.id]!
        XCTAssertEqual(nudge.swapKey, pasta.id)
        XCTAssertEqual(nudge.swapDay, today)
        XCTAssertEqual(nudge.keeps, "1-2")
        XCTAssertEqual(nudge.name, "Salmon fillets")
        XCTAssertEqual(plan.meals.value.map(\.day), [today, today + 6]) // not moved by the note

        vm.onFreshSwap(salmon.id)
        await settleMain()
        let after = Dictionary(uniqueKeysWithValues: plan.meals.value.map { ($0.id, $0) })
        XCTAssertEqual(after[salmon.id]?.day, today)
        XCTAssertEqual(after[salmon.id]?.mealTypeId, dinner)
        XCTAssertEqual(after[pasta.id]?.day, today + 6)
        XCTAssertEqual(after[pasta.id]?.mealTypeId, lunch)
        XCTAssertTrue(vm.uiState.freshNudges.isEmpty) // swapped, so nothing is late
    }

    func testANoteIsCountedFromThePlansFirstDayAndFollowsThePlan() async {
        await planned(9, today + 6, dinner, "2 salmon fillets")
        await plan.addNote("Eat out", day: today + 1, mealTypeId: dinner)
        let salmon = plan.meals.value[0]
        let vm = viewModel()
        await settleMain()
        // From the plan's first day (tomorrow's note): 5 days on, past the 1-2.
        XCTAssertEqual(Set(vm.uiState.freshNudges.keys), [salmon.id])

        // Planned for the day after, nothing is late.
        await plan.move(entryId: salmon.id, day: today + 2, mealTypeId: dinner)
        await settleMain()
        XCTAssertTrue(vm.uiState.freshNudges.isEmpty)
    }

    func testAPerishableMealWithNothingToSwapWithHasNoNote() async {
        await planned(9, today + 6, dinner, "2 salmon fillets")
        await planned(7, today + 8, dinner, "1 lb spaghetti")
        let vm = viewModel()
        await settleMain()
        XCTAssertTrue(vm.uiState.freshNudges.isEmpty) // the salmon is the plan's first meal
    }

    func testWithoutTheFlagOrTheRepositoryThereAreNoNotesAndASwapDoesNothing() async {
        await planned(7, today, dinner, "1 lb spaghetti")
        await planned(9, today + 5, dinner, "2 salmon fillets")
        let off = viewModel(freshFood: false)
        let without = WeekViewModel(plan: plan, recipes: FakeRecipeRepository(), calendar: calendar, sleep: immediateSleep)
        await settleMain()
        XCTAssertTrue(off.uiState.freshNudges.isEmpty)
        XCTAssertTrue(without.uiState.freshNudges.isEmpty)
        off.onFreshSwap(plan.meals.value[1].id)
        await settleMain()
        XCTAssertEqual(plan.meals.value.map(\.day), [today, today + 5])
    }
}
