import Combine
import Foundation
@testable import RecipeClipper

/// A hand-written fake, not a mock. History and recent are in-memory subjects a test can push
/// onto; `importFromUrl` and `open` return whatever the test stages; `setChecked`, `setNotes`,
/// `delete` and `restore` record every call.
final class FakeRecipeRepository: RecipeRepository {
    /// What `observeHistory` emits, regardless of the query passed.
    let history = CurrentValueSubject<[RecipeSummary], Never>([])
    /// What `observeRecent` emits (capped at the limit asked for).
    let recent = CurrentValueSubject<[RecipeSummary], Never>([])

    /// Every query `observeHistory` was called with, in order — for the debounce test.
    private(set) var historyQueries: [String] = []

    var importResult: ParseResult = .error(.nothingToShow)
    var openResult: Recipe?
    /// Staged answer for `saveClip`; nil answers success with the recipe given id 1.
    var saveClipResult: ParseResult?
    private(set) var saveClipCalls: [Recipe] = []
    /// Per-id answers for `delete`; missing ids answer nil, same as "already gone".
    var deleteResults: [Int64: DeletedRecipe] = [:]

    private(set) var setCheckedCalls: [(id: Int64, checked: Set<Int>)] = []
    private(set) var setNotesCalls: [(id: Int64, notes: String)] = []
    private(set) var deleteCalls: [Int64] = []
    private(set) var restoreCalls: [DeletedRecipe] = []

    @MainActor func importFromUrl(_ sharedUrl: String) async -> ParseResult { importResult }

    @MainActor func saveClip(_ recipe: Recipe) async -> ParseResult {
        saveClipCalls.append(recipe)
        if let saveClipResult { return saveClipResult }
        var saved = recipe
        saved.id = 1
        return .success(saved)
    }

    @MainActor func open(id: Int64) async -> Recipe? { openResult }

    @MainActor func setChecked(id: Int64, checked: Set<Int>) async {
        setCheckedCalls.append((id, checked))
    }

    @MainActor func setNotes(id: Int64, notes: String) async {
        setNotesCalls.append((id, notes))
    }

    @MainActor func delete(id: Int64) async -> DeletedRecipe? {
        deleteCalls.append(id)
        return deleteResults[id]
    }

    @MainActor func restore(_ deleted: DeletedRecipe) async {
        restoreCalls.append(deleted)
    }

    func observeHistory(query: String) -> AnyPublisher<[RecipeSummary], Never> {
        historyQueries.append(query)
        return history.eraseToAnyPublisher()
    }

    /// Honours `limit`, like the SQL LIMIT it stands in for, so a test can't "prove" a cap
    /// that isn't there.
    func observeRecent(limit: Int) -> AnyPublisher<[RecipeSummary], Never> {
        recent.map { Array($0.prefix(limit)) }.eraseToAnyPublisher()
    }
}
