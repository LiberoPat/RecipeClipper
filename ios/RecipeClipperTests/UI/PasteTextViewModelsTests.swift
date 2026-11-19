import XCTest
@testable import RecipeClipper

/// The three ways to paste (#372), each into the shared-text route: Home's and Recipes' "Paste a
/// link" sheets and the Ask an AI sheet's "Paste the answer". A link, a JSON recipe, a week, a
/// recipe written out, a list and nothing. Nothing is saved by any of them (the editor's Save does
/// that). Android's `PasteTextViewModelsTest` and `PastedTextRouterTest`.
@MainActor
final class PasteTextViewModelsTests: XCTestCase {

    private func recipe(_ name: String, day: String?) -> String {
        "{\"@context\": \"https://schema.org\", \"@type\": \"Recipe\", \"name\": \"\(name)\", "
            + (day.map { "\"day\": \"\($0)\", " } ?? "")
            + "\"recipeYield\": \"2 servings\", \"recipeIngredient\": [\"1 cup rice\", \"2 eggs\"], "
            + "\"recipeInstructions\": [{\"@type\": \"HowToStep\", \"text\": \"Cook.\"}]}"
    }

    private var json: String { "Here you go:\n```json\n" + recipe("Lemon Pasta", day: nil) + "\n```" }
    private var plan: String {
        "[" + recipe("Fried rice", day: "2026-11-23") + ",\n" + recipe("Omelette", day: "2026-11-24") + "]"
    }
    private let prose = "Ingredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix.\n2. Bake."

    private let router = PastedTextRouter()

    /// One way to paste: hands `text` over and returns where it led (nil: nowhere).
    private typealias Paster = (String) async -> PasteTarget?

    private func home(_ repository: FakeRecipeRepository = FakeRecipeRepository()) -> Paster {
        { [router] text in
            let vm = HomeViewModel(repository: repository, pasted: router)
            await settleMain()
            vm.onPasteLink()
            vm.onLinkChange(text)
            let target = vm.onOpenLink()
            XCTAssertEqual(vm.uiState.pastingLink, target == nil, "the sheet closes only on a target")
            XCTAssertTrue(repository.addManualCalls.isEmpty && repository.saveClipCalls.isEmpty, "nothing saved")
            return target
        }
    }

    private func recipes(_ repository: FakeRecipeRepository = FakeRecipeRepository()) -> Paster {
        { [router] text in
            let vm = RecipesViewModel(
                repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep, pasted: router
            )
            vm.onPasteLink()
            vm.onLinkChange(text)
            let target = vm.onOpenLink()
            XCTAssertEqual(vm.uiState.pastingLink, target == nil, "the sheet closes only on a target")
            XCTAssertTrue(repository.addManualCalls.isEmpty && repository.saveClipCalls.isEmpty, "nothing saved")
            return target
        }
    }

    private func askAiViewModel() -> AskAiViewModel {
        AskAiViewModel(
            pantry: FakePantryRepository([]), lists: FakeListRepository(), recipes: FakeRecipeRepository(),
            preferences: FakeAppPreferences(), clock: TestClock(now: 1_800_000_000_000), pasted: router
        )
    }

    private var askAi: Paster {
        { [self] text in askAiViewModel().onPasteAnswer(text) }
    }

    private func forEachPaster(_ check: (Paster) async -> Void) async {
        for paster in [home(), recipes(), askAi] { await check(paster) }
    }

    func testALinkImports() async {
        await forEachPaster { paste in
            let target = await paste("https://example.com/soup")
            XCTAssertEqual(target, .link("https://example.com/soup"))
        }
    }

    func testAJsonRecipeOpensTheEditor() async {
        await forEachPaster { paste in
            guard case .review(let reading) = await paste(json) else { return XCTFail("not the editor") }
            XCTAssertEqual(reading.name, "Lemon Pasta")
            XCTAssertTrue(reading.fromChat)
        }
    }

    func testAWeekOfRecipesOpensTheMealPlanReview() async {
        await forEachPaster { paste in
            guard case .plan(let text) = await paste(plan) else { return XCTFail("not the plan's") }
            XCTAssertEqual(MealPlanImport.read(text)?.items.map(\.recipe.name), ["Fried rice", "Omelette"])
        }
    }

    func testARecipeWrittenOutOpensTheEditorSorted() async {
        await forEachPaster { paste in
            guard case .review(let reading) = await paste(prose) else { return XCTFail("not the editor") }
            XCTAssertTrue(reading.sorted)
        }
    }

    func testABulletedListIsOfferedOnGroceries() async {
        await forEachPaster { paste in
            let target = await paste("- milk\n- eggs\n- flour")
            XCTAssertEqual(target, .list("- milk\n- eggs\n- flour"))
        }
    }

    func testEmptyTextGoesNowhere() async {
        await forEachPaster { paste in
            let none = await paste("")
            XCTAssertNil(none)
            let blank = await paste("   \n")
            XCTAssertNil(blank)
        }
    }

    func testANumberWithADotIsNotALinkButText() {
        guard case .review(let reading) = router.open("1.5 cups flour") else { return XCTFail("not the editor") }
        XCTAssertFalse(reading.sorted)
        XCTAssertEqual(router.open("  example.com/soup \n"), .link("https://example.com/soup"))
        XCTAssertEqual(router.open("Great soup\nhttps://example.com/soup"), .link("https://example.com/soup"))
    }

    func testWithShareTextOffOnlyALinkOrLinesGoAnywhere() {
        // As before #306: the first link imports, and any lines are offered as a list.
        let off = PastedTextRouter(shareTextOn: { false }, mealPlanOn: { true })
        XCTAssertNil(off.open("   "))
        XCTAssertEqual(off.open("Great soup\nhttps://example.com/soup"), .link("https://example.com/soup"))
        XCTAssertEqual(off.open("- milk\n- eggs"), .list("- milk\n- eggs"))
    }

    // What each says when there is nothing to open.

    func testTheSheetsGoIsOffForBlankTextAndSaysSoForTextTooLongToRead() async {
        let vm = HomeViewModel(repository: FakeRecipeRepository(), pasted: router)
        await settleMain()
        vm.onPasteLink()
        XCTAssertFalse(vm.uiState.canOpenLink)
        vm.onLinkChange("  ")
        XCTAssertFalse(vm.uiState.canOpenLink)

        vm.onLinkChange(String(repeating: "x", count: SharedText.maxLength + 1))
        XCTAssertTrue(vm.uiState.canOpenLink)
        XCTAssertNil(vm.onOpenLink())
        XCTAssertTrue(vm.uiState.linkNoRecipe)
        XCTAssertTrue(vm.uiState.pastingLink)
    }

    func testPasteTheAnswerSaysAnEmptyClipboardIsEmptyAndClearsWhenAnythingChanges() async {
        let vm = askAiViewModel()
        await settleMain()

        XCTAssertNil(vm.onPasteAnswer(nil))
        XCTAssertEqual(vm.uiState.pasteProblem, .empty)
        XCTAssertNil(vm.onPasteAnswer("  "))
        XCTAssertEqual(vm.uiState.pasteProblem, .empty)

        vm.onRequestChange("soup")
        XCTAssertNil(vm.uiState.pasteProblem)

        XCTAssertNil(vm.onPasteAnswer(String(repeating: "x", count: SharedText.maxLength + 1)))
        XCTAssertEqual(vm.uiState.pasteProblem, .noRecipe)
    }
}
