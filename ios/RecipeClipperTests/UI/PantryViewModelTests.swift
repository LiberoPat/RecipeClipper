import XCTest
@testable import RecipeClipper

/// Android's PantryViewModelTest, GroceriesPantryTest and WhatINeedViewModelTest (#51).
@MainActor
final class PantryViewModelTests: XCTestCase {
    private let groceries = FakeGroceryRepository()
    private let calendar = FakePlanCalendar()

    private func item(_ id: Int64, _ name: String, inStock: Bool = true, alwaysHave: Bool = false, aisle: Aisle = .other, expires: Int64? = nil) -> PantryItem {
        PantryItem(id: id, name: name, quantity: nil, language: "en", aisle: aisle, inStock: inStock, alwaysHave: alwaysHave, purchasedDay: 1, expiresDay: expires)
    }

    private func viewModel(_ pantry: FakePantryRepository) async -> PantryViewModel {
        let vm = PantryViewModel(pantry: pantry, groceries: groceries, calendar: calendar, phoneLanguage: { "en" })
        await settleMain()
        return vm
    }

    private func names(_ vm: PantryViewModel) -> [String] { (vm.uiState.sections ?? []).flatMap { $0.items.map(\.name) } }

    // MARK: The Pantry tab

    func testATypedItemIsAddedInStockTodayInItsAisle() async {
        let pantry = FakePantryRepository()
        let vm = await viewModel(pantry)
        vm.onDraftChange(" rice ")
        vm.onAddTyped()
        await settleMain()

        let rice = pantry.items.value.first
        XCTAssertEqual(rice?.name, "rice")
        XCTAssertEqual(rice?.language, "en")
        XCTAssertEqual(rice?.aisle, .grains)
        XCTAssertEqual(rice?.inStock, true)
        XCTAssertEqual(rice?.purchasedDay, calendar.today())
        XCTAssertEqual(vm.uiState.draft, "")
    }

    func testTypingANameAlreadyHerePutsItBackInStock() async {
        let pantry = FakePantryRepository([item(1, "Rice", inStock: false)])
        let vm = await viewModel(pantry)
        vm.onDraftChange("rice")
        vm.onAddTyped()
        await settleMain()
        XCTAssertEqual(pantry.items.value.count, 1)
        XCTAssertEqual(pantry.items.value.first?.inStock, true)
    }

    func testSearchAndSortRearrangeWhatsShown() async {
        let pantry = FakePantryRepository([
            item(1, "milk", aisle: .dairy, expires: 20_725), item(2, "apples", aisle: .produce, expires: 20_730), item(3, "rice", aisle: .grains),
        ])
        let vm = await viewModel(pantry)
        XCTAssertEqual(names(vm), ["apples", "milk", "rice"])
        vm.onSortChange(.expiry)
        XCTAssertEqual(names(vm), ["milk", "apples", "rice"])
        vm.onQueryChange("MI")
        XCTAssertEqual(names(vm), ["milk"])
        vm.onQueryChange("zzz")
        XCTAssertEqual(vm.uiState.sections, [])
        XCTAssertTrue(vm.uiState.hasItems)
    }

    func testRunningOutPutsTheItemOnGroceriesSilentlyOnceAndMarksItOnList() async {
        let pantry = FakePantryRepository([item(1, "milk"), item(2, "rice")])
        let vm = await viewModel(pantry)
        XCTAssertEqual(vm.uiState.onList, [])

        vm.onToggleStock(pantry.items.value[0])
        await settleMain { !self.groceries.items.value.isEmpty }
        XCTAssertEqual(pantry.items.value.first?.inStock, false)
        XCTAssertEqual(groceries.items.value.map(\.text), ["milk"])
        XCTAssertEqual(groceries.items.value.first?.aisle, .dairy)
        XCTAssertNil(vm.uiState.message) // no snackbar
        await settleMain { vm.uiState.onList == [1] }
        XCTAssertEqual(vm.uiState.onList, [1])

        // Back in and out again: still one line on the list.
        vm.onToggleStock(pantry.items.value[0])
        await settleMain { pantry.items.value[0].inStock }
        vm.onToggleStock(pantry.items.value[0])
        await settleMain { !pantry.items.value[0].inStock }
        XCTAssertEqual(groceries.items.value.map(\.text), ["milk"])
    }

