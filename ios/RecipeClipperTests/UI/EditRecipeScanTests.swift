import XCTest
@testable import RecipeClipper

/// "Scan a recipe" (#226): the review over a fake reader. Android's EditRecipeScanViewModelTest.
@MainActor
final class EditRecipeScanTests: XCTestCase {
    private let front = "file:///private/var/mobile/Containers/Shared/AppGroup/X/Scans/page-1-A.img"
    private let back = "file:///private/var/mobile/Containers/Shared/AppGroup/X/Scans/page-2-B.img"

    private let card = [
        PhotoLine(text: "Ingredients", confidence: 1),
        PhotoLine(text: "1 cup butter", confidence: 1),
        PhotoLine(text: "11/2 cups flour", confidence: 1),
        PhotoLine(text: "Directions", confidence: 1),
        PhotoLine(text: "1. Cream the butter.", confidence: 1),
        PhotoLine(text: "2. Stir in the flour.", confidence: 0.3),
    ]

    /// The German card of `shared/fixtures/languages/de.json`, as a photo's lines.
    private let germanCard = [
        "Omas Apfelkuchen", "ZUTATEN", "200 g Butter", "150 g Zucker", "3 Eier", "300 g Mehl",
        "1 Pck. Backpulver", "4 Äpfel", "ZUBEREITUNG:", "Butter und Zucker schaumig rühren.",
        "Eier nacheinander unterrühren.", "Mehl mit Backpulver mischen und mit der Milch unterrühren.",
        "Bei 180 °C etwa 45 Minuten backen.",
    ].map { PhotoLine(text: $0, confidence: 1) }

    private func viewModel(
        _ reader: FakePhotoTextReader, pages: [String], repository: FakeRecipeRepository = FakeRecipeRepository()
    ) -> EditRecipeViewModel {
        EditRecipeViewModel(recipeId: nil, repository: repository, photoReader: reader, scanPages: pages)
    }

    private func saved(_ id: Int64) -> Recipe {
        var recipe = Recipe(
            name: "Cookies", image: nil, ingredients: ["1 cup butter"], instructions: [], prepTime: nil,
            cookTime: nil, totalTime: nil, yield: nil, sourceUrl: "manual:abc"
        )
        recipe.id = id
        return recipe
    }

    func testReadsThePagesInOrderSortedWithSuspectAmountsAndUnsureLinesListed() async {
        let reader = FakePhotoTextReader(.read(card))
        let vm = viewModel(reader, pages: [front, back])
        await settleMain()

        XCTAssertEqual(reader.calls, [[front, back]])
        let state = vm.uiState
        XCTAssertTrue(state.scan)
        XCTAssertTrue(state.isNew)
        XCTAssertEqual(state.photo?.imageUrls, [front, back])
        XCTAssertEqual(state.photoOutcome, .read)
        // No title is guessed, and a scan's page is never the recipe's picture.
        XCTAssertEqual(state.draft.name, "")
        XCTAssertEqual(state.draft.image, "")
        XCTAssertEqual(state.draft.ingredientsText, "1 cup butter\n11/2 cups flour")
        XCTAssertEqual(state.draft.instructionsText, "Cream the butter.\nStir in the flour.")
        XCTAssertEqual(state.uncertain, ["11/2 cups flour", "Stir in the flour."])
    }

    func testNothingIsSavedUntilTheCookSavesThenAsATypedInRecipeWithNoPicture() async {
        let repository = FakeRecipeRepository()
        repository.addManualResult = saved(7)
        let vm = viewModel(FakePhotoTextReader(.read(card)), pages: [front], repository: repository)
        await settleMain()
        XCTAssertTrue(repository.addManualCalls.isEmpty)

        var draft = vm.uiState.draft
        draft.name = "Cookies"
        draft.ingredientsText = "1 cup butter\n1 1/2 cups flour"
        vm.onDraftChange(draft)
        vm.onSave()
        await settleMain()

        XCTAssertEqual(repository.addManualCalls.count, 1)
        XCTAssertEqual(repository.addManualCalls.first?.name, "Cookies")
        XCTAssertEqual(repository.addManualCalls.first?.image, "")
        XCTAssertEqual(repository.addManualCalls.first?.ingredients, ["1 cup butter", "1 1/2 cups flour"])
        XCTAssertEqual(repository.addManualLanguages, ["en"])
        XCTAssertTrue(repository.saveClipCalls.isEmpty)
        XCTAssertEqual(vm.uiState.savedId, 7)
    }

    func testAGermanCardIsSavedInGermanAsItsWordsSay() async {
        let repository = FakeRecipeRepository()
        repository.addManualResult = saved(3)
        let vm = viewModel(FakePhotoTextReader(.read(germanCard)), pages: [front], repository: repository)
        await settleMain()
        XCTAssertEqual(vm.uiState.photoOutcome, .read)

        var draft = vm.uiState.draft
        draft.name = "Omas Apfelkuchen"
        vm.onDraftChange(draft)
        vm.onSave()
        await settleMain()

        XCTAssertEqual(repository.addManualLanguages, ["de"])
    }

    func testNothingReadOpensTheReviewToFinishByHand() async {
        let repository = FakeRecipeRepository()
        let lines = [PhotoLine(text: "Cream butter and sugar,"), PhotoLine(text: "add the oats. Bake.")]
        let vm = viewModel(FakePhotoTextReader(.read(lines)), pages: [front], repository: repository)
        await settleMain()

        XCTAssertEqual(vm.uiState.photoOutcome, .notSorted)
        XCTAssertEqual(vm.uiState.draft.ingredientsText, "Cream butter and sugar,\nadd the oats. Bake.")

        // Not a recipe yet (no name): Save points out the rule and saves nothing.
        vm.onSave()
        await settleMain()
        XCTAssertTrue(vm.uiState.showInvalid)
        XCTAssertTrue(repository.addManualCalls.isEmpty)
    }

    func testAPageThatWontOpenFailsAndTryAgainReadsTheSamePages() async {
        let reader = FakePhotoTextReader(.failed)
        let vm = viewModel(reader, pages: [front, back])
        await settleMain()
        XCTAssertEqual(vm.uiState.photoOutcome, .failed)

        reader.result = .read(card)
        vm.onReadAgain()
        await settleMain()

        XCTAssertEqual(reader.calls, [[front, back], [front, back]])
        XCTAssertEqual(vm.uiState.photoOutcome, .read)
    }

    func testNoPagesIsAPlainNewRecipe() async {
        let reader = FakePhotoTextReader()
        let vm = viewModel(reader, pages: [])
        await settleMain()

        XCTAssertFalse(vm.uiState.scan)
        XCTAssertNil(vm.uiState.photo)
        XCTAssertTrue(reader.calls.isEmpty)
    }

    /// The real reader over a local page (the fixture card as a file URL, as a scan stages it):
    /// Vision reads it on the simulator, never through the image cache.
    func testTheVisionReaderReadsALocalPage() async throws {
        let card = try XCTUnwrap(Bundle(for: EditRecipeScanTests.self)
            .url(forResource: "card-front", withExtension: "jpg", subdirectory: "fixtures/reddit/photos"))
        let result = await VisionPhotoTextReader().read([card.absoluteString])

        guard case .read(let lines) = result else { return XCTFail("not read: \(result)") }
        XCTAssertTrue(lines.contains { $0.text.localizedCaseInsensitiveContains("butter") }, "\(lines.map(\.text))")
        XCTAssertNil(ImageLoader.shared.cache.cachedResponse(for: URLRequest(url: card)), "a local page is never cached")
    }
}
