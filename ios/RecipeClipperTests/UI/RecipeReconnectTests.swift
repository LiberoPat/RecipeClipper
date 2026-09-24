import Combine
import XCTest
@testable import RecipeClipper

/// Connectivity a test flips by hand. Each stream starts with the current state, as
/// PathConnectivity's does, and gets every later change.
@MainActor
final class ReconnectFakeConnectivity: Connectivity {
    private(set) var online: Bool
    private var continuations: [UUID: AsyncStream<Bool>.Continuation] = [:]

    init(online: Bool) { self.online = online }

    nonisolated func onlineUpdates() -> AsyncStream<Bool> {
        AsyncStream { continuation in
            Task { @MainActor in
                let id = UUID()
                self.continuations[id] = continuation
                continuation.yield(self.online)
                continuation.onTermination = { _ in Task { @MainActor in self.continuations[id] = nil } }
            }
        }
    }

    func set(_ value: Bool) {
        guard value != online else { return }
        online = value
        for continuation in continuations.values { continuation.yield(value) }
    }
}

/// Answers every import with `importResult` and counts the calls. Local to these tests so the
/// shared fakes stay as they are.
@MainActor
final class ReconnectCountingRepository: RecipeRepository {
    var importResult: ParseResult
    private(set) var importCalls = 0

    init(_ result: ParseResult) { importResult = result }

    func importFromUrl(_ sharedUrl: String) async -> ParseResult {
        importCalls += 1
        return importResult
    }
    func saveClip(_ recipe: Recipe) async -> ParseResult { .error(.saveFailed) }
    func open(id: Int64) async -> Recipe? { nil }
    func setChecked(id: Int64, checked: Set<Int>) async {}
    func setNotes(id: Int64, notes: String) async {}
    func delete(id: Int64) async -> DeletedRecipe? { nil }
    func restore(_ deleted: DeletedRecipe) async {}
    nonisolated func observeHistory(query: String) -> AnyPublisher<[RecipeSummary], Never> {
        Just([]).eraseToAnyPublisher()
    }
    nonisolated func observeRecent(limit: Int) -> AnyPublisher<[RecipeSummary], Never> {
        Just([]).eraseToAnyPublisher()
    }
}

/// The recipe screen reloads once when the connection comes back while it shows an offline or
/// fetch-failed error — only on a real offline -> online transition.
@MainActor
final class RecipeReconnectTests: XCTestCase {

    private let recipe = Recipe(
        name: "Soup", image: nil, ingredients: ["1 onion"], instructions: ["Cook."],
        prepTime: nil, cookTime: nil, totalTime: nil, yield: "4", sourceUrl: "https://example.com/soup", id: 1
    )

    private func viewModel(_ repository: RecipeRepository, _ connectivity: Connectivity) -> RecipeViewModel {
        let clock = TestClock()
        return RecipeViewModel(
            recipeId: nil, url: "https://example.com/soup", repository: repository,
            preferences: FakeAppPreferences(), clock: clock, sleep: clock.sleep, connectivity: connectivity
        )
    }

    func testOfflineThenBackOnlineReloadsExactlyOnce() async {
        let connectivity = ReconnectFakeConnectivity(online: false)
        let repository = ReconnectCountingRepository(.error(.offline))
        let vm = viewModel(repository, connectivity)
        await settleMain()
        XCTAssertEqual(repository.importCalls, 1)
        XCTAssertEqual(vm.uiState.content, .error(.offline))

        repository.importResult = .success(recipe)
        connectivity.set(true)
        await settleMain()
        XCTAssertEqual(repository.importCalls, 2)
        XCTAssertEqual(vm.uiState.content.success?.recipe.name, "Soup")

        // Showing a recipe now: later drops and returns change nothing.
        connectivity.set(false)
        await settleMain()
        connectivity.set(true)
        await settleMain()
        XCTAssertEqual(repository.importCalls, 2)
    }

    // Each test holds its ViewModel and reads it last. One that is only created (`_ =`) is
    // freed at once, its load is cancelled before it shows the error, and so it never starts
    // watching: a "doesn't reload" test would then pass without testing anything.

    func testAFetchFailureWhileOnlineWaitsForARealDropAndReturn() async {
        let connectivity = ReconnectFakeConnectivity(online: true)
        let repository = ReconnectCountingRepository(.error(.fetchFailed("reset")))
        let vm = viewModel(repository, connectivity)
        await settleMain()
        XCTAssertEqual(vm.uiState.content, .error(.fetchFailed("reset")))
        XCTAssertEqual(repository.importCalls, 1, "already online is not a transition")

        connectivity.set(false)
        await settleMain()
        XCTAssertEqual(repository.importCalls, 1)
        repository.importResult = .success(recipe)
        connectivity.set(true)
        await settleMain()
        XCTAssertEqual(repository.importCalls, 2)
        XCTAssertEqual(vm.uiState.content.success?.recipe.name, "Soup")
    }

    func testErrorsReconnectingCantFixDontReload() async {
        for error in [ParseError.blocked(httpStatus: 403), .noRecipeFound] {
            let connectivity = ReconnectFakeConnectivity(online: false)
            let repository = ReconnectCountingRepository(.error(error))
            let vm = viewModel(repository, connectivity)
            await settleMain()
            XCTAssertEqual(vm.uiState.content, .error(error))
            connectivity.set(true)
            await settleMain()
            XCTAssertEqual(repository.importCalls, 1, "\(error)")
            XCTAssertEqual(vm.uiState.content, .error(error))
        }
    }

    func testTryAgainWhileOfflineLeavesOnlyOneWatcher() async {
        let connectivity = ReconnectFakeConnectivity(online: false)
        let repository = ReconnectCountingRepository(.error(.offline))
        let vm = viewModel(repository, connectivity)
        await settleMain()
        vm.onRetry()
        await settleMain()
        XCTAssertEqual(repository.importCalls, 2)

        repository.importResult = .success(recipe)
        connectivity.set(true)
        await settleMain()
        XCTAssertEqual(repository.importCalls, 3)
    }
}
