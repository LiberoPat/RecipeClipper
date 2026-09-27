import XCTest
@testable import RecipeClipper

/// Android's PantryUseUpViewModelTest and RecipeCookFinishedTest (#147): what the end-of-cooking
/// sheet lists and preselects, what one confirm writes to the pantry and the grocery list, the
/// one Undo, and the recipe screen handing over the ticked lines once cook mode is finished.
@MainActor
final class PantryUseUpViewModelTests: XCTestCase {
    private let groceries = FakeGroceryRepository()

    private func item(_ id: Int64, _ name: String, _ quantity: String?) -> PantryItem {
        PantryItem(id: id, name: name, quantity: quantity, language: "en", aisle: .other, inStock: true, alwaysHave: false, purchasedDay: 1, expiresDay: 30)
    }

    private lazy var chicken = item(1, "chicken", "2 lb")
    private lazy var eggs = item(2, "eggs", "6")
    private lazy var flour = item(3, "flour", "half a bag")
    private lazy var milk = item(4, "milk", "1 cup")

    private func viewModel(_ items: PantryItem...) -> (FakePantryRepository, PantryUseUpViewModel) {
        let pantry = FakePantryRepository(items)
        return (pantry, PantryUseUpViewModel(pantry: pantry, groceries: groceries))
    }

    func testFinishingOpensTheSheetWorkedOutRowsTickedTheRestKeep() async {
        let (_, vm) = viewModel(chicken, eggs, flour)
        vm.onCookFinished(language: "en", lines: ["1 lb chicken", "2 large eggs", "2 cups flour", "1 tsp salt"])
        await settleMain()

        let sheet = vm.uiState.sheet
        XCTAssertEqual(sheet?.rows.map(\.item.name), ["chicken", "eggs", "flour"])
        XCTAssertEqual(sheet?.rows.map(\.change), [.subtract(before: "2 lb", after: "1 lb"), .subtract(before: "6", after: "4"), .ask])
        XCTAssertEqual(sheet?.ticked, [1, 2])
        XCTAssertEqual(sheet?.choice(3), .keep)
    }

    func testNothingInThePantryUsedNoSheet() async {
        let (_, vm) = viewModel(chicken)
        vm.onCookFinished(language: "en", lines: ["2 cups rice"])
        await settleMain()
        XCTAssertNil(vm.uiState.sheet)
    }

    func testConfirmWritesTheNewQuantitiesAndOnlyTheTickedOnes() async {
        let (pantry, vm) = viewModel(chicken, eggs)
        vm.onCookFinished(language: "en", lines: ["1 lb chicken", "2 eggs"])
        await settleMain()
        vm.onToggle(2)
        vm.onConfirm()
        await settleMain()

        XCTAssertNil(vm.uiState.sheet)
        XCTAssertEqual(pantry.items.value.map(\.quantity), ["1 lb", "6"])
        XCTAssertEqual(pantry.items.value.map(\.inStock), [true, true])
        XCTAssertNotNil(vm.uiState.updated)
        XCTAssertTrue(groceries.items.value.isEmpty)
    }

    func testUsedUpGoesOutOfStockWithNoQuantityOntoTheGroceryList() async {
        let (pantry, vm) = viewModel(milk)
        vm.onCookFinished(language: "en", lines: ["1 cup milk"])
        await settleMain()
        XCTAssertEqual(vm.uiState.sheet?.rows.first?.change, .subtract(before: "1 cup", after: nil))
        vm.onConfirm()
        await settleMain()

        XCTAssertEqual(pantry.items.value.first?.inStock, false)
        XCTAssertNil(pantry.items.value.first?.quantity)
        XCTAssertEqual(groceries.items.value.map(\.text), ["milk"])
    }

    func testAnAskedRowRunningLowGoesOnTheListOutGoesOutToo() async {
        let sugar = item(5, "sugar", nil)
        let (pantry, vm) = viewModel(flour, sugar)
        vm.onCookFinished(language: "en", lines: ["2 cups flour", "1 cup sugar"])
        await settleMain()
        vm.onChoice(3, .low)
        vm.onChoice(5, .out)
        vm.onConfirm()
        await settleMain()

        XCTAssertEqual(pantry.items.value.map(\.inStock), [true, false])
        XCTAssertEqual(pantry.items.value.map(\.quantity), ["half a bag", nil])
        XCTAssertEqual(groceries.items.value.map(\.text), ["flour", "sugar"])
    }

