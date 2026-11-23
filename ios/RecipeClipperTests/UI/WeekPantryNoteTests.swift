import XCTest
@testable import RecipeClipper

/// The Plan's quiet pantry note (#456, `planPantryNote`) through `WeekViewModel`: which meals, the
/// pantry and the grocery list followed live, "Add to groceries" and its undo. Android's
/// `WeekPantryNoteTest`.
@MainActor
final class WeekPantryNoteTests: XCTestCase {
    private let today = FakePlanCalendar.wednesday
    private let plan = FakeMealPlanRepository()
    private let pantry = FakePantryRepository()
    private let groceries = FakeGroceryRepository()
    private let lunch = FakeMealPlanRepository.lunch
    private let dinner = FakeMealPlanRepository.dinner

    private func item(_ id: Int64, _ name: String, inStock: Bool = true) -> PantryItem {
        PantryItem(
            id: id, name: name, quantity: nil, language: "en", aisle: .other, inStock: inStock,
            alwaysHave: false, purchasedDay: nil, expiresDay: nil
        )
    }

    private func viewModel(on: Bool = true, decisions: FakeDecisionRepository? = nil) -> WeekViewModel {
        WeekViewModel(
            plan: plan, recipes: FakeRecipeRepository(), calendar: FakePlanCalendar(), sleep: immediateSleep,
            groceries: groceries, pantry: pantry, decisions: decisions, preferences: FakeAppPreferences(), pantryNote: on
        )
    }

    @discardableResult
    private func planned(
        _ recipeId: Int64, _ day: Int64, _ type: Int64, _ lines: [String], servings: Int? = nil, yield: String? = nil
    ) async -> Int64 {
        plan.lines[recipeId] = lines
        await plan.addRecipe(recipeId: recipeId, day: day, mealTypeId: type, servings: servings)
        let id = plan.meals.value.last!.id
        groceries.planned.append(PlannedIngredients(
            entryId: id, day: day, servings: servings, recipeId: recipeId, title: "Recipe \(recipeId)",
            ingredients: lines, yield: yield, language: "en"
        ))
        return id
    }

    private func texts() -> [String] { groceries.items.value.map(\.text) }

    func testAPlannedRecipeMissingIngredientsGetsTheNoteOneWithEverythingNone() async {
        pantry.items.send([item(1, "flour"), item(2, "eggs")])
        let falafel = await planned(7, today, lunch, ["2 cups flour", "2 tbsp tahini", "1 tsp sumac"])
        let pancakes = await planned(8, today, dinner, ["2 cups flour", "2 eggs"])
        await plan.addNote("Eat out", day: today + 1, mealTypeId: dinner)
        let vm = viewModel()
        await settleMain()
        XCTAssertEqual(vm.uiState.pantryGaps, [falafel: PantryGap(missing: ["tahini", "sumac"])])
        XCTAssertNil(vm.uiState.pantryGaps[pancakes])
    }

    func testTheNoteFollowsThePantryAndThePlan() async {
        pantry.items.send([item(1, "flour")])
        let falafel = await planned(7, today, lunch, ["2 tbsp tahini"])
        let vm = viewModel()
        await settleMain()
        XCTAssertEqual(Set(vm.uiState.pantryGaps.keys), [falafel])

        pantry.items.send([item(1, "flour"), item(2, "tahini")])
        await settleMain()
        XCTAssertTrue(vm.uiState.pantryGaps.isEmpty)

        pantry.items.send([item(1, "flour"), item(2, "tahini", inStock: false)])
        let curry = await planned(9, today + 2, dinner, ["1 can coconut milk"])
        await settleMain()
        XCTAssertEqual(Set(vm.uiState.pantryGaps.keys), [falafel, curry])
    }

    func testAnEmptyPantryAMealInThePastAndTheFlagOffSayNothing() async {
        await planned(7, today, lunch, ["2 tbsp tahini"])
        let vm = viewModel()
        await settleMain()
        XCTAssertTrue(vm.uiState.pantryGaps.isEmpty)

        pantry.items.send([item(1, "flour")])
        let yesterday = await planned(8, today - 1, lunch, ["2 tbsp tahini"])
        await settleMain()
        XCTAssertNil(vm.uiState.pantryGaps[yesterday])
        XCTAssertEqual(vm.uiState.pantryGaps.count, 1)

        let off = viewModel(on: false)
        await settleMain()
        XCTAssertTrue(off.uiState.pantryGaps.isEmpty)
    }

