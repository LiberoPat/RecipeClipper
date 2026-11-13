import XCTest
@testable import RecipeClipper

/// Text shared in (#306): the share extension leaves a recipe, or text that is neither a link
/// nor a list, for the app's editor (`PendingText`), read once (#334), behind the shareText flag.
@MainActor
final class TextHandOffTests: XCTestCase {

    private let recipeText = "Ingredients:\n- 2 cups flour\n- 1 tsp salt\nMethod:\n1. Mix.\n2. Bake."

    private func pendingText() -> PendingText {
        PendingText(defaults: UserDefaults(suiteName: "ShareImportPendingText-\(UUID().uuidString)")!)
    }

    private func recipe() -> Recipe {
        Recipe(
            name: "Scones", image: nil, ingredients: ["2 cups flour"], instructions: ["Mix."],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: nil,
            sourceUrl: "https://example.com/scones", id: 7
        )
    }

    private func viewModel(
        _ repository: ReconnectCountingRepository, pending: PendingText, on: Bool = true,
        pendingScan: PendingScan? = nil, makeReceiveList: (@MainActor () -> ReceiveListViewModel?)? = nil
    ) -> ShareImportViewModel {
        ShareImportViewModel(
            repository: repository, makeReceiveList: makeReceiveList, pendingScan: pendingScan,
            shareTextOn: { on }, pendingText: pending, clock: DataTestClock(5_000)
        )
    }

    private func reading(_ text: String) -> SharedText.Reading {
        guard case .editor(let reading) = SharedText.route(text) else { fatalError("not the editor's: \(text)") }
        return reading
    }

    func testARecipeIsLeftForTheAppsEditorAsItsReading() async {
        let pending = pendingText()
        let repository = ReconnectCountingRepository(.success(recipe()))
        let vm = viewModel(repository, pending: pending)

        vm.start(with: nil, text: recipeText)
        await settleMain()

        XCTAssertEqual(vm.uiState, .textInApp)
        let left = pending.take(now: 5_000 + PendingText.window)
        XCTAssertEqual(left, reading(recipeText))
        XCTAssertEqual(left?.recipe.ingredients, ["2 cups flour", "1 tsp salt"])
    }

    func testARecipeWinsOverALinkInItAndSoDoesProse() async {
        let pending = pendingText()
        let repository = ReconnectCountingRepository(.success(recipe()))
        let vm = viewModel(repository, pending: pending)
        let text = recipeText + "\nFrom https://example.com/scones"

        vm.start(with: SharedInput(url: "https://example.com/scones"), text: text)
        await settleMain()

        XCTAssertEqual(vm.uiState, .textInApp)
        XCTAssertEqual(repository.importCalls, 0)
        XCTAssertEqual(pending.take(now: 5_000)?.leftovers, ["From https://example.com/scones"])

        // A prose recipe with a signature's link (#334): the text, not the link.
        let prose = "Hi Mum,\nMix the flour with the butter, then bake it.\nLove, Sam\nSent from https://example.com/x"
        let other = viewModel(repository, pending: pending)
        other.start(with: SharedInput(url: "https://example.com/x"), text: prose)
        await settleMain()
        XCTAssertEqual(other.uiState, .textInApp)
        XCTAssertEqual(repository.importCalls, 0)
        XCTAssertEqual(pending.take(now: 5_000)?.sorted, false)
    }

    func testOtherTextIsLeftTooButALinkStillImports() async {
        let pending = pendingText()
        let vm = viewModel(ReconnectCountingRepository(.success(recipe())), pending: pending)
        vm.start(with: nil, text: "Hi Mum,\nMix the flour with the butter.")
        await settleMain()
        XCTAssertEqual(vm.uiState, .textInApp)

        let repository = ReconnectCountingRepository(.success(recipe()))
        let linked = viewModel(repository, pending: pending)
        linked.start(with: SharedInput(url: "https://example.com/scones"), text: "Look! https://example.com/scones")
        await linked.currentLoad?.value
        XCTAssertEqual(linked.uiState, .saved(title: "Scones"))
        XCTAssertNil(pending.take(now: 5_000), "a new share clears the text left before")
    }

