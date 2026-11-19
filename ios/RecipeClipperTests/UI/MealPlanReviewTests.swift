import Combine
import XCTest
@testable import RecipeClipper

/// A week of recipes shared in (#352): the review, then Save into the library and Week.
/// Android's `MealPlanReviewViewModelTest`.
@MainActor
final class MealPlanReviewTests: XCTestCase {
    private let today = FakePlanCalendar.wednesday
    private let recipes = FakeRecipeRepository()
    private let week = FakeMealPlanRepository()

    private func recipe(_ name: String, _ day: String) -> String {
        "{\"@type\": \"Recipe\", \"name\": \"\(name)\", \"day\": \"\(day)\", "
            + "\"recipeIngredient\": [\"1 egg\", \"2 cups rice\"], \"recipeInstructions\": [\"Cook.\"]}"
    }

    private func date(_ offset: Int64) -> String {
        let c = PlanDays.civil(today + offset)
        return String(format: "%04d-%02d-%02d", c.year, c.month, c.day)
    }

    /// Recipes named, each on `today` plus an offset (a text, as the share leaves it).
    private func text(_ plan: (String, Int64)...) -> String {
        "[" + plan.map { recipe($0.0, date($0.1)) }.joined(separator: ",") + "]"
    }

    private func viewModel(
        _ text: String, limit: @escaping () -> LibraryLimit = { .unlimited }, entitlements: Entitlements? = nil
    ) -> MealPlanReviewViewModel {
        MealPlanReviewViewModel(
            text: text, recipes: recipes, plan: week, calendar: FakePlanCalendar(), limit: limit, entitlements: entitlements ?? FakeEntitlements()
        )
    }

    private func saved(_ id: Int64) -> Recipe {
        Recipe(
            name: "saved", image: nil, ingredients: ["1 egg"], instructions: ["Cook."], prepTime: nil, cookTime: nil,
            totalTime: nil, yield: nil, sourceUrl: "manual:x", id: id
        )
    }

    private func library(_ count: Int) {
        recipes.history.send((0..<count).map {
            RecipeSummary(
                id: Int64($0 + 1), title: "R\($0)", imageUrl: nil, totalTime: nil, lastViewedAt: Int64($0), isSaved: false,
                lastCookedDay: nil, sourceUrl: "https://example.com/\($0)", isFromChat: false
            )
        })
    }

    func testEveryRecipeIsTickedWithItsDayAndNothingIsSavedBeforeSave() async {
        let vm = viewModel(text(("Omelette", 0), ("Soup", 3), ("Stew", 40)))
        await settleMain()

        let state = vm.uiState
        XCTAssertFalse(state.loading)
        XCTAssertEqual(state.items.map(\.name), ["Omelette", "Soup", "Stew"])
        XCTAssertEqual(state.items.map(\.day), [today, today + 3, today + 40])
        XCTAssertTrue(state.items.allSatisfy { $0.ticked && $0.fits && !$0.passed })
        XCTAssertEqual(state.items[0].preview, "1 egg, 2 cups rice")
        XCTAssertTrue(recipes.addManualCalls.isEmpty)
        XCTAssertTrue(week.meals.value.isEmpty)
    }

    func testADayThatHasPassedOrNoneLeavesTheRecipeUnplannedUntilTheCookPicksOne() async {
        let vm = viewModel("[" + recipe("Old", date(-2)) + "," + recipe("Fine", date(0)) + "," + recipe("Undated", "Tuesday") + "]")
        await settleMain()

        let items = vm.uiState.items
        XCTAssertNil(items[0].day)
        XCTAssertTrue(items[0].passed)
        XCTAssertEqual(items[1].day, today)
        XCTAssertNil(items[2].day)
        XCTAssertFalse(items[2].passed)

        recipes.addManualQueue = [saved(1), saved(2), saved(3)]
        vm.onDay(0, today + 1)
        vm.onSave()
        await settleMain()
        XCTAssertEqual(week.meals.value.map(\.day), [today + 1, today])
    }

    func testSaveSavesEachKeptRecipeAsFromAChatAndPlansItOnItsDayUnderDinner() async {
        let vm = viewModel(text(("Omelette", 0), ("Soup", 1), ("Stew", 2)))
        await settleMain()
        recipes.addManualQueue = [saved(11), saved(12)]
        vm.onTick(1)
        vm.onDay(2, today + 5)
        vm.onSave()
        await settleMain()

        XCTAssertEqual(recipes.addManualCalls.map(\.name), ["Omelette", "Stew"])
        XCTAssertEqual(recipes.addManualFromChat, [true, true])
        XCTAssertEqual(week.meals.value.map(\.recipeId), [11, 12])
        XCTAssertEqual(week.meals.value.map(\.day), [today, today + 5])
        XCTAssertTrue(week.meals.value.allSatisfy { $0.mealTypeId == FakeMealPlanRepository.dinner })
        XCTAssertEqual(vm.uiState.kept, 2)
        XCTAssertEqual(vm.uiState.planned, 2)
        XCTAssertTrue(vm.uiState.finished)
    }

