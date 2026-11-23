import XCTest
@testable import RecipeClipper

/// Ask with a switch (#465, `pantryListSwitch`): the edit sheet's "Add to grocery list", and the
/// shortcuts' Undo. Android's PantryListSwitchTest.
@MainActor
final class PantryListSwitchTests: XCTestCase {
    private let groceries = FakeGroceryRepository()
    private let calendar = FakePlanCalendar()

    private func item(_ id: Int64, _ name: String, inStock: Bool = true, runningLow: Bool = false) -> PantryItem {
        PantryItem(
            id: id, name: name, quantity: nil, language: "en", aisle: .other, inStock: inStock, alwaysHave: false,
            purchasedDay: nil, expiresDay: nil, runningLow: runningLow
        )
    }

    private func viewModel(_ pantry: FakePantryRepository, on: Bool = true) async -> PantryViewModel {
        let vm = PantryViewModel(pantry: pantry, groceries: groceries, calendar: calendar, phoneLanguage: { "en" }, listSwitch: { on })
        await settleMain()
        return vm
    }

    /// Waits for the ViewModel's queued writes, then for its observers to catch up.
    private func settle(_ vm: PantryViewModel) async {
        await vm.settleWrites()
        await settleMain()
    }

    private var texts: [String] { groceries.items.value.map(\.text) }

    func testInStockTheSwitchIsHiddenAndMovingToRunningLowShowsItOnAndAddsTheLine() async {
        let pantry = FakePantryRepository([item(1, "milk")])
        let vm = await viewModel(pantry)
        vm.onEdit(pantry.items.value[0])
        XCTAssertEqual(vm.uiState.editing?.showsListSwitch, false)

        vm.onEditStock(.runningLow)
        await settle(vm)
        XCTAssertEqual(vm.uiState.editing?.showsListSwitch, true)
        XCTAssertEqual(vm.uiState.editing?.addToList, true)
        XCTAssertEqual(texts, ["milk"])
        XCTAssertEqual(pantry.items.value[0].stock, .runningLow)
        XCTAssertNil(vm.uiState.message) // the sheet's choice is shown by the switch, not a snackbar
    }

    func testTurningTheSwitchOffTakesTheLineThisSheetAddedBackOffAndOnPutsItBack() async {
        let pantry = FakePantryRepository([item(1, "milk")])
        let vm = await viewModel(pantry)
        vm.onEdit(pantry.items.value[0])
        vm.onEditStock(.runOut)
        vm.onEditAddToList(false) // straight after: the writes are queued in order
        await settle(vm)
        XCTAssertEqual(vm.uiState.editing?.addToList, false)
        XCTAssertEqual(texts, [])
        XCTAssertEqual(pantry.items.value[0].stock, .runOut) // the stock stays

        vm.onEditAddToList(true)
        await settle(vm)
        XCTAssertEqual(texts, ["milk"])

        vm.onEditAddToList(false)
        vm.onEditDismissed()
        await settle(vm)
        XCTAssertEqual(texts, [])
        XCTAssertNil(vm.uiState.message) // only the sheet's own line went: nothing to offer back
    }

    func testOffThenBetweenRunningLowAndRunOutTheSwitchStaysOffAndNothingIsAdded() async {
        let pantry = FakePantryRepository([item(1, "milk")])
        let vm = await viewModel(pantry)
        vm.onEdit(pantry.items.value[0])
        vm.onEditStock(.runningLow)
        vm.onEditAddToList(false)
        vm.onEditStock(.runOut)
        await settle(vm)
        XCTAssertEqual(vm.uiState.editing?.addToList, false)
        XCTAssertEqual(texts, [])

        // Back to In stock hides it; out of stock again from there turns it on.
        vm.onEditStock(.inStock)
        await settle(vm)
        XCTAssertEqual(vm.uiState.editing?.showsListSwitch, false)
        vm.onEditStock(.runningLow)
        await settle(vm)
        XCTAssertEqual(vm.uiState.editing?.addToList, true)
        XCTAssertEqual(texts, ["milk"])
    }