    func testKeepChangesNothingAndRaisesNoSnackbar() async {
        let (pantry, vm) = viewModel(flour)
        vm.onCookFinished(language: "en", lines: ["2 cups flour"])
        await settleMain()
        vm.onConfirm()
        await settleMain()
        XCTAssertEqual(pantry.items.value, [flour])
        XCTAssertNil(vm.uiState.updated)
    }

    func testAnItemAlreadyOnTheListIsntAddedTwice() async {
        await groceries.add([NewGroceryLine(text: "Milk", language: "en")])
        let (_, vm) = viewModel(milk)
        vm.onCookFinished(language: "en", lines: ["1 cup milk"])
        await settleMain()
        vm.onConfirm()
        await settleMain()
        XCTAssertEqual(groceries.items.value.map(\.text), ["Milk"])
    }

    func testOneUndoPutsThePantryBackAndTakesTheAddedLinesOff() async {
        await groceries.add([NewGroceryLine(text: "2 lemons", language: "en")])
        let (pantry, vm) = viewModel(chicken, milk)
        vm.onCookFinished(language: "en", lines: ["1 lb chicken", "1 cup milk"])
        await settleMain()
        vm.onConfirm()
        await settleMain()
        XCTAssertEqual(groceries.items.value.map(\.text), ["2 lemons", "milk"])

        vm.onUndo()
        await settleMain()
        XCTAssertEqual(pantry.items.value, [chicken, milk])
        XCTAssertEqual(groceries.items.value.map(\.text), ["2 lemons"])
        XCTAssertNil(vm.uiState.updated)
    }

    func testDismissingChangesNothing() async {
        let (pantry, vm) = viewModel(chicken)
        vm.onCookFinished(language: "en", lines: ["1 lb chicken"])
        await settleMain()
        vm.onDismissed()
        await settleMain()
        XCTAssertNil(vm.uiState.sheet)
        XCTAssertEqual(pantry.items.value, [chicken])
    }

    // MARK: The recipe screen hands the ticked lines over at the end of cooking

    private func recipeViewModel(checked: Set<Int>, servings: Int? = nil) async -> RecipeViewModel {
        let repository = FakeRecipeRepository()
        repository.openResult = Recipe(
            name: "Omelette", image: nil, ingredients: ["For the eggs:", "3 eggs", "1 cup milk", "salt"],
            instructions: ["Whisk.", "Cook."], prepTime: nil, cookTime: nil, totalTime: nil, yield: "2 servings",
            sourceUrl: "https://example.com/omelette", id: 1, checkedIngredients: checked, servingsTarget: servings
        )
        let clock = TestClock(now: 0)
        let vm = RecipeViewModel(
            recipeId: 1, url: nil, repository: repository, preferences: FakeAppPreferences(),
            clock: clock, sleep: clock.sleep, alarms: FakeTimerAlarmScheduler(), openInCookMode: false
        )
        await settleMain()
        return vm
    }

    func testFinishingHandsOverTheTickedLinesAsShownScaledInOrder() async {
        let vm = await recipeViewModel(checked: [2, 1], servings: 4)
        vm.onCookStart()
        vm.onStepDone()
        vm.onStepDone()
        XCTAssertEqual(vm.uiState.cookFinished, FinishedCook(language: "en", lines: ["6 eggs", "2 cup milk"]))
        vm.onCookFinishedHandled()
        XCTAssertNil(vm.uiState.cookFinished)
    }

    func testNothingTickedOrLeavingBeforeTheEndHandsNothingOver() async {
        let none = await recipeViewModel(checked: [])
        none.onCookStart()
        none.onStepDone()
        none.onStepDone()
        XCTAssertNil(none.uiState.cookFinished)

        let left = await recipeViewModel(checked: [1])
        left.onCookStart()
        left.onStepDone()
        left.onCookExit()
        XCTAssertNil(left.uiState.cookFinished)
    }
}