    func testARecipeWithNoDayIsSavedButNotPlannedAndNothingTickedSavesNothing() async {
        let vm = viewModel(text(("A", 0), ("B", 1)))
        await settleMain()
        recipes.addManualQueue = [saved(1), saved(2)]
        vm.onDay(0, nil)
        vm.onDay(1, nil)
        vm.onSave()
        await settleMain()
        XCTAssertEqual(recipes.addManualCalls.count, 2)
        XCTAssertTrue(week.meals.value.isEmpty)
        XCTAssertEqual(vm.uiState.kept, 2)
        XCTAssertEqual(vm.uiState.planned, 0)

        let none = viewModel(text(("A", 0), ("B", 0)))
        await settleMain()
        none.onTick(0)
        none.onTick(1)
        none.onSave()
        await settleMain()
        XCTAssertEqual(recipes.addManualCalls.count, 2)
    }

    func testTheFreeLibrarySaysHowManyFitSavesThoseAndNeverRemovesAnything() async {
        library(48)
        let vm = viewModel(text(("A", 0), ("B", 1), ("C", 2)), limit: { .capped(max: 50) })
        await settleMain()

        XCTAssertEqual(vm.uiState.room, 2)
        XCTAssertEqual(vm.uiState.items.map(\.fits), [true, true, false])
        XCTAssertTrue(vm.uiState.overLimit)
        XCTAssertEqual(vm.uiState.savingCount, 2)

        // Leaving the first out lets the third in: the first that fit, in order.
        vm.onTick(0)
        XCTAssertEqual(vm.uiState.items.map(\.fits), [true, true, true])
        vm.onTick(0)

        recipes.addManualQueue = [saved(1), saved(2)]
        vm.onSave()
        await settleMain()
        XCTAssertEqual(recipes.addManualCalls.map(\.name), ["A", "B"])
        XCTAssertEqual(vm.uiState.items.map(\.name), ["C"])
        XCTAssertEqual(vm.uiState.kept, 2)
        XCTAssertFalse(vm.uiState.finished)
        XCTAssertEqual(week.meals.value.count, 2)
    }

    func testUnlockingMakesRoomForTheRestWhichSaveThenKeeps() async {
        let store = FakeEntitlements()
        library(49)
        let vm = viewModel(
            text(("A", 0), ("B", 1)), limit: { store.state.unlocked ? .unlimited : .capped(max: 50) }, entitlements: store
        )
        await settleMain()
        XCTAssertEqual(vm.uiState.items.map(\.fits), [true, false])

        recipes.addManualQueue = [saved(1)]
        vm.onSave()
        await settleMain()
        XCTAssertEqual(vm.uiState.items.map(\.name), ["B"])
        library(50)
        await settleMain()
        XCTAssertEqual(vm.uiState.room, 0)
        XCTAssertEqual(vm.uiState.savingCount, 0)

        vm.onUnlock()
        await settleMain()
        XCTAssertEqual(store.purchases, 1)
        XCTAssertNil(vm.uiState.room)
        XCTAssertTrue(vm.uiState.items[0].fits)

        recipes.addManualQueue = [saved(2)]
        vm.onSave()
        await settleMain()
        XCTAssertTrue(vm.uiState.finished)
        XCTAssertEqual(vm.uiState.kept, 2)
    }

    func testARecipeTheOneUpsertDoesNotKeepStaysInTheReview() async {
        let vm = viewModel(text(("A", 0), ("B", 1)))
        await settleMain()
        recipes.addManualQueue = [saved(0), saved(5)]
        vm.onSave()
        await settleMain()

        XCTAssertEqual(vm.uiState.items.map(\.name), ["A"])
        XCTAssertEqual(vm.uiState.kept, 1)
        XCTAssertEqual(week.meals.value.map(\.recipeId), [5])
        XCTAssertFalse(vm.uiState.finished)
    }

    func testAFailedSaveKeepsTheRecipeListedAndSaysSo() async {
        let vm = viewModel(text(("A", 0), ("B", 0)))
        await settleMain()
        recipes.addManualQueue = [nil, saved(9)]
        vm.onSave()
        await settleMain()

        XCTAssertTrue(vm.uiState.saveFailed)
        XCTAssertEqual(vm.uiState.items.map(\.name), ["A"])
        XCTAssertFalse(vm.uiState.finished)
    }
}
