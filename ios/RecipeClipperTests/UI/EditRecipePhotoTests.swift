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
        await settleMain(until: { !vm.uiState.reading })

        XCTAssertEqual(reader.calls, [[front, back]])
        let state = vm.uiState
        XCTAssertTrue(state.isNew)
        XCTAssertFalse(state.reading)
        XCTAssertEqual(state.photoOutcome, .read)
        XCTAssertEqual(state.draft.name, "Aunt June's oatmeal cookies")
        XCTAssertEqual(state.draft.image, front)
        XCTAssertEqual(state.draft.ingredientsText, "1 cup butter\n1 1/2 cups flour")
        XCTAssertEqual(state.draft.instructionsText, "Cream the butter.\nStir in the flour.")
        XCTAssertEqual(state.flags, [LineFlag(raw: "1 1/2 cups flour", shown: "1 1/2 cups flour", reasons: [.unsure])])
        XCTAssertTrue(state.checkHeading)
    }

    func testALineFlaggedOnlyForItsSizeIsNamedAsLargeBesideTheHeadingAHardToReadLineBrings() async {
        // #281: "34 tsp salt" for ¾ is read clearly, just too much; "1 1/2 cups flour" was unsure.
        let lines = [PhotoLine(text: "Ingredients"), PhotoLine(text: "34 tsp salt")] + card.dropFirst()
        let vm = viewModel(FakePhotoTextReader(.read(lines)))
        await settleMain(until: { !vm.uiState.reading })

        XCTAssertEqual(vm.uiState.flags.map(\.raw), ["34 tsp salt", "1 1/2 cups flour"])
        XCTAssertEqual(vm.uiState.flags.map(\.largeOnly), [true, false])
        XCTAssertTrue(vm.uiState.checkHeading)
    }

    func testNoHeadingWhenEveryLineIsListedOnlyForItsSize() async {
        let lines = ["Ingredients", "34 tsp salt", "12 tbsp vinegar", "1 cup flour", "Directions", "1. Mix."]
        let vm = viewModel(FakePhotoTextReader(.read(lines.map { PhotoLine(text: $0, confidence: 1) })))
        await settleMain(until: { !vm.uiState.reading })

        XCTAssertEqual(vm.uiState.flags.map(\.raw), ["34 tsp salt", "12 tbsp vinegar"])
        XCTAssertFalse(vm.uiState.checkHeading)
    }

    func testALineBothMisreadAndTooLargeKeepsTheHeadingAndIsntNamedAsLarge() async {
        // #283: "11/2" lost its space, and "34 tsp" is too much.
        let lines = ["Ingredients", "34 tsp salt, 11/2 tsp pepper", "1 cup flour", "Directions", "1. Mix."]
        let vm = viewModel(FakePhotoTextReader(.read(lines.map { PhotoLine(text: $0, confidence: 1) })))
        await settleMain(until: { !vm.uiState.reading })

        XCTAssertEqual(vm.uiState.flags.count, 1)
        XCTAssertEqual(vm.uiState.flags.first?.reasons, [.misread, .large])
        XCTAssertEqual(vm.uiState.flags.first?.largeOnly, false)
        XCTAssertTrue(vm.uiState.checkHeading)
    }

    func testAChipFixesTheLineInTheBoxAndItsOneEntryKeepingTheLinesMarkup() async throws {
        // #283: nothing sorts, so each line stays as read, "▢" and all, and is listed without it.
        let lines = ["Grandma's card", "▢ 2% cups oats", "▢ 34 tsp salt"]
        let vm = viewModel(FakePhotoTextReader(.read(lines.map { PhotoLine(text: $0, confidence: 1) })))
        await settleMain(until: { !vm.uiState.reading })
        XCTAssertEqual(vm.uiState.photoOutcome, .notSorted)
        let oats = try XCTUnwrap(vm.uiState.flags.first)
        XCTAssertEqual(oats, LineFlag(raw: "▢ 2% cups oats", shown: "2% cups oats", reasons: [.misread, .fraction]))

        vm.onFraction(oats, "⅔")

        XCTAssertEqual(vm.uiState.draft.ingredientsText, "Grandma's card\n▢ 2⅔ cups oats\n▢ 34 tsp salt")
        XCTAssertEqual(vm.uiState.flags, [
            LineFlag(raw: "▢ 2⅔ cups oats", shown: "2⅔ cups oats", reasons: [.misread]),
            LineFlag(raw: "▢ 34 tsp salt", shown: "34 tsp salt", reasons: [.large]),
        ])
    }

    func testAChipForALineTheCookHasChangedOnlyTakesItsChipsAway() async throws {
        let lines = ["Ingredients", "2 % cups oats", "1 cup flour", "Directions", "1. Mix."]
        let vm = viewModel(FakePhotoTextReader(.read(lines.map { PhotoLine(text: $0, confidence: 1) })))
        await settleMain(until: { !vm.uiState.reading })
        let oats = try XCTUnwrap(vm.uiState.flags.first)
        var draft = vm.uiState.draft
        draft.ingredientsText = "2 ¼ cups oats\n1 cup flour"
        vm.onDraftChange(draft)

        vm.onFraction(oats, "⅔")

        XCTAssertEqual(vm.uiState.draft.ingredientsText, "2 ¼ cups oats\n1 cup flour")
        XCTAssertEqual(vm.uiState.flags, [LineFlag(raw: "2 % cups oats", shown: "2 % cups oats", reasons: [.misread])])
    }

    func testNothingIsSavedUntilTheCookSavesThenUnderThePostsLink() async {
        let repository = FakeRecipeRepository()
        let vm = viewModel(FakePhotoTextReader(.read(card)), repository: repository)
        await settleMain(until: { !vm.uiState.reading })
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
        await settleMain(until: { !vm.uiState.reading })

        XCTAssertEqual(vm.uiState.photoOutcome, .notSorted)
        XCTAssertEqual(vm.uiState.draft.ingredientsText, "Cream butter and sugar,\nadd the oats. Bake.")
        XCTAssertEqual(vm.uiState.draft.instructionsText, "")
        XCTAssertEqual(vm.uiState.draft.name, "Aunt June's oatmeal cookies")
        XCTAssertEqual(vm.uiState.draft.image, front)
    }

    func testAPhotoWithNoTextLeavesTheTitleAndPhotoToFinishByHand() async {
        let repository = FakeRecipeRepository()
        let vm = viewModel(FakePhotoTextReader(.read([])), repository: repository)
        await settleMain(until: { !vm.uiState.reading })

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
        await settleMain(until: { !vm.uiState.reading })
        XCTAssertEqual(vm.uiState.photoOutcome, .failed)
        XCTAssertEqual(vm.uiState.draft.name, "Aunt June's oatmeal cookies")

        reader.result = .read(card)
        vm.onReadAgain()
        await settleMain(until: { !vm.uiState.reading })

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
        await settleMain(until: { !vm.uiState.reading })
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
        await settleMain(until: { !vm.uiState.reading })

        vm.onSave()
        await settleMain()

        XCTAssertTrue(vm.uiState.saveFailed)
        XCTAssertNil(vm.uiState.savedId)
    }
}
