import XCTest
@testable import RecipeClipper

/// Text shared in (#306): the share extension leaves a recipe, or text that is neither a link
/// nor a list, for the app's editor (`PendingText`), behind the shareText flag.
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
        _ repository: ReconnectCountingRepository, pending: PendingText, on: Bool = true
    ) -> ShareImportViewModel {
        ShareImportViewModel(
            repository: repository, shareTextOn: { on }, pendingText: pending, clock: DataTestClock(5_000)
        )
    }

    func testARecipeIsLeftForTheAppsEditor() async {
        let pending = pendingText()
        let repository = ReconnectCountingRepository(.success(recipe()))
        let vm = viewModel(repository, pending: pending)

        vm.start(with: nil, text: recipeText)
        await settleMain()

        XCTAssertEqual(vm.uiState, .textInApp)
        XCTAssertEqual(pending.take(now: 5_000 + PendingText.window), recipeText)
    }

    func testARecipeWinsOverALinkInIt() async {
        let pending = pendingText()
        let repository = ReconnectCountingRepository(.success(recipe()))
        let vm = viewModel(repository, pending: pending)
        let text = recipeText + "\nFrom https://example.com/scones"

        vm.start(with: SharedInput(url: "https://example.com/scones"), text: text)
        await settleMain()

        XCTAssertEqual(vm.uiState, .textInApp)
        XCTAssertEqual(repository.importCalls, 0)
        XCTAssertEqual(pending.take(now: 5_000), text)
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

    func testWithTheFlagOffTheLinkImportsAndNothingIsLeft() async {
        let pending = pendingText()
        let repository = ReconnectCountingRepository(.success(recipe()))
        let vm = viewModel(repository, pending: pending, on: false)

        vm.start(with: SharedInput(url: "https://example.com/scones"), text: recipeText + "\nhttps://example.com/scones")
        await vm.currentLoad?.value

        XCTAssertEqual(vm.uiState, .saved(title: "Scones"))
        XCTAssertNil(pending.take(now: 5_000))
    }

    func testTheTextLeftOpensOnceAndOnlyWithinTheWindow() {
        let pending = pendingText()
        pending.put("text", at: 1_000)
        XCTAssertEqual(pending.take(now: 1_000 + PendingText.window), "text")
        XCTAssertNil(pending.take(now: 1_000 + PendingText.window), "taken once")

        pending.put("text", at: 1_000)
        XCTAssertNil(pending.take(now: 1_001 + PendingText.window), "too old")
    }

    func testSharedTextSortsAsOnAndroid() {
        XCTAssertEqual(SharedText.kind(recipeText + "\nhttps://example.com"), .recipe)
        XCTAssertEqual(SharedText.kind("Look! https://example.com/scones"), .link)
        XCTAssertEqual(SharedText.kind("Groceries\n- milk\n- eggs"), .list)
        XCTAssertEqual(SharedText.kind("milk\neggs"), .text)
        let unsorted = SharedText.read("Hi Mum,\n  Mix it.  \n\nLove")
        XCTAssertFalse(unsorted.sorted)
        XCTAssertEqual(unsorted.recipe.ingredients, [])
        XCTAssertEqual(unsorted.recipe.instructions, ["Hi Mum,", "Mix it.", "Love"])
    }
}