    func testBlankTextIsIgnoredAndAMixedListIsNotAList() async {
        let pending = pendingText()
        var lists = 0
        let vm = viewModel(ReconnectCountingRepository(.success(recipe())), pending: pending, makeReceiveList: { lists += 1; return nil })
        vm.start(with: nil, text: "   \n \u{2028} ")
        await settleMain()
        XCTAssertEqual(vm.uiState, .noLink)
        XCTAssertNil(pending.take(now: 5_000))
        XCTAssertEqual(lists, 0)

        let mixed = viewModel(ReconnectCountingRepository(.success(recipe())), pending: pending, makeReceiveList: { lists += 1; return nil })
        mixed.start(with: nil, text: "Here's grandma's scones…\n- 2 cups flour\n- 1 tsp salt\nMix everything and bake 20 minutes.")
        await settleMain()
        XCTAssertEqual(mixed.uiState, .textInApp)
        XCTAssertEqual(lists, 0)
    }

    func testBesidePicturesOnlyARecipeWins() async {
        let pending = pendingText()
        let scanDefaults = UserDefaults(suiteName: "ShareImportPendingScan-\(UUID().uuidString)")!
        let pages = ScanPages(directory: FileManager.default.temporaryDirectory.appendingPathComponent("TextHandOff-\(UUID().uuidString)"))
        let scan = PendingScan(defaults: scanDefaults, pages: pages)

        let vm = viewModel(ReconnectCountingRepository(.success(recipe())), pending: pending, pendingScan: scan)
        vm.start(with: nil, text: recipeText, scanPages: ["page-0.jpg"], withPictures: true)
        await settleMain()
        XCTAssertEqual(vm.uiState, .textInApp)
        XCTAssertNotNil(pending.take(now: 5_000))

        let caption = viewModel(ReconnectCountingRepository(.success(recipe())), pending: pending, pendingScan: scan)
        caption.start(with: nil, text: "Dinner last night", scanPages: ["page-0.jpg"], withPictures: true)
        await settleMain()
        XCTAssertEqual(caption.uiState, .scanInApp)
        XCTAssertNil(pending.take(now: 5_000))

        // The photoText flag off: no pages staged, and a caption is nothing to open.
        let noScan = viewModel(ReconnectCountingRepository(.success(recipe())), pending: pending)
        noScan.start(with: nil, text: "Dinner last night", withPictures: true)
        await settleMain()
        XCTAssertEqual(noScan.uiState, .noLink)
        XCTAssertNil(pending.take(now: 5_000))
    }

    func testWithTheFlagOffTheLinkImportsAndNothingIsLeft() async {
        let pending = pendingText()
        let repository = ReconnectCountingRepository(.success(recipe()))
        let vm = viewModel(repository, pending: pending, on: false)

        vm.start(with: SharedInput(url: "https://example.com/scones"), text: recipeText + "\nhttps://example.com/scones")
        await vm.currentLoad?.value

        XCTAssertEqual(vm.uiState, .saved(title: "Scones"))
        XCTAssertNil(pending.take(now: 5_000))
    }

    func testTheReadingLeftOpensOnceAndOnlyWithinTheWindow() {
        let pending = pendingText()
        let left = reading(recipeText)
        pending.put(left, at: 1_000)
        XCTAssertEqual(pending.take(now: 1_000 + PendingText.window), left)
        XCTAssertNil(pending.take(now: 1_000 + PendingText.window), "taken once")

        pending.put(left, at: 1_000)
        XCTAssertNil(pending.take(now: 1_001 + PendingText.window), "too old")
    }

    func testSharedTextSortsAsOnAndroid() {
        XCTAssertEqual(SharedText.kind(recipeText + "\nhttps://example.com"), .recipe)
        XCTAssertEqual(SharedText.kind("Look! https://example.com/scones"), .link)
        XCTAssertEqual(SharedText.kind("Groceries\n- milk\n- eggs"), .list)
        XCTAssertEqual(SharedText.kind("milk\neggs"), .text)
        XCTAssertEqual(SharedText.kind("  "), .ignored)
        let unsorted = SharedText.read("Hi Mum,\n  Mix it.  \n\nLove")
        XCTAssertFalse(unsorted.sorted)
        XCTAssertEqual(unsorted.recipe.ingredients, [])
        XCTAssertEqual(unsorted.recipe.instructions, ["Hi Mum,", "Mix it.", "Love"])
    }
}
