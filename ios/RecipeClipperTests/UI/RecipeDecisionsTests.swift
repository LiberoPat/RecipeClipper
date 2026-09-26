import XCTest
@testable import RecipeClipper

/// The reading view's count-bracket decision (#104): off under `aiDecisions` alone (#127), and
/// applied once it lands only with `aiCountBrackets` on too. Android's `RecipeDecisionsTest`.
@MainActor
final class RecipeDecisionsTests: XCTestCase {
    private let apples = "3 large apples, peeled and sliced (about 3 cups)"
    private var question: DecisionQuestion { .countBracket(apples, language: "en") }

    private func open(
        _ decisions: FakeDecisionRepository?, flagsOn: [Flag], ingredients: [String]? = nil
    ) async -> RecipeViewModel {
        let repository = FakeRecipeRepository()
        repository.openResult = Recipe(
            name: "Apple Crumble", image: nil, ingredients: ingredients ?? [apples, "1 cup sugar"], instructions: ["Bake."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "4 servings",
            sourceUrl: "https://example.com/crumble", id: 1, language: "en"
        )
        let flags = FeatureFlags(store: MemoryFeatureFlagStore())
        for flag in Flag.allCases { flags.set(flag, flagsOn.contains(flag)) }
        let vm = RecipeViewModel(
            recipeId: 1, url: nil, repository: repository, preferences: FakeAppPreferences(), clock: TestClock(),
            flags: flags, decisions: decisions
        )
        await settleMain()
        vm.onServingsChange(8)
        await settleMain()
        return vm
    }

    func testATotalScalesTheBracketWithCountBracketsOn() async {
        let decisions = FakeDecisionRepository([question: "total"])
        let vm = await open(decisions, flagsOn: [.aiDecisions, .aiCountBrackets])
        XCTAssertEqual(decisions.asked, [question])
        XCTAssertEqual(vm.uiState.content.success?.ingredients, ["6 large apples, peeled and sliced (about 6 cups)", "2 cup sugar"])
    }

    func testAiDecisionsAloneAsksNothingAndScalesACountBracketAsWithTheFlagOff() async {
        let off = await open(nil, flagsOn: [])
        let decisions = FakeDecisionRepository([question: "each"])
        let vm = await open(decisions, flagsOn: [.aiDecisions])
        XCTAssertTrue(decisions.asked.isEmpty)
        XCTAssertEqual(vm.uiState.content.success?.ingredients, off.uiState.content.success?.ingredients)
        XCTAssertEqual(vm.uiState.content.success?.ingredients, [apples, "2 cup sugar"])
    }

    func testTheReadingViewNeverAsksAboutGroceryTextAndKeepsTheLineAsWritten() async {
        let lines = ["2 eggs (dfsafs -", "2 onions dfsafs"]
        let decisions = FakeDecisionRepository([
            .trailingText("(dfsafs -", language: "en"): "junk",
            .ingredientName("2 onions dfsafs", language: "en"): "onions",
            .trailingText("dfsafs", language: "en"): "junk",
        ])
        let off = await open(nil, flagsOn: [], ingredients: lines)
        let vm = await open(decisions, flagsOn: [.aiDecisions, .aiCountBrackets], ingredients: lines)
        XCTAssertTrue(decisions.asked.isEmpty)
        let shown = vm.uiState.content.success?.ingredients ?? []
        XCTAssertEqual(shown, off.uiState.content.success?.ingredients)
        XCTAssertEqual(shown.count, 2)
        XCTAssertTrue(shown.allSatisfy { $0.contains("dfsafs") })
    }
}