    func testTappingOnListTakesOnlyTheItemsOwnUntickedLineOffTheList() async {
        await groceries.add([
            NewGroceryLine(text: " Milk ", language: "en"), NewGroceryLine(text: "1 cup milk", language: "en"),
            NewGroceryLine(text: "flour", language: "en"),
        ])
        await groceries.setChecked([groceries.items.value[2].id], checked: true)
        let pantry = FakePantryRepository([item(1, "milk"), item(2, "flour", inStock: false), item(3, "rice")])
        let vm = await viewModel(pantry)
        // A recipe's "1 cup milk" isn't the item's own line, and a ticked line is already bought.
        await settleMain { vm.uiState.onList == [1] }
        XCTAssertEqual(vm.uiState.onList, [1])

        vm.onTakeOffList(pantry.items.value[0])
        await settleMain { self.groceries.items.value.count == 2 }
        XCTAssertEqual(groceries.items.value.map(\.text), ["1 cup milk", "flour"])
        await settleMain { vm.uiState.onList.isEmpty }
        XCTAssertEqual(vm.uiState.onList, [])
        XCTAssertNil(vm.uiState.message)
    }

    func testBackInStockIsBoughtTodayWithNoOffer() async {
        let pantry = FakePantryRepository([item(1, "milk", inStock: false)])
        let vm = await viewModel(pantry)
        vm.onToggleStock(pantry.items.value[0])
        await settleMain()
        XCTAssertEqual(pantry.items.value.first?.inStock, true)
        XCTAssertEqual(pantry.items.value.first?.purchasedDay, calendar.today())
        XCTAssertNil(vm.uiState.message)
    }

    func testTheEditSheetSavesAndABlankNameIsntSaved() async {
        let pantry = FakePantryRepository([item(1, "oil")])
        let vm = await viewModel(pantry)
        vm.onEdit(pantry.items.value[0])
        vm.onEditName("")
        vm.onEditSave()
        XCTAssertNotNil(vm.uiState.editing)

        vm.onEditName("olive oil")
        vm.onEditQuantity(" half a bottle ")
        vm.onEditAlwaysHave(true)
        vm.onEditExpiry(20_800)
        vm.onEditSave()
        await settleMain()
        let oil = pantry.items.value[0]
        XCTAssertEqual(oil.name, "olive oil")
        XCTAssertEqual(oil.quantity, "half a bottle")
        XCTAssertTrue(oil.alwaysHave)
        XCTAssertEqual(oil.expiresDay, 20_800)
        XCTAssertNil(vm.uiState.editing)
    }

    func testDeletingFromTheEditSheetCanBeUndone() async {
        let pantry = FakePantryRepository([item(1, "oil"), item(2, "rice")])
        let vm = await viewModel(pantry)
        vm.onEdit(pantry.items.value[0])
        vm.onEditDelete()
        await settleMain()
        XCTAssertEqual(pantry.items.value.map(\.name), ["rice"])
        XCTAssertEqual(vm.uiState.message, .deleted(id: 1, name: "oil"))

        vm.onUndoDelete()
        await settleMain()
        XCTAssertEqual(pantry.items.value.map(\.name), ["oil", "rice"])
    }

    // MARK: Grocery check-off into the pantry

    private func groceriesVM(_ pantry: FakePantryRepository) async -> GroceriesViewModel {
        let vm = GroceriesViewModel(repository: groceries, pantry: pantry, calendar: calendar, phoneLanguage: { "en" })
        await settleMain()
        return vm
    }

    private func row(_ vm: GroceriesViewModel, _ text: String) -> GroceryCombiner.Row {
        (vm.uiState.sections ?? []).flatMap(\.rows).first { $0.items.contains { $0.text == text } }!
    }

    /// The sheet as name and ticked.
    private func sheet(_ vm: GroceriesViewModel) -> [String] {
        guard let s = vm.uiState.putAway else { return [] }
        return s.items.map { "\($0.name) \(s.ticked.contains($0.key))" }
    }

    /// Ticks `texts` behind the screen's back, then waits until the screen shows them.
    private func tick(_ vm: GroceriesViewModel, _ texts: Set<String>) async {
        await groceries.setChecked(groceries.items.value.filter { texts.contains($0.text) }.map(\.id), checked: true)
        await settleMain { (vm.uiState.sections ?? []).flatMap(\.rows).flatMap(\.items).filter(\.checked).count == texts.count }
    }

