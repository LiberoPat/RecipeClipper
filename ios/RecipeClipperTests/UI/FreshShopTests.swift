import XCTest
@testable import RecipeClipper

/// Shop for this week in two trips (#396): the split, the two Add buttons and the flag.
/// Android's `WhatINeedFreshViewModelTest`.
@MainActor
final class FreshShopTests: XCTestCase {
    private let today = FakePlanCalendar.wednesday
    private let calendar = FakePlanCalendar()
    private let groceries = FakeGroceryRepository()

    override func setUp() {
        super.setUp()
        groceries.planned = [
            PlannedIngredients(entryId: 1, day: today + 1, servings: nil, recipeId: 7, title: "Pasta",
                               ingredients: ["1 lb spaghetti", "2 tbsp olive oil", "1 bag baby spinach"], yield: nil, language: "en"),
            PlannedIngredients(entryId: 2, day: today + 2, servings: nil, recipeId: 8, title: "Fish tacos",
                               ingredients: ["1 lb cod fillets", "8 tortillas"], yield: nil, language: "en"),
            PlannedIngredients(entryId: 3, day: today + 5, servings: nil, recipeId: 9, title: "Salmon bowls",
                               ingredients: ["2 salmon fillets", "1 bunch basil", "1 cup rice", "4 oz smoked salmon"], yield: nil, language: "en"),
            PlannedIngredients(entryId: 4, day: today + 6, servings: nil, recipeId: 10, title: "Spinach salad",
                               ingredients: ["1 bag baby spinach"], yield: nil, language: "en"),
        ]
    }

    private func item(_ id: Int64, _ name: String) -> PantryItem {
        PantryItem(id: id, name: name, quantity: nil, language: "en", aisle: .other, inStock: true, alwaysHave: false, purchasedDay: nil, expiresDay: nil)
    }

    private func viewModel(
        _ pantry: FakePantryRepository = FakePantryRepository(), freshFood: Bool = true, withCalendar: Bool = true
    ) async -> WhatINeedViewModel {
        let vm = WhatINeedViewModel(
            weekStart: today, groceries: groceries, pantry: pantry, preferences: FakeAppPreferences(),
            calendar: withCalendar ? calendar : nil, freshFood: freshFood
        )
        await vm.loading?.value
        await settleMain()
        return vm
    }

    func testAPerishableOnlyForMealsFourOrMoreDaysAwayGoesLaterEachWithTheDayItIsFor() async {
        let vm = await viewModel()
        let trips = vm.uiState.trips!
        XCTAssertEqual(trips.later.map(\.row.name), ["salmon fillets", "basil"])
        XCTAssertEqual(trips.later.map(\.firstDay), [today + 5, today + 5])
        XCTAssertEqual(trips.later.map(\.group.key), ["fish", "herbs"])
        // The spinach is also for tomorrow's pasta, the cod two days away, smoked salmon isn't fresh.
        XCTAssertEqual(
            trips.now.map(\.name),
            ["spaghetti", "olive oil", "baby spinach", "cod fillets", "tortillas", "rice", "smoked salmon"]
        )
    }

    func testTheAmountsAndLinesAreExactlyWhatWhatINeedShows() async {
        let vm = await viewModel()
        let state = vm.uiState
        XCTAssertEqual(state.trips!.later[0].row.lines.map(\.text), ["2 salmon fillets"])
        XCTAssertEqual(state.needs!.buy.count, state.trips!.now.count + state.trips!.later.count)
        for row in state.needs!.buy {
            XCTAssertTrue(state.trips!.now.contains(row) || state.trips!.later.contains { $0.row == row })
        }
    }

    func testAddToGroceriesAddsBuyNowsLinesAndTheLaterGroupHasItsOwnButtonEachOnce() async {
        let vm = await viewModel()
        vm.onAddBuyToGroceries()
        await settleMain()
        let now = groceries.items.value.map(\.text)
        XCTAssertEqual(
            now.sorted(),
            ["1 lb spaghetti", "2 tbsp olive oil", "1 bag baby spinach", "1 bag baby spinach", "1 lb cod fillets", "8 tortillas", "1 cup rice", "4 oz smoked salmon"].sorted()
        )
        XCTAssertTrue(vm.uiState.added)
        XCTAssertFalse(vm.uiState.addedLater)

        vm.onAddBuyToGroceries()
        vm.onAddLaterToGroceries()
        vm.onAddLaterToGroceries()
        await settleMain()
        XCTAssertEqual(groceries.items.value.count, now.count + 2)
        XCTAssertEqual(Array(groceries.items.value.map(\.text).suffix(2)), ["2 salmon fillets", "1 bunch basil"])
        XCTAssertTrue(vm.uiState.addedLater)
    }

    func testAnIngredientInThePantryIsHaveNotLater() async {
        let vm = await viewModel(FakePantryRepository([item(1, "basil")]))
        XCTAssertEqual(vm.uiState.trips!.later.map(\.row.name), ["salmon fillets"])
        XCTAssertTrue(vm.uiState.needs!.have.contains { $0.name == "basil" })
    }

    func testTheSplitFollowsThePantryLive() async {
        let pantry = FakePantryRepository()
        let vm = await viewModel(pantry)
        XCTAssertEqual(vm.uiState.trips!.later.count, 2)
        _ = await pantry.add(NewPantryItem(name: "salmon fillets", language: "en"))
        await settleMain()
        XCTAssertEqual(vm.uiState.trips!.later.map(\.row.name), ["basil"])
    }

    func testWithTheFlagOffOrWithoutTodayToBuyIsOneListAndAddAddsAllOfIt() async {
        for vm in [await viewModel(freshFood: false), await viewModel(withCalendar: false)] {
            XCTAssertNil(vm.uiState.trips)
            XCTAssertEqual(vm.uiState.buyNow, vm.uiState.needs!.buy)
        }
        let vm = await viewModel(freshFood: false)
        vm.onAddBuyToGroceries()
        await settleMain()
        XCTAssertTrue(groceries.items.value.contains { $0.text == "2 salmon fillets" })
        XCTAssertTrue(groceries.items.value.contains { $0.text == "1 bunch basil" })
    }

    func testNothingIsLaterWhenEveryPerishableIsNeededSooner() async {
        calendar.todayValue = today + 3 // the salmon is now two days away
        let vm = await viewModel()
        XCTAssertTrue(vm.uiState.trips!.later.isEmpty)
        XCTAssertEqual(vm.uiState.needs!.buy, vm.uiState.trips!.now)
    }
}
