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
        await settleMain(until: { !vm.uiState.reading })

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
        XCTAssertEqual(state.flags.map(\.raw), ["11/2 cups flour", "Stir in the flour."])
        XCTAssertEqual(state.checks.map(\.steps), [false, true])
    }

    /// #286: a strip is its line's box with a little room, cut from the page decoded at most 1600
    /// pixels a side (the fixture card, a real JPEG), and nothing more of the page.
    func testAStripIsCutFromThePageDecodedSmall() throws {
        let card = try XCTUnwrap(Bundle(for: EditRecipeScanTests.self)
            .url(forResource: "card-front", withExtension: "jpg", subdirectory: "fixtures/reddit/photos"))
        let data = try Data(contentsOf: card)
        let page = try XCTUnwrap(ReviewImages.downsampled(data, maxSide: ReviewImages.stripPageSide))
        XCTAssertLessThanOrEqual(max(page.width, page.height), ReviewImages.stripPageSide)

        let box = PhotoBox(left: 0.1, top: 0.4, right: 0.6, bottom: 0.45)
        let strip = try XCTUnwrap(ReviewImages.strip(data, box: box))
        let rect = ReviewImages.pixels(box.padded(width: page.width, height: page.height), width: page.width, height: page.height)
        XCTAssertEqual(strip.size, rect.size)
        XCTAssertLessThan(strip.size.height, CGFloat(page.height) / 10)

        XCTAssertEqual(ReviewImages.pixels(PhotoBox(left: 0, top: 0, right: 1, bottom: 1), width: 100, height: 50), CGRect(x: 0, y: 0, width: 100, height: 50))
        XCTAssertEqual(ReviewImages.pixels(PhotoBox(left: 0.5, top: 0.5, right: 0.5, bottom: 0.5), width: 100, height: 50), CGRect(x: 50, y: 25, width: 1, height: 1))
        XCTAssertEqual(ReviewImages.stripHeight(width: 300, aspect: 3), 56, "a short line, at most 56 points")
        XCTAssertEqual(ReviewImages.stripHeight(width: 300, aspect: 30), 28, "a long line, at least 28")
        XCTAssertEqual(ReviewImages.stripHeight(width: 300, aspect: 7.5), 40)
    }

    /// #286: a scan's strip is cut from the cook's own page, read from its file, never cached.
    func testAScansCardHasTheStripOfItsPage() async {
        let box = PhotoBox(left: 0.1, top: 0.5, right: 0.7, bottom: 0.56)
        let vm = viewModel(FakePhotoTextReader(.read([
            PhotoLine(text: "Ingredients", page: 0), PhotoLine(text: "11/2 cups flour", page: 1, box: box),
            PhotoLine(text: "Directions", page: 1), PhotoLine(text: "1. Mix.", page: 1),
        ])), pages: [front, back])
        await settleMain(until: { !vm.uiState.reading })

        XCTAssertEqual(vm.uiState.checks.map(\.strip), [PhotoStrip(image: back, box: box, local: true)])
    }

    func testNothingIsSavedUntilTheCookSavesThenAsATypedInRecipeWithNoPicture() async {
        let repository = FakeRecipeRepository()
        repository.addManualResult = saved(7)
        let vm = viewModel(FakePhotoTextReader(.read(card)), pages: [front], repository: repository)
        await settleMain(until: { !vm.uiState.reading })
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
        await settleMain(until: { !vm.uiState.reading })
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
        await settleMain(until: { !vm.uiState.reading })

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
        await settleMain(until: { !vm.uiState.reading })
        XCTAssertEqual(vm.uiState.photoOutcome, .failed)

        reader.result = .read(card)
        vm.onReadAgain()
        await settleMain(until: { !vm.uiState.reading })

        XCTAssertEqual(reader.calls, [[front, back], [front, back]])
        XCTAssertEqual(vm.uiState.photoOutcome, .read)
    }

    func testNoPagesIsAPlainNewRecipe() async {
        let reader = FakePhotoTextReader()
        let vm = viewModel(reader, pages: [])
        await settleMain(until: { !vm.uiState.reading })

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
        // Each line says where it was read (#286): the first page, a box on it, read top down.
        XCTAssertTrue(lines.allSatisfy { $0.page == 0 }, "\(lines.map(\.page))")
        let boxes = lines.compactMap(\.box)
        XCTAssertEqual(boxes.count, lines.count)
        XCTAssertTrue(boxes.allSatisfy { 0 <= $0.left && $0.left < $0.right && $0.right <= 1 && 0 <= $0.top && $0.top < $0.bottom && $0.bottom <= 1 })
        XCTAssertLessThan(try XCTUnwrap(boxes.first).top, try XCTUnwrap(boxes.last).top, "the first line is above the last")
    }

    /// Vision's box (normalised, origin bottom left) as the review's (origin top left) (#286).
    func testVisionsBoxIsFlippedToATopLeftOrigin() {
        XCTAssertEqual(
            VisionPhotoTextReader.box(CGRect(x: 0.1, y: 0.7, width: 0.5, height: 0.1)),
            PhotoBox(left: 0.1, top: 0.2, right: 0.6, bottom: 0.3)
        )
        // Past the picture's edge: clamped to it.
        XCTAssertEqual(
            VisionPhotoTextReader.box(CGRect(x: -0.1, y: -0.05, width: 0.6, height: 0.15)),
            PhotoBox(left: 0, top: 0.9, right: 0.5, bottom: 1)
        )
        XCTAssertNil(VisionPhotoTextReader.box(CGRect(x: 0.2, y: 0.2, width: 0, height: 0.1)))
        XCTAssertNil(VisionPhotoTextReader.box(CGRect(x: 1.2, y: 0.2, width: 0.3, height: 0.1)))
    }

    /// The fraction-glyph cards (#245), read by the real Vision: what it makes of ½ ¼ ¾ ⅓ ⅔ ⅛ is
    /// printed (`FRACTION-OCR`, the measurements in docs/decisions.md), and the "%" Vision gave
    /// for the handwritten ⅔ is listed to check, while the "70%" beside it isn't.
    func testTheVisionReaderOnFractionGlyphs() async throws {
        var read: [String] = []
        for name in ["card-fractions-print", "card-fractions-hand"] {
            let card = try XCTUnwrap(Bundle(for: EditRecipeScanTests.self)
                .url(forResource: name, withExtension: "jpg", subdirectory: "fixtures/reddit/photos"))
            let result = await VisionPhotoTextReader().read([card.absoluteString])
            guard case .read(let lines) = result else { return XCTFail("not read: \(result)") }
            for line in lines { print("FRACTION-OCR \(name)\t\(line.confidence ?? -1)\t\(line.text)") }
            read += lines.map(\.text)
        }
        let flagged = read.filter { !PhotoTextSorter.check($0).isEmpty }
        XCTAssertFalse(flagged.contains { $0.contains("70%") }, "\(flagged)")
        if read.contains(where: { $0.contains("% cups oats") }) {
            XCTAssertTrue(flagged.contains { $0.contains("% cups oats") }, "\(flagged)")
        }
    }
}