    func testATickOnlyTicks() async {
        let pantry = FakePantryRepository([item(1, "Butter", inStock: false)])
        await groceries.add([NewGroceryLine(text: "250 g unsalted butter", language: "en"), NewGroceryLine(text: "2 cups flour", language: "en")])
        let vm = await groceriesVM(pantry)

        vm.onToggle(row(vm, "250 g unsalted butter"))
        vm.onToggle(row(vm, "2 cups flour"))
        await settleMain { self.groceries.items.value.allSatisfy(\.checked) }
        XCTAssertEqual(pantry.items.value, [item(1, "Butter", inStock: false)])
        XCTAssertNil(vm.uiState.removed)
        XCTAssertNil(vm.uiState.putAway)
    }

    func testDoneShoppingPutsAwayWhatsTickedClearsEveryTickedLineAndOneUndoRevertsItAll() async {
        let pantry = FakePantryRepository([item(1, "Butter", inStock: false)])
        await groceries.add(["250 g unsalted butter", "2 tbsp butter", "2 cups flour", "salt and pepper", "2 onions"]
            .map { NewGroceryLine(text: $0, language: "en") })
        let vm = await groceriesVM(pantry)
        await tick(vm, ["250 g unsalted butter", "2 tbsp butter", "2 cups flour", "salt and pepper"])
        let before = groceries.items.value

        vm.onDoneShopping()
        // One entry per pantry item or ingredient, in the list's order (both butters are the
        // pantry's Butter); what the pantry tracks starts ticked; "salt and pepper" has no name.
        XCTAssertEqual(sheet(vm), ["Butter true", "flour false"])
        XCTAssertEqual(groceries.items.value, before)

        vm.onPutAwayToggle(vm.uiState.putAway!.items[1].key)
        vm.onPutAwayConfirm()
        await settleMain { vm.uiState.removed != nil }
        XCTAssertNil(vm.uiState.putAway)
        XCTAssertEqual(groceries.items.value.map(\.text), ["2 onions"])
        XCTAssertEqual(pantry.items.value.map(\.name), ["Butter", "flour"])
        XCTAssertEqual(pantry.items.value.map(\.inStock), [true, true])
        XCTAssertEqual(pantry.items.value.map(\.purchasedDay), [calendar.today(), calendar.today()])
        XCTAssertEqual(pantry.items.value[1].aisle, .baking) // the grocery item's aisle
        XCTAssertEqual(vm.uiState.removed?.putAway, true)

        vm.onUndoRemove()
        await settleMain { self.groceries.items.value.count == before.count && pantry.items.value.count == 1 }
        XCTAssertEqual(groceries.items.value, before)
        XCTAssertEqual(pantry.items.value, [item(1, "Butter", inStock: false)])
        XCTAssertNil(vm.uiState.removed)
    }

    func testAnItemLeftUntickedInTheSheetStaysOutOfThePantryButLeavesTheList() async {
        let pantry = FakePantryRepository()
        await groceries.add([NewGroceryLine(text: "2 cups flour", language: "en")])
        let vm = await groceriesVM(pantry)
        await tick(vm, ["2 cups flour"])

        vm.onDoneShopping()
        XCTAssertEqual(sheet(vm), ["flour false"])
        vm.onPutAwayConfirm()
        await settleMain { vm.uiState.removed != nil }
        XCTAssertTrue(pantry.items.value.isEmpty)
        XCTAssertTrue(groceries.items.value.isEmpty)
        XCTAssertEqual(vm.uiState.removed?.putAway, false) // "Checked items removed"
    }

    func testWithNothingThePantryCanHoldDoneShoppingClearsAtOnceUndoably() async {
        await groceries.add([NewGroceryLine(text: "salt and pepper", language: "en"), NewGroceryLine(text: "2 eggs", language: "en")])
        let vm = await groceriesVM(FakePantryRepository())
        await tick(vm, ["salt and pepper"])

        vm.onDoneShopping()
        XCTAssertNil(vm.uiState.putAway)
        await settleMain { vm.uiState.removed != nil }
        XCTAssertEqual(groceries.items.value.map(\.text), ["2 eggs"])
        vm.onUndoRemove()
        await settleMain { self.groceries.items.value.count == 2 }
        XCTAssertEqual(groceries.items.value.map(\.text), ["salt and pepper", "2 eggs"])
    }

