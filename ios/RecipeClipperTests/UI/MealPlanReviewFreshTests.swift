import XCTest
@testable import RecipeClipper

/// Fresh food's note in the meal-plan review (#396): a perishable recipe planned late, one swap,
/// never by itself. Android's `MealPlanReviewFreshTest`.
@MainActor
final class MealPlanReviewFreshTests: XCTestCase {
    private let today = FakePlanCalendar.wednesday
    private let recipes = FakeRecipeRepository()
    private let week = FakeMealPlanRepository()

    private func date(_ offset: Int64) -> String {
        let c = PlanDays.civil(today + offset)
        return String(format: "%04d-%02d-%02d", c.year, c.month, c.day)
    }

    /// A shared text: each recipe named, with its ingredient lines and a day (today plus an offset), in order.
    private func text(_ plan: (name: String, lines: [String], day: Int64, language: String?)...) -> String {
        "[" + plan.map { p in
            let lang = p.language.map { ", \"inLanguage\": \"\($0)\"" } ?? ""
            return "{\"@type\": \"Recipe\", \"name\": \"\(p.name)\", \"day\": \"\(date(p.day))\"\(lang), "
                + "\"recipeIngredient\": [\(p.lines.map { "\"\($0)\"" }.joined(separator: ", "))], \"recipeInstructions\": [\"Cook.\"]}"
        }.joined(separator: ",") + "]"
    }

    private func viewModel(_ text: String, freshFood: Bool = true) -> MealPlanReviewViewModel {
        MealPlanReviewViewModel(text: text, recipes: recipes, plan: week, calendar: FakePlanCalendar(), freshFood: freshFood)
    }

    private let omelette = (name: "Omelette", lines: ["4 eggs", "1 tbsp butter"])
    private let salmon = (name: "Salmon bowls", lines: ["2 salmon fillets", "1 cup rice"])

    private func saved(_ id: Int64) -> Recipe {
        Recipe(
            name: "saved", image: nil, ingredients: ["1 egg"], instructions: ["Cook."], prepTime: nil, cookTime: nil,
            totalTime: nil, yield: nil, sourceUrl: "manual:x", id: id
        )
    }

    func testAPerishableRecipePlannedLaterThanItsGroupKeepsFromThePlansFirstDayGetsOneSwap() async {
        let vm = viewModel(text((omelette.name, omelette.lines, 0, nil), (salmon.name, salmon.lines, 5, nil)))
        await settleMain()

        let nudge = vm.uiState.nudges[1]!
        XCTAssertEqual(nudge.name, "Salmon fillets")
        XCTAssertEqual(nudge.keeps, "1-2")
        XCTAssertEqual(nudge.swapKey, 0)
        XCTAssertEqual(nudge.swapDay, today)
        XCTAssertEqual(Set(vm.uiState.nudges.keys), [1])
        // Shown, not done.
        XCTAssertEqual(vm.uiState.items.map(\.day), [today, today + 5])
    }

    func testTheSwapTradesTheTwoDaysAndTheNoteGoes() async {
        let vm = viewModel(text((omelette.name, omelette.lines, 0, nil), (salmon.name, salmon.lines, 5, nil)))
        await settleMain()
        vm.onSwap(1)
        XCTAssertEqual(vm.uiState.items.map(\.day), [today + 5, today])
        XCTAssertTrue(vm.uiState.nudges.isEmpty)
        XCTAssertTrue(week.meals.value.isEmpty) // nothing is saved before Save
    }

    func testSwappingWhereThereIsNoNoteDoesNothing() async {
        let vm = viewModel(text((omelette.name, omelette.lines, 0, nil), (salmon.name, salmon.lines, 1, nil)))
        await settleMain()
        XCTAssertTrue(vm.uiState.nudges.isEmpty)
        vm.onSwap(1)
        XCTAssertEqual(vm.uiState.items.map(\.day), [today, today + 1])
    }

    func testTheNoteFollowsTheCooksChangesADayPickedOrARecipeUnticked() async {
        let vm = viewModel(text((omelette.name, omelette.lines, 0, nil), (salmon.name, salmon.lines, 1, nil)))
        await settleMain()
        XCTAssertTrue(vm.uiState.nudges.isEmpty)

        vm.onDay(1, today + 4)
        XCTAssertNotNil(vm.uiState.nudges[1])
        vm.onDay(1, nil) // not planned: nothing to be late
        XCTAssertTrue(vm.uiState.nudges.isEmpty)
        vm.onDay(1, today + 4)
        vm.onTick(0) // the first meal is out, so the plan starts on the salmon's day
        XCTAssertTrue(vm.uiState.nudges.isEmpty)
    }

    func testNothingPerishableOrAPassedDayOrTheFlagOffHasNoNote() async {
        let vm = viewModel(text((omelette.name, omelette.lines, 0, nil), ("Rice", ["2 cups rice"], 9, nil), (salmon.name, salmon.lines, -3, nil)))
        await settleMain()
        XCTAssertTrue(vm.uiState.nudges.isEmpty)

        let off = viewModel(text((omelette.name, omelette.lines, 0, nil), (salmon.name, salmon.lines, 5, nil)), freshFood: false)
        await settleMain()
        XCTAssertTrue(off.uiState.nudges.isEmpty)
        off.onSwap(1)
        XCTAssertEqual(off.uiState.items.map(\.day), [today, today + 5])
    }

    func testTheRecipesOwnLanguageReadsItsIngredients() async {
        let vm = viewModel(text((omelette.name, omelette.lines, 0, nil), ("Lachs mit Reis", ["2 Lachsfilets", "200 g Reis"], 5, "de")))
        await settleMain()
        XCTAssertEqual(vm.uiState.nudges[1]?.name, "Lachsfilets")
    }

    func testSavePlansTheDaysAsSwapped() async {
        let vm = viewModel(text((omelette.name, omelette.lines, 0, nil), (salmon.name, salmon.lines, 5, nil)))
        await settleMain()
        recipes.addManualQueue = [saved(1), saved(2)]
        vm.onSwap(1)
        vm.onSave()
        await settleMain()
        XCTAssertEqual(week.meals.value.map { [$0.recipeId!, $0.day] }, [[1, today + 5], [2, today]])
    }
}
