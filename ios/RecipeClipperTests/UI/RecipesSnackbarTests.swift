import XCTest
@testable import RecipeClipper

/// The snackbar's timeout, as the History screen's `.task(id: pendingDeletes)` runs it, and
/// what happens to captures when History goes away with the snackbar still up.
@MainActor
final class RecipesSnackbarTests: XCTestCase {

    private func deleted(_ id: Int64) -> DeletedRecipe {
        DeletedRecipe(
            recipe: Recipe(
                name: "R\(id)", image: nil, ingredients: ["1 egg"], instructions: ["Cook."],
                prepTime: nil, cookTime: nil, totalTime: nil, yield: nil,
                sourceUrl: "https://example.com/\(id)", id: id
            ),
            memberships: []
        )
    }

    func testTimingOutSettlesTheBatch() async {
        var settled = 0
        await SnackbarTimeout.run(pending: ["A"], sleep: { _ in }, onTimeout: { settled += 1 })
        XCTAssertEqual(settled, 1)
    }

    func testNothingPendingNeverSettles() async {
        var settled = 0
        await SnackbarTimeout.run(pending: [], sleep: { _ in }, onTimeout: { settled += 1 })
        XCTAssertEqual(settled, 0)
    }

    /// A second swipe restarts the task (and leaving the screen cancels it): neither Undo nor
    /// dismissal may run for the cancelled one, or the earlier capture would be dropped.
    func testACancelledTimeoutRunsNeitherOutcome() async {
        var settled = 0
        let task = Task { @MainActor in
            await SnackbarTimeout.run(pending: ["A"], sleep: { try await Task.sleep(for: $0) }, onTimeout: { settled += 1 })
        }
        await Task.yield()
        task.cancel()
        await task.value
        XCTAssertEqual(settled, 0)
    }

    /// Cancelled after the wait but before the settle (the restart landed in between): still
    /// neither outcome.
    func testCancellationThatArrivesAsTheWaitEndsStillSettlesNothing() async {
        var settled = 0
        let task = Task { @MainActor in
            await SnackbarTimeout.run(pending: ["A"], sleep: { _ in withUnsafeCurrentTask { $0?.cancel() } }, onTimeout: { settled += 1 })
        }
        await task.value
        XCTAssertEqual(settled, 0)
    }

    /// The restart path end to end: first swipe, its timeout cancelled by a second swipe, then
    /// Undo restores both.
    func testARestartedSnackbarKeepsTheEarlierCaptureForUndo() async {
        let repository = FakeRecipeRepository()
        repository.deleteResults = [1: deleted(1), 2: deleted(2)]
        let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
        await settleMain()

        vm.onDelete(testSummary(1, title: "A"))
        await settleMain()
        let first = Task { @MainActor in
            await SnackbarTimeout.run(pending: vm.uiState.pendingDeletes, sleep: { try await Task.sleep(for: $0) },
                                      onTimeout: vm.onSnackbarDismissed)
        }
        vm.onDelete(testSummary(2, title: "B"))
        await settleMain()
        first.cancel()          // what `.task(id:)` does when pendingDeletes changes
        await first.value

        XCTAssertEqual(vm.uiState.pendingDeletes, ["A", "B"])
        vm.onUndoDelete()
        await settleMain()
        XCTAssertEqual(repository.restoreCalls, [deleted(1), deleted(2)])
    }

    /// Popping History with the snackbar up: Android's deletes stand. The ViewModel goes with
    /// the screen, and nothing it captured is ever restored later.
    func testLeavingHistoryWithDeletesPendingNeverRestoresThem() async {
        let repository = FakeRecipeRepository()
        repository.deleteResults = [1: deleted(1)]
        weak var released: RecipesViewModel?
        do {
            let vm = RecipesViewModel(repository: repository, preferences: FakeAppPreferences(), sleep: immediateSleep)
            released = vm
            await settleMain()
            vm.onDelete(testSummary(1, title: "A"))
            await settleMain()
            XCTAssertEqual(vm.uiState.pendingDeletes, ["A"])
        }
        await settleMain()

        XCTAssertNil(released, "the popped screen's ViewModel (and its captures) should be gone")
        XCTAssertEqual(repository.deleteCalls, [1])
        XCTAssertTrue(repository.restoreCalls.isEmpty)
    }
}