    func testAnItemAlreadyOnTheListOpensWithTheSwitchOnAndOffOffersThoseLinesBack() async {
        await groceries.add([NewGroceryLine(text: "2 milk", language: "en"), NewGroceryLine(text: "bread", language: "en")])
        let pantry = FakePantryRepository([item(1, "milk", runningLow: true), item(2, "rice", inStock: false)])
        let vm = await viewModel(pantry)
        await settleMain { vm.uiState.onList == [1] }

        vm.onEdit(pantry.items.value[0])
        XCTAssertEqual(vm.uiState.editing?.showsListSwitch, true)
        XCTAssertEqual(vm.uiState.editing?.addToList, true)

        vm.onEditAddToList(false)
        await settle(vm)
        XCTAssertEqual(texts, ["bread"])
        vm.onEditSave()
        await settle(vm)
        // Lines the sheet didn't add went: the basket tag's Undo offers them back.
        guard case .takenOffList(_, 1) = vm.uiState.message else { return XCTFail("\(String(describing: vm.uiState.message))") }
        vm.onUndoDelete()
        await settleMain { self.groceries.items.value.count == 2 }
        XCTAssertEqual(texts, ["2 milk", "bread"])

        // Out but not on the list: the switch opens off.
        vm.onEdit(pantry.items.value[1])
        XCTAssertEqual(vm.uiState.editing?.showsListSwitch, true)
        XCTAssertEqual(vm.uiState.editing?.addToList, false)
    }

    func testAShortcutAddsWithAnUndoThatTakesOnlyThatLineOff() async {
        await groceries.add([NewGroceryLine(text: "bread", language: "en")])
        let pantry = FakePantryRepository([item(1, "milk")])
        let vm = await viewModel(pantry)

        vm.onSetStock(pantry.items.value[0], .runOut)
        await settle(vm)
        XCTAssertEqual(texts, ["bread", "milk"])
        guard case .addedToList(_, "milk") = vm.uiState.message else { return XCTFail("\(String(describing: vm.uiState.message))") }

        vm.onUndoDelete()
        await settleMain { self.groceries.items.value.count == 1 }
        XCTAssertEqual(texts, ["bread"])
        XCTAssertEqual(pantry.items.value[0].stock, .runOut) // the stock stays as chosen
        XCTAssertNil(vm.uiState.message)
    }

    func testAShortcutWhoseLineIsAlreadyThereShowsNoSnackbar() async {
        await groceries.add([NewGroceryLine(text: "milk", language: "en")])
        let pantry = FakePantryRepository([item(1, "milk")])
        let vm = await viewModel(pantry)

        vm.onSetStock(pantry.items.value[0], .runningLow)
        await settle(vm)
        XCTAssertEqual(texts, ["milk"])
        XCTAssertNil(vm.uiState.message)

        vm.onSetStock(pantry.items.value[0], .inStock)
        await settle(vm)
        XCTAssertNil(vm.uiState.message)
    }

    func testFlagOffTheSheetHasNoSwitchAndEveryWayAddsSilentlyAsBefore() async {
        let pantry = FakePantryRepository([item(1, "milk"), item(2, "rice")])
        let vm = await viewModel(pantry, on: false)
        vm.onEdit(pantry.items.value[0])
        vm.onEditStock(.runningLow)
        await settle(vm)
        XCTAssertEqual(vm.uiState.editing?.showsListSwitch, false)
        vm.onEditAddToList(false) // no switch: nothing happens
        await settle(vm)
        XCTAssertEqual(texts, ["milk"])
        vm.onEditDismissed()

        vm.onSetStock(pantry.items.value[1], .runOut)
        await settle(vm)
        XCTAssertEqual(texts, ["milk", "rice"])
        XCTAssertNil(vm.uiState.message)
    }
}
