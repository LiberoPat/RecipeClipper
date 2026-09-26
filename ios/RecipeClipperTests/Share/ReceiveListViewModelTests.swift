import UniformTypeIdentifiers
import XCTest
@testable import RecipeClipper

/// "Add this list" (#149; Android's ReceiveListViewModelTest): a list shared in or pasted, added
/// to Groceries or the Pantry, and the share card that offers it.
@MainActor
final class ReceiveListViewModelTests: XCTestCase {

    private let groceries = FakeGroceryRepository()
    private let sent = "Groceries\n\nMeat\n- 2 lb chicken thighs (Sheet-pan chicken)\n\nProduce\n- 2 onions\n- 1 lime"

    private func viewModel(pantry: FakePantryRepository = FakePantryRepository()) -> ReceiveListViewModel {
        ReceiveListViewModel(groceries: groceries, pantry: pantry, calendar: FakePlanCalendar(), phoneLanguage: { "en" })
    }

    private func pantryItem(_ id: Int64, _ name: String, inStock: Bool) -> PantryItem {
        PantryItem(
            id: id, name: name, quantity: nil, language: "en", aisle: .produce, inStock: inStock,
            alwaysHave: false, purchasedDay: nil, expiresDay: nil
        )
    }

    func testTheTickedLinesGoOnTheGroceryListAsWritten() async {
        await groceries.add([NewGroceryLine(text: "1 lb chicken thighs", language: "en")])
        let vm = viewModel()
        vm.open(sent)
        XCTAssertEqual(vm.uiState.lines, ["2 lb chicken thighs (Sheet-pan chicken)", "2 onions", "1 lime"])
        XCTAssertEqual(vm.uiState.tickedCount, 3)

        vm.onToggle(2)
        vm.onAddToGroceries()
        await vm.currentWrite?.value

        XCTAssertEqual(
            groceries.items.value.map(\.text),
            ["1 lb chicken thighs", "2 lb chicken thighs (Sheet-pan chicken)", "2 onions"]
        )
        XCTAssertEqual(groceries.items.value.map(\.language), ["en", "en", "en"])
        XCTAssertEqual(vm.uiState.added, .groceries)
        // A second tap adds nothing more.
        vm.onAddToGroceries()
        await vm.currentWrite?.value
        XCTAssertEqual(groceries.items.value.count, 3)
    }

    func testInThePantryEachLineIsItsIngredientAndOneAlreadyTrackedIsRestocked() async {
        let pantry = FakePantryRepository([pantryItem(1, "onions", inStock: false), pantryItem(2, "lime", inStock: true)])
        let vm = viewModel(pantry: pantry)
        vm.open(sent + "\n- 3 onions\n- salt and pepper")
        vm.onAddToPantry()
        await vm.currentWrite?.value

        let items = Dictionary(uniqueKeysWithValues: pantry.items.value.map { ($0.name, $0) })
        XCTAssertEqual(Set(items.keys), ["onions", "lime", "chicken thighs", "salt and pepper"])
        XCTAssertEqual(items["onions"]?.inStock, true)
        XCTAssertEqual(items["chicken thighs"]?.purchasedDay, FakePlanCalendar.wednesday)
        XCTAssertEqual(vm.uiState.added, .pantry)
        XCTAssertTrue(groceries.items.value.isEmpty)
    }

    func testAClipboardWithNoListShowsTheSheetEmptyAndNothingCanBeAdded() async {
        let vm = viewModel()
        vm.open(nil)
        XCTAssertEqual(vm.uiState.lines, [])
        vm.onAddToGroceries()
        XCTAssertNil(vm.uiState.added)

        vm.onDismiss()
        XCTAssertNil(vm.uiState.lines)
    }

    // MARK: - The share card

    func testSharedTextWithALinkIsStillARecipe() async {
        let vm = ShareImportViewModel(repository: nil, makeReceiveList: { self.viewModel() })
        vm.start(with: SharedInput(url: "https://example.com/soup"), text: nil)
        XCTAssertEqual(vm.uiState, .failed(.saveFailed))
        XCTAssertNil(vm.receiveList)
    }

    func testSharedTextWithLinesOffersTheList() async {
        let vm = ShareImportViewModel(repository: nil, makeReceiveList: { self.viewModel() })
        vm.start(with: nil, text: sent)
        XCTAssertEqual(vm.uiState, .list)
        XCTAssertEqual(vm.receiveList?.uiState.lines?.count, 3)
    }

    func testWithTheGroceryListOffOrNothingToAddItIsNoLink() async {
        let off = ShareImportViewModel(repository: nil, makeReceiveList: { nil })
        off.start(with: nil, text: sent)
        XCTAssertEqual(off.uiState, .noLink)

        let empty = ShareImportViewModel(repository: nil, makeReceiveList: { self.viewModel() })
        empty.start(with: nil, text: "\n---\n")
        XCTAssertEqual(empty.uiState, .noLink)
    }

    func testSharedTextIsReadWhenThereIsNoLink() async {
        let provider = NSItemProvider(item: "- milk\n- 2 eggs" as NSString, typeIdentifier: UTType.plainText.identifier)
        let input = await SharedItems.read(from: [provider])
        XCTAssertNil(input)
        let text = await SharedItems.text(from: [provider])
        XCTAssertEqual(text, "- milk\n- 2 eggs")
    }

    func testTheSwitchIsOffUntilTheAppWritesIt() {
        let defaults = UserDefaults(suiteName: "ReceiveListViewModelTests")!
        defaults.removePersistentDomain(forName: "ReceiveListViewModelTests")
        let mirror = DefaultsGroceriesSwitch(defaults: defaults)
        XCTAssertFalse(mirror.isOn)
        mirror.store(true)
        XCTAssertTrue(mirror.isOn)
        mirror.store(false)
        XCTAssertFalse(mirror.isOn)
    }
}
