import XCTest
@testable import RecipeClipper

/// A week of recipes shared in (#352): the share extension leaves the text for the app's
/// meal-plan review (`PendingPlan`), behind the shareText and mealPlanImport flags.
@MainActor
final class PlanHandOffTests: XCTestCase {

    private func meal(_ name: String, _ day: String) -> String {
        "{\"@type\": \"Recipe\", \"name\": \"\(name)\", \"day\": \"\(day)\", "
            + "\"recipeIngredient\": [\"1 egg\"], \"recipeInstructions\": [\"Cook.\"]}"
    }

    private var week: String { "```json\n[" + meal("Omelette", "2026-11-23") + ", " + meal("Soup", "2026-11-24") + "]\n```" }

    private func defaults() -> UserDefaults { UserDefaults(suiteName: "PlanHandOff-\(UUID().uuidString)")! }

    private func recipe() -> Recipe {
        Recipe(
            name: "Scones", image: nil, ingredients: ["2 cups flour"], instructions: ["Mix."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "https://example.com/scones", id: 7
        )
    }

    private func viewModel(
        pendingText: PendingText, pendingPlan: PendingPlan, planOn: Bool = true, shareOn: Bool = true
    ) -> ShareImportViewModel {
        ShareImportViewModel(
            repository: ReconnectCountingRepository(.success(recipe())),
            shareTextOn: { shareOn }, pendingText: pendingText, clock: DataTestClock(5_000),
            mealPlanOn: { planOn }, pendingPlan: pendingPlan
        )
    }

    func testAWeekOfRecipesIsLeftForTheReviewAsItsText() async {
        let suite = defaults()
        let pendingText = PendingText(defaults: suite)
        let pendingPlan = PendingPlan(defaults: suite)
        let vm = viewModel(pendingText: pendingText, pendingPlan: pendingPlan)

        vm.start(with: nil, text: week)
        await settleMain()

        XCTAssertEqual(vm.uiState, .planInApp)
        XCTAssertNil(pendingText.take(now: 5_000), "not the single-recipe editor's")
        let left = pendingPlan.take(now: 5_000 + PendingPlan.window)
        XCTAssertEqual(left, week)
        XCTAssertEqual(MealPlanImport.read(left ?? "")?.items.count, 2)
    }

    func testAnAnswerCutOffBeforeItsFirstRecipeEndedIsACauseAndLeavesNothing() async {
        let suite = defaults()
        let pendingText = PendingText(defaults: suite)
        let pendingPlan = PendingPlan(defaults: suite)
        let vm = viewModel(pendingText: pendingText, pendingPlan: pendingPlan)
        let cut = "```json\n[\n  {\n    \"@type\": \"Recipe\",\n    \"name\": \"Mon\",\n    \"day\": \"2026-11-19\",\n"
            + "    \"recipeInstructions\": [\n      { \"@type\": \"HowToStep\","

        vm.start(with: nil, text: cut)
        await settleMain()

        XCTAssertEqual(vm.uiState, .failed(.cutOff))
        XCTAssertNil(pendingText.take(now: 5_000))
        XCTAssertNil(pendingPlan.take(now: 5_000))
        // A cut-off week with whole recipes is still left for the review, which flags it.
        let meal = "{\"@type\": \"Recipe\", \"name\": \"Omelette\", \"day\": \"2026-11-23\", \"recipeIngredient\": [\"1 egg\"], \"recipeInstructions\": [\"Cook.\"]}"
        vm.start(with: nil, text: "[\(meal), \(meal.replacingOccurrences(of: "Omelette", with: "Soup")), {\"@type\": \"Recipe\", \"name\": \"X\", \"re")
        await settleMain()
        XCTAssertEqual(vm.uiState, .planInApp)
    }

    func testWithTheFlagOffTheFirstRecipeIsLeftForTheEditorAsBefore() async {
        let suite = defaults()
        let pendingText = PendingText(defaults: suite)
        let pendingPlan = PendingPlan(defaults: suite)
        let vm = viewModel(pendingText: pendingText, pendingPlan: pendingPlan, planOn: false)

        vm.start(with: nil, text: week)
        await settleMain()

        XCTAssertEqual(vm.uiState, .textInApp)
        XCTAssertNil(pendingPlan.take(now: 5_000))
        XCTAssertEqual(pendingText.take(now: 5_000)?.name, "Omelette")
    }

    func testASingleRecipeStaysWithTheEditorAndANewShareClearsAnEarlierPlan() async {
        let suite = defaults()
        let pendingText = PendingText(defaults: suite)
        let pendingPlan = PendingPlan(defaults: suite)
        viewModel(pendingText: pendingText, pendingPlan: pendingPlan).start(with: nil, text: week)
        await settleMain()

        let single = viewModel(pendingText: pendingText, pendingPlan: pendingPlan)
        single.start(with: nil, text: meal("Omelette", "2026-11-23"))
        await settleMain()

        XCTAssertEqual(single.uiState, .textInApp)
        XCTAssertNil(pendingPlan.take(now: 5_000), "the plan left before is gone")
        XCTAssertNotNil(pendingText.take(now: 5_000))
    }
}