    func testTheModelsSameAnswerCountsAsHad() async {
        pantry.items.send([item(1, "green onion")])
        let meal = await planned(7, today, lunch, ["2 scallions"])
        let decisions = FakeDecisionRepository()
        let vm = viewModel(decisions: decisions)
        await settleMain()
        XCTAssertEqual(Set(vm.uiState.pantryGaps.keys), [meal])
        decisions.answer([DecisionQuestion.sameIngredient("scallions", "green onion", language: "en"): "same"])
        await settleMain()
        XCTAssertTrue(vm.uiState.pantryGaps.isEmpty)
    }

    func testAddToGroceriesAddsExactlyTheMissingLinesAtThePlannedServingsAndUndoTakesThemOff() async {
        pantry.items.send([item(1, "flour")])
        await groceries.add([NewGroceryLine(text: "milk", language: "en")])
        let falafel = await planned(
            7, today, lunch, ["For the sauce:", "2 tbsp tahini", "1 tsp sumac", "1 cup flour", "salt and pepper to taste"],
            servings: 4, yield: "2 servings"
        )
        let vm = viewModel()
        await settleMain()
        XCTAssertEqual(vm.uiState.pantryGaps[falafel], PantryGap(missing: ["tahini", "sumac"]))

        vm.onAddMissingToGroceries(falafel)
        await settleMain()
        XCTAssertEqual(texts(), ["milk", "4 tbsp tahini", "2 tsp sumac"])
        XCTAssertEqual(groceries.items.value.map(\.recipeId), [nil, 7, 7])
        XCTAssertEqual(vm.uiState.addedToGroceries?.count, 2)
        XCTAssertEqual(vm.uiState.pantryGaps[falafel], PantryGap(missing: [], onList: 2))

        vm.onUndoAddToGroceries()
        await settleMain()
        XCTAssertEqual(texts(), ["milk"])
        XCTAssertNil(vm.uiState.addedToGroceries)
        XCTAssertEqual(vm.uiState.pantryGaps[falafel], PantryGap(missing: ["tahini", "sumac"]))
    }

    func testTheNoteFollowsTheGroceryListAndTheRestockEndsIt() async {
        pantry.items.send([item(1, "flour")])
        let falafel = await planned(7, today, lunch, ["2 tbsp tahini", "1 tsp sumac"])
        let vm = viewModel()
        await settleMain()

        await groceries.add([NewGroceryLine(text: "tahini", language: "en")])
        await settleMain()
        XCTAssertEqual(vm.uiState.pantryGaps[falafel], PantryGap(missing: ["sumac"], onList: 1))

        vm.onAddMissingToGroceries(falafel)
        await settleMain()
        XCTAssertEqual(texts(), ["tahini", "1 tsp sumac"])
        XCTAssertEqual(vm.uiState.addedToGroceries?.count, 1)
        vm.onAddedSnackbarDismissed()

        await groceries.setChecked(groceries.items.value.map(\.id), checked: true)
        await settleMain()
        XCTAssertEqual(vm.uiState.pantryGaps[falafel], PantryGap(missing: ["tahini", "sumac"]))
        pantry.items.send([item(1, "flour"), item(2, "tahini"), item(3, "sumac")])
        await settleMain()
        XCTAssertNil(vm.uiState.pantryGaps[falafel])
    }

    func testARemovalSettlesTheAddsUndo() async {
        pantry.items.send([item(1, "flour")])
        let falafel = await planned(7, today, lunch, ["2 tbsp tahini"])
        let other = await planned(8, today, dinner, ["2 cups flour"])
        let vm = viewModel()
        await settleMain()
        vm.onAddMissingToGroceries(falafel)
        await settleMain()
        vm.onRemove(plan.meals.value.first { $0.id == other }!)
        await settleMain()
        XCTAssertNil(vm.uiState.addedToGroceries)
        vm.onUndoAddToGroceries()
        await settleMain()
        XCTAssertEqual(texts(), ["2 tbsp tahini"])
    }
}
