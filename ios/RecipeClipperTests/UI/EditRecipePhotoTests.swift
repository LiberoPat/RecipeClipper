import XCTest
@testable import RecipeClipper

/// "Read the photo" (#198): the editor over a fake reader. Android's EditRecipePhotoViewModelTest.
@MainActor
final class EditRecipePhotoTests: XCTestCase {
    private let postUrl = "https://www.reddit.com/r/Old_Recipes/comments/1f6d4ef/aunt_junes_oatmeal_cookies/"
    private let front = "https://preview.redd.it/front.jpg"
    private let back = "https://preview.redd.it/back.jpg"

    private let card = [
        PhotoLine(text: "Ingredients", confidence: 1),
        PhotoLine(text: "1 cup butter", confidence: 1),
        PhotoLine(text: "1 1/2 cups flour", confidence: 0.3),
        PhotoLine(text: "Directions", confidence: 1),
        PhotoLine(text: "1. Cream the butter.", confidence: 1),
        PhotoLine(text: "2. Stir in the flour.", confidence: 1),
    ]

    private func viewModel(_ reader: FakePhotoTextReader, repository: FakeRecipeRepository = FakeRecipeRepository()) -> EditRecipeViewModel {
        EditRecipeViewModel(
            recipeId: nil, repository: repository,
            photo: PhotoPost(url: postUrl, title: "Aunt June's oatmeal cookies", imageUrls: [front, back]),
            photoReader: reader
        )
    }

    func testReadsEveryPictureInOrderAndFillsTheEditorSortedWithUnsureLinesMarked() async {
        let reader = FakePhotoTextReader(.read(card))
        let vm = viewModel(reader)
        await settleMain()

        XCTAssertEqual(reader.calls, [[front, back]])
        let state = vm.uiState
        XCTAssertTrue(state.isNew)
        XCTAssertFalse(state.reading)
        XCTAssertEqual(state.photoOutcome, .read)
        XCTAssertEqual(state.draft.name, "Aunt June's oatmeal cookies")
        XCTAssertEqual(state.draft.image, front)
        XCTAssertEqual(state.draft.ingredientsText, "1 cup butter\n1 1/2 cups flour")
        XCTAssertEqual(state.draft.instructionsText, "Cream the butter.\nStir in the flour.")
        XCTAssertEqual(state.uncertain, ["1 1/2 cups flour"])
    }

    func testNothingIsSavedUntilTheCookSavesThenUnderThePostsLink() async {
        let repository = FakeRecipeRepository()
        let vm = viewModel(FakePhotoTextReader(.read(card)), repository: repository)
        await settleMain()
        XCTAssertTrue(repository.saveClipCalls.isEmpty)

        var draft = vm.uiState.draft
        draft.ingredientsText = "1 cup butter\n1 1/4 cups flour"
        vm.onDraftChange(draft)
        vm.onSave()
        await settleMain()

        let saved = repository.saveClipCalls.first
        XCTAssertEqual(repository.saveClipCalls.count, 1)
        XCTAssertEqual(saved?.sourceUrl, postUrl)
        XCTAssertEqual(saved?.sourceType, .reddit)
        XCTAssertEqual(saved?.name, "Aunt June's oatmeal cookies")
        XCTAssertEqual(saved?.image, front)
        XCTAssertEqual(saved?.ingredients, ["1 cup butter", "1 1/4 cups flour"])
        XCTAssertEqual(saved?.instructions, ["Cream the butter.", "Stir in the flour."])
        XCTAssertEqual(saved?.language, "en")
        XCTAssertEqual(vm.uiState.savedId, 1)
    }

    func testLinesThatDontSortOpenTheEditorToFinishByHandEveryLineKept() async {
        let lines = [PhotoLine(text: "Cream butter and sugar,"), PhotoLine(text: "add the oats. Bake.")]
        let vm = viewModel(FakePhotoTextReader(.read(lines)))
        await settleMain()

        XCTAssertEqual(vm.uiState.photoOutcome, .notSorted)
        XCTAssertEqual(vm.uiState.draft.ingredientsText, "Cream butter and sugar,\nadd the oats. Bake.")
        XCTAssertEqual(vm.uiState.draft.instructionsText, "")
        XCTAssertEqual(vm.uiState.draft.name, "Aunt June's oatmeal cookies")
        XCTAssertEqual(vm.uiState.draft.image, front)
    }

    func testAPhotoWithNoTextLeavesTheTitleAndPhotoToFinishByHand() async {
        let repository = FakeRecipeRepository()
        let vm = viewModel(FakePhotoTextReader(.read([])), repository: repository)
        await settleMain()

        XCTAssertEqual(vm.uiState.photoOutcome, .notSorted)
        XCTAssertEqual(vm.uiState.draft.name, "Aunt June's oatmeal cookies")
        XCTAssertEqual(vm.uiState.draft.ingredientsText, "")

        // Not a recipe yet: Save points out the rule and saves nothing.
        vm.onSave()
        await settleMain()
        XCTAssertTrue(vm.uiState.showInvalid)
        XCTAssertTrue(repository.saveClipCalls.isEmpty)
    }

    func testAPictureThatWontLoadOffersTryAgainWhichReadsAgain() async {
        let reader = FakePhotoTextReader(.failed)
        let vm = viewModel(reader)
        await settleMain()
        XCTAssertEqual(vm.uiState.photoOutcome, .failed)
        XCTAssertEqual(vm.uiState.draft.name, "Aunt June's oatmeal cookies")

        reader.result = .read(card)
        vm.onReadAgain()
        await settleMain()

        XCTAssertEqual(reader.calls.count, 2)
        XCTAssertEqual(vm.uiState.photoOutcome, .read)
    }

    func testWhileReadingSaveWaitsAndLeavingEndsTheReadWithNothingSaved() async {
        let repository = FakeRecipeRepository()
        let reader = FakePhotoTextReader(.read(card))
        reader.held = true
        var vm: EditRecipeViewModel? = viewModel(reader, repository: repository)
        await settleMain()
        XCTAssertEqual(vm?.uiState.reading, true)

        vm?.onSave()
        await settleMain()
        XCTAssertEqual(vm?.uiState.saving, false)

        weak var gone = vm
        vm = nil // Back: the screen's ViewModel goes
        reader.release()
        await settleMain()

        XCTAssertNil(gone)
        XCTAssertTrue(repository.saveClipCalls.isEmpty)
    }

    func testAFullFreeLibraryKeepsTheEditorOpenWithThePrompt() async {
        let repository = FakeRecipeRepository()
        let vm = viewModel(FakePhotoTextReader(.read(card)), repository: repository)
        await settleMain()
        repository.saveClipResult = .notKept(Recipe(
            name: "Aunt June's oatmeal cookies", image: nil, ingredients: ["1 cup butter"], instructions: [],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil, sourceUrl: postUrl
        ))

        vm.onSave()
        await settleMain()

        XCTAssertTrue(vm.uiState.libraryFull)
        XCTAssertNil(vm.uiState.savedId)
    }

    func testAFailedSaveSaysSoAndKeepsTheEditor() async {
        let repository = FakeRecipeRepository()
        repository.saveClipResult = .error(.saveFailed)
        let vm = viewModel(FakePhotoTextReader(.read(card)), repository: repository)
        await settleMain()

        vm.onSave()
        await settleMain()

        XCTAssertTrue(vm.uiState.saveFailed)
        XCTAssertNil(vm.uiState.savedId)
    }
}
