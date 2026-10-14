import XCTest
@testable import RecipeClipper

/// Android's PantrySelectionTest (#258): entering, toggling, dragging a range, and the bulk
/// actions with one undo.
@MainActor
final class PantrySelectionTests: XCTestCase {
    private let groceries = FakeGroceryRepository()
    private let calendar = FakePlanCalendar()

    private func item(_ id: Int64, _ name: String, inStock: Bool = true, aisle: Aisle = .other) -> PantryItem {
        PantryItem(
            id: id, name: name, quantity: "1 bag", language: "en", aisle: aisle, inStock: inStock,
            alwaysHave: id == 2, purchasedDay: 20_000, expiresDay: 20_100 + id
        )
    }

    private func viewModel(_ pantry: FakePantryRepository) async -> PantryViewModel {
        let vm = PantryViewModel(pantry: pantry, groceries: groceries, calendar: calendar, phoneLanguage: { "en" })
        await settleMain()
        return vm
    }

    private func eight() -> FakePantryRepository { FakePantryRepository((1...8).map { item(Int64($0), "item\($0)") }) }

    func testATouchAndHoldStartsSelectingWithThatRowCheckedAndTheMenuWithNone() async {
        let vm = await viewModel(eight())
        XCTAssertNil(vm.uiState.selection)
        vm.onLongPress(3)
        vm.onDragEnd()
        XCTAssertEqual(vm.uiState.selection?.ids, [3])

        vm.onExitSelection()
        XCTAssertNil(vm.uiState.selection)
        vm.onStartSelecting()
        XCTAssertEqual(vm.uiState.selection?.ids, [])
    }

    func testATapTogglesARow() async {
        let vm = await viewModel(eight())
        vm.onStartSelecting()
        vm.onToggle(2)
        vm.onToggle(5)
        XCTAssertEqual(vm.uiState.selection?.ids, [2, 5])
        vm.onToggle(2)
        XCTAssertEqual(vm.uiState.selection?.ids, [5])
    }

    func testADragChecksTheRangeFromItsAnchorAndDraggingBackGivesRowsTheirStateBack() async {
        let vm = await viewModel(eight())
        let order = vm.uiState.order
        vm.onStartSelecting()
        vm.onToggle(order[0])

        vm.onDragStart(order[2])
        vm.onDragTo(order[6])
        XCTAssertEqual(vm.uiState.selection?.ids, Set([order[0]] + order[2...6]))
        // Back past the anchor: what lay beyond it is unchecked again, and the rows above it join.
        vm.onDragTo(order[1])
        XCTAssertEqual(vm.uiState.selection?.ids, Set(order[0...2]))
        vm.onDragTo(order[4])
        vm.onDragEnd()
        XCTAssertEqual(vm.uiState.selection?.ids, Set([order[0]] + order[2...4]))
        XCTAssertNil(vm.uiState.selection?.anchor)

        // Starting on a checked row unchecks the range instead.
        vm.onDragStart(order[3])
        vm.onDragTo(order[0])
        vm.onDragEnd()
        XCTAssertEqual(vm.uiState.selection?.ids, [order[4]])
    }

    func testATouchAndHoldThenADragChecksEveryRowItPasses() async {
        let vm = await viewModel(eight())
        let order = vm.uiState.order
        vm.onLongPress(order[5])
        vm.onDragTo(order[7])
        vm.onDragTo(order[3])
        vm.onDragEnd()
        XCTAssertEqual(vm.uiState.selection?.ids, Set(order[3...5]))
    }

    func testSelectAllChecksEveryRowShownAndASectionsOnlyItsOwn() async {
        let pantry = FakePantryRepository([item(1, "milk", aisle: .dairy), item(2, "rice", aisle: .grains), item(3, "oats", aisle: .grains)])
        let vm = await viewModel(pantry)
        vm.onStartSelecting()
        let grains = vm.uiState.sections!.first { $0.aisle == .grains }!
        vm.onSelectSection(grains)
        XCTAssertEqual(vm.uiState.selection?.ids, [2, 3])
        XCTAssertFalse(vm.uiState.allSelected)
        vm.onSelectAll()
        XCTAssertEqual(vm.uiState.selection?.ids, [1, 2, 3])
        XCTAssertTrue(vm.uiState.allSelected)
        // A search hides some: they leave the selection.
        vm.onQueryChange("rice")
        XCTAssertEqual(vm.uiState.selection?.ids, [2])
    }

    func testRemoveDeletesEveryCheckedItemAtOnceAndOneUndoPutsThemAllBackWhole() async {
        let pantry = eight()
        let vm = await viewModel(pantry)
        let before = pantry.items.value
        vm.onLongPress(1)
        vm.onDragEnd()
        [2, 4, 7].forEach(vm.onToggle)
        XCTAssertEqual(vm.uiState.selection?.ids, [1, 2, 4, 7])
        vm.onRemoveSelected()
        await settleMain { vm.uiState.message != nil }

        XCTAssertNil(vm.uiState.selection)
        XCTAssertEqual(pantry.items.value, before.filter { ![1, 2, 4, 7].contains($0.id) })
        XCTAssertEqual(vm.uiState.message, .removed(id: 1, count: 4))

        vm.onUndoDelete()
        await settleMain { pantry.items.value.count == 8 }
        XCTAssertEqual(pantry.items.value, before)
        XCTAssertNil(vm.uiState.message)
    }

    func testMarkRunOutRunsEveryCheckedItemOutListsTheirNamesAndOneUndoPutsTheirStockBack() async {
        let pantry = FakePantryRepository([item(1, "milk"), item(2, "rice"), item(3, "oats", inStock: false), item(4, "flour")])
        await groceries.add([NewGroceryLine(text: "rice", language: "en")])
        let vm = await viewModel(pantry)
        let before = pantry.items.value
        vm.onStartSelecting()
        [1, 2, 3].forEach(vm.onToggle)
        vm.onMarkRunOutSelected()
        await settleMain { vm.uiState.message != nil }

        XCTAssertNil(vm.uiState.selection)
        XCTAssertEqual(pantry.items.value.map(\.stock), [.runOut, .runOut, .runOut, .inStock])
        // Oats had run out already, so two changed; rice was on the list already.
        XCTAssertEqual(vm.uiState.message, .markedRunOut(id: 1, count: 2))
        XCTAssertEqual(groceries.items.value.map(\.text), ["rice", "milk"])

        vm.onUndoDelete()
        await settleMain { pantry.items.value.first?.inStock == true }
        XCTAssertEqual(pantry.items.value, before)
    }
}