    func testDismissingTheSheetChangesNothingAndATimedOutSnackbarKeepsThePutAway() async {
        let pantry = FakePantryRepository([item(1, "milk", inStock: false)])
        await groceries.add([NewGroceryLine(text: "1 cup milk", language: "en")])
        let vm = await groceriesVM(pantry)
        await tick(vm, ["1 cup milk"])

        vm.onDoneShopping()
        vm.onPutAwayDismissed()
        await settleMain()
        XCTAssertNil(vm.uiState.putAway)
        XCTAssertEqual(groceries.items.value.count, 1)
        XCTAssertEqual(pantry.items.value[0].inStock, false)

        vm.onDoneShopping()
        vm.onPutAwayConfirm()
        await settleMain { vm.uiState.removed != nil }
        vm.onSnackbarDismissed()
        vm.onUndoRemove()
        await settleMain()
        XCTAssertTrue(groceries.items.value.isEmpty)
        XCTAssertEqual(pantry.items.value[0].inStock, true)
    }

    func testTheSheetStartsWithWhatThePantryHasUnticked() async {
        let pantry = FakePantryRepository([item(1, "flour"), item(2, "salt", inStock: false, alwaysHave: true), item(3, "milk", inStock: false)])
        let vm = AddToGroceriesViewModel(repository: groceries, preferences: FakeAppPreferences(), pantry: pantry)
        vm.setRecipe(7, title: "Pancakes", language: "en", rendered: ["2 cups flour", "1 tsp salt", "1 cup milk", "2 eggs"])
        await vm.loading?.value

        XCTAssertEqual(vm.uiState.unticked, [SourceLine(source: "recipe-7", index: 0), SourceLine(source: "recipe-7", index: 1)])
        vm.onAdd()
        await settleMain()
        XCTAssertEqual(groceries.items.value.map(\.text), ["1 cup milk", "2 eggs"])
    }

    // MARK: What I need

    private func planWeek() {
        groceries.planned = [
            PlannedIngredients(entryId: 1, day: 100, servings: 8, recipeId: 7, title: "Pancakes",
                               ingredients: ["2 cups flour", "2 eggs", "1 tsp salt"], yield: "Serves 4", language: "en"),
            PlannedIngredients(entryId: 2, day: 103, servings: nil, recipeId: 9, title: "Bread",
                               ingredients: ["500 g flour"], yield: nil, language: "en"),
            PlannedIngredients(entryId: 3, day: 107, servings: nil, recipeId: 5, title: "Next week",
                               ingredients: ["1 onion"], yield: nil, language: "en"),
        ]
    }

    private func needVM(_ pantry: FakePantryRepository) async -> WhatINeedViewModel {
        planWeek()
        let vm = WhatINeedViewModel(weekStart: 100, groceries: groceries, pantry: pantry, preferences: FakeAppPreferences())
        await vm.loading?.value
        await settleMain()
        return vm
    }

    func testTheWeeksLinesAtPlannedServingsMarkedHaveOrBuy() async {
        let vm = await needVM(FakePantryRepository([item(1, "flour"), item(2, "salt", inStock: false, alwaysHave: true)]))
        let needs = vm.uiState.needs!
        XCTAssertEqual(needs.buy.map(\.name), ["eggs"])
        XCTAssertEqual(needs.buy[0].lines.map(\.text), ["4 eggs"])
        XCTAssertEqual(needs.have.map(\.name), ["flour", "salt"])
        XCTAssertEqual(needs.have[0].lines.map(\.text), ["4 cups flour", "500 g flour"])
        XCTAssertEqual(needs.have[1].status, .staple)
    }

    func testThePantryChangingMovesARowAcross() async {
        let pantry = FakePantryRepository([item(1, "eggs", inStock: false)])
        let vm = await needVM(pantry)
        XCTAssertTrue(vm.uiState.needs!.buy.contains { $0.name == "eggs" })
        await pantry.setInStock([1], inStock: true)
        await settleMain()
        XCTAssertTrue(vm.uiState.needs!.have.contains { $0.name == "eggs" })
    }

    func testAddingBuyPutsOnlyTheBuyLinesOnGroceriesOnce() async {
        let vm = await needVM(FakePantryRepository([item(1, "flour")]))
        vm.onAddBuyToGroceries()
        vm.onAddBuyToGroceries()
        await settleMain()
        XCTAssertEqual(groceries.items.value.map(\.text), ["4 eggs", "2 tsp salt"])
        XCTAssertEqual(groceries.items.value.map(\.recipeId), [7, 7])
        XCTAssertEqual(groceries.items.value.map(\.plannedDay), [100, 100])
        XCTAssertTrue(vm.uiState.added)
    }
}
