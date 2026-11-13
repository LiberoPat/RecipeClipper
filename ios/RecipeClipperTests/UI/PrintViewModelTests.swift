import PDFKit
import XCTest
@testable import RecipeClipper

/// Print or save as PDF, and a list's cookbook (#318; Android's PrintViewModelTest and, for the
/// PDF itself, PrintPdfTest), over fakes.
@MainActor
final class PrintViewModelTests: XCTestCase {

    private func recipe(_ id: Int64, _ name: String, servingsTarget: Int? = nil) -> Recipe {
        var recipe = Recipe(
            name: name, image: nil, ingredients: ["1 cup milk", "For the top:", "2 oz butter"],
            instructions: ["Stir."], prepTime: nil, cookTime: nil, totalTime: nil,
            yield: "2 servings", sourceUrl: "https://example.com/\(id)"
        )
        recipe.id = id
        recipe.notes = "My note"
        recipe.servingsTarget = servingsTarget
        return recipe
    }

    private let recipes = FakeRecipeRepository()

    private func viewModel() -> PrintViewModel {
        recipes.recipes.value = [1: recipe(1, "Pancakes", servingsTarget: 4), 2: recipe(2, "Waffles")]
        return PrintViewModel(recipes: recipes, preferences: FakeAppPreferences(unitSystem: .metric), decisions: FakeDecisionRepository())
    }

    private func cookbook(_ vm: PrintViewModel, _ ids: [Int64]) async -> [CookbookRecipe]? {
        vm.makeCookbook(title: "Breakfast", recipeIds: ids, labels: .english, contents: "Contents")
        await vm.currentWork?.value
        guard case .cookbook(let title, let contents, let recipes) = vm.uiState.job else { return nil }
        XCTAssertEqual(title, "Breakfast")
        XCTAssertEqual(contents, "Contents")
        return recipes
    }

    func testARecipeIsPrintedAsHandedOver() {
        let vm = viewModel()
        vm.printRecipe(title: "Pancakes", blocks: [.title("Pancakes")])
        XCTAssertEqual(vm.uiState.job, .recipe(title: "Pancakes", blocks: [.title("Pancakes")]))
        vm.onDone()
        XCTAssertNil(vm.uiState.job)
        XCTAssertFalse(vm.uiState.busy)
    }

    func testACookbookHoldsTheListsRecipesInOrderAMissingOneSkipped() async {
        let recipes = await cookbook(viewModel(), [2, 9, 1])
        XCTAssertEqual(recipes?.map(\.name), ["Waffles", "Pancakes"])
    }

    func testEachRecipeRendersAsTheReadingViewOpensItAtItsServingsAndInTheCooksUnits() async {
        let blocks = await cookbook(viewModel(), [1])?.first?.blocks ?? []
        XCTAssertTrue(blocks.contains(.meta("Serves 4 (originally 2)")))
        XCTAssertEqual(blocks.filter {
            switch $0 { case .ingredient, .groupHeading: return true; default: return false }
        }, [.ingredient("480 ml milk"), .groupHeading("For the top:"), .ingredient("115 g butter")])
    }

    func testACookbookLeavesTheCooksNotesOut() async {
        let blocks = await cookbook(viewModel(), [1])?.first?.blocks ?? []
        XCTAssertFalse(blocks.contains { if case .note = $0 { return true } else { return false } })
    }

    func testACookbookWithNothingLeftInItFails() async {
        let vm = viewModel()
        vm.makeCookbook(title: "Breakfast", recipeIds: [9], labels: .english, contents: "Contents")
        await vm.currentWork?.value
        XCTAssertTrue(vm.uiState.failed)
        vm.onFailureShown()
        XCTAssertFalse(vm.uiState.failed)
    }

    func testACookbookReadsItsRecipesInOneQuery() async {
        let cards = await cookbook(viewModel(), [2, 1])
        XCTAssertEqual(recipes.byIdsQueries, [[2, 1]])
        XCTAssertEqual(cards?.count, 2)
    }

    func testACookbookIsBusyUntilItIsHandedOver() async {
        let vm = viewModel()
        vm.makeCookbook(title: "Breakfast", recipeIds: [1], labels: .english, contents: "Contents")
        XCTAssertTrue(vm.uiState.busy)
        await vm.currentWork?.value
        XCTAssertTrue(vm.uiState.busy)
        vm.onDone()
        XCTAssertEqual(vm.uiState, PrintUiState())
    }

    func testLeavingTheScreenDropsTheJobBuiltOrStillBuilding() async {
        let vm = viewModel()
        vm.makeCookbook(title: "Breakfast", recipeIds: [1], labels: .english, contents: "Contents")
        vm.onLeave()
        await vm.currentWork?.value
        XCTAssertEqual(vm.uiState, PrintUiState())

        vm.printRecipe(title: "Pancakes", blocks: [.title("Pancakes")])
        vm.onLeave()
        XCTAssertEqual(vm.uiState, PrintUiState())
    }

    func testTheFailureAlertGoingLeavesAJobStartedMeanwhileAlone() async {
        let vm = viewModel()
        vm.makeCookbook(title: "Breakfast", recipeIds: [9], labels: .english, contents: "Contents")
        await vm.currentWork?.value
        XCTAssertTrue(vm.uiState.failed)
        vm.printRecipe(title: "Pancakes", blocks: [.title("Pancakes")])
        vm.onFailureShown()
        XCTAssertEqual(vm.uiState, PrintUiState(busy: true, job: .recipe(title: "Pancakes", blocks: [.title("Pancakes")])))
    }

    // MARK: The PDF

    private func card(_ name: String, steps: Int) -> [RecipePrint.Block] {
        [.title(name), .credit("example.com"), .meta("Serves 4"), .section("Ingredients")]
            + (1...6).map { .ingredient("\($0) cups flour") } + [.section("Instructions")]
            + (1...steps).map { .step($0, String(repeating: "Stir the batter well, then rest it a while. ", count: 4)) }
    }

    private func pages(_ data: Data) -> Int { PDFDocument(data: data)?.pageCount ?? 0 }

    func testAShortRecipeIsOnePage() {
        XCTAssertEqual(pages(PrintPdf().recipe(card("Pancakes", steps: 3), photos: [:])), 1)
    }

    func testALongRecipeBreaksAcrossPages() {
        XCTAssertGreaterThan(pages(PrintPdf().recipe(card("Pancakes", steps: 40), photos: [:])), 1)
    }

    func testAStepTallerThanAPageRunsOnToTheNextSplitBetweenLines() {
        // One step of some 7,000 characters: once cut off at the foot of the page (#331).
        let step = String(repeating: "Stir the batter well, then rest it a while. ", count: 160)
        let pdf = PrintPdf().recipe([.title("Pancakes"), .section("Instructions"), .step(1, step)], photos: [:])
        let document = PDFDocument(data: pdf)
        XCTAssertEqual(document?.pageCount, 2)
        XCTAssertTrue(document?.page(at: 1)?.string?.contains("Stir the batter") ?? false)
        XCTAssertTrue(document?.page(at: 0)?.string?.contains("1.") ?? false)
    }

    func testACookbookHasATitlePageContentsAndEachRecipeFromANewPage() {
        let pdf = PrintPdf().cookbook(
            title: "Breakfast", contents: "Contents",
            recipes: [("Pancakes", card("Pancakes", steps: 3)), ("Waffles", card("Waffles", steps: 3))], photos: [:]
        )
        XCTAssertEqual(pages(pdf), 4)
        XCTAssertTrue(PDFDocument(data: pdf)?.page(at: 1)?.string?.contains("Waffles") ?? false)
    }
}
