import XCTest
@testable import RecipeClipper

/// The reading view's count-bracket decision (#104): off under `aiDecisions` alone (#127), and
/// applied once it lands only with `aiCountBrackets` on too. And junk after an ingredient (#174):
/// asked as Groceries asks it, hidden once decided. Android's `RecipeDecisionsTest`.
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

    // Junk after an ingredient (#174): the questions Groceries asks, and the model's answers.
    private let junkLines = ["2 eggs (dfsafs -", "2 onions dfsafs", "2 eggs, beaten", "For the sauce:"]
    private let eggsJunk = DecisionQuestion.trailingText("(dfsafs -", language: "en")
    private let onionsName = DecisionQuestion.ingredientName("2 onions dfsafs", language: "en")
    private let onionsJunk = DecisionQuestion.trailingText("dfsafs", language: "en")
    private let beatenNote = DecisionQuestion.trailingText(", beaten", language: "en")
    private var junkAnswers: [DecisionQuestion: String] {
        [eggsJunk: "junk", onionsName: "onions", onionsJunk: "junk", beatenNote: "note"]
    }

    private func openJunk(_ decisions: DecisionRepository) async -> RecipeViewModel {
        let repository = FakeRecipeRepository()
        repository.openResult = Recipe(
            name: "Omelette", image: nil, ingredients: junkLines, instructions: ["Bake."], prepTime: nil, cookTime: nil,
            totalTime: nil, yield: "4 servings", sourceUrl: "https://example.com/omelette", id: 1, language: "en"
        )
        let flags = FeatureFlags(store: MemoryFeatureFlagStore())
        for flag in Flag.allCases { flags.set(flag, flag == .aiDecisions) }
        let vm = RecipeViewModel(
            recipeId: 1, url: nil, repository: repository, preferences: FakeAppPreferences(), clock: TestClock(),
            flags: flags, decisions: decisions
        )
        await settleMain()
        return vm
    }

    func testTheRecipeAsksAboutJunkOncePerVisitAndHidesItOnceDecided() async {
        let decisions = FakeDecisionRepository(junkAnswers)
        let vm = await openJunk(decisions)
        await settleMain { decisions.asked.count == 4 && vm.uiState.content.success?.ingredients[1] == "2 onions" }

        // The name lands first; its trailing text is asked then. The heading is never asked about.
        XCTAssertEqual(decisions.asked, [onionsName, eggsJunk, beatenNote, onionsJunk])
        XCTAssertEqual(vm.uiState.content.success?.ingredients, ["2 eggs", "2 onions", "2 eggs, beaten", "For the sauce:"])

        vm.onServingsChange(8)
        vm.onUnitSystemChange(.metric)
        await settleMain()
        XCTAssertEqual(decisions.asked.count, 4, "nothing is asked twice in a visit")
        XCTAssertEqual(vm.uiState.content.success?.ingredients, ["4 eggs", "4 onions", "4 eggs, beaten", "For the sauce:"])
    }

    func testAnAnswerAlreadyCachedFromGroceriesOrAnEarlierVisitCostsNoModelCall() async throws {
        let model = FakeDecisionModel(answer: "junk")
        let db = try AppDatabase(path: nil)
        for (question, answer) in junkAnswers {
            try await db.write { try AiDecisionDao(db: $0).insert(question, answer: answer, now: 1) }
        }
        var on = true
        let repository = DefaultDecisionRepository(
            db: db, model: model, clock: DataTestClock(), isOn: { on }, countBracketsOn: { false }
        )
        let vm = await openJunk(repository)
        await settleMain { vm.uiState.content.success?.ingredients.first == "2 eggs" }
        XCTAssertEqual(vm.uiState.content.success?.ingredients, ["2 eggs", "2 onions", "2 eggs, beaten", "For the sauce:"])
        XCTAssertTrue(model.asked.isEmpty)

        // aiDecisions off: every line as written, and nothing asked.
        on = false
        let off = await openJunk(repository)
        await settleMain()
        XCTAssertEqual(off.uiState.content.success?.ingredients, junkLines)
        XCTAssertTrue(model.asked.isEmpty)
    }

    func testTheLinesShowAsWrittenUntilAnAnswerLandsThenRenderAgainTicksInPlace() async {
        let decisions = FakeDecisionRepository() // the model can't answer yet
        let vm = await openJunk(decisions)
        vm.onIngredientChecked(1, true)
        XCTAssertEqual(vm.uiState.content.success?.ingredients, junkLines)

        decisions.answer([eggsJunk: "junk", onionsName: "onions", onionsJunk: "junk"])
        await settleMain { vm.uiState.content.success?.ingredients.first == "2 eggs" }
        XCTAssertEqual(vm.uiState.content.success?.ingredients, ["2 eggs", "2 onions", "2 eggs, beaten", "For the sauce:"])
        XCTAssertEqual(vm.uiState.checkedIngredients, [1])
        XCTAssertEqual(vm.uiState.content.success?.recipe.ingredients, junkLines, "the stored lines are never rewritten")
        let shared = vm.shareText() ?? ""
        XCTAssertTrue(shared.contains("INGREDIENTS\n2 eggs\n2 onions\n2 eggs, beaten\nFor the sauce:\n"), shared)

        // The pantry's use-up sheet (#147) gets the ticked line as shown.
        vm.onCookStart()
        vm.onStepDone()
        XCTAssertEqual(vm.uiState.cookFinished, FinishedCook(language: "en", lines: ["2 onions"]))
    }
}
