import Combine
import Foundation
@testable import RecipeClipper

/// A hand-written fake, not a mock. History and recent are in-memory subjects a test can push
/// onto; `importFromUrl` and `open` return whatever the test stages; `setChecked`, `setNotes`,
/// `setCookProgress`, `setServingsTarget`, `delete` and `restore` record every call.
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
    private(set) var setCookProgressCalls: [(id: Int64, progress: CookProgress)] = []
    private(set) var setServingsTargetCalls: [(id: Int64, target: Int?)] = []
    private(set) var deleteCalls: [Int64] = []
    private(set) var restoreCalls: [DeletedRecipe] = []

    @MainActor func importFromUrl(_ sharedUrl: String, renderedPage: String?) async -> ParseResult { importResult }

    @MainActor func saveClip(_ recipe: Recipe) async -> ParseResult {
        saveClipCalls.append(recipe)
        if let saveClipResult { return saveClipResult }
        var saved = recipe
        saved.id = 1
        return .success(saved)
    }

    @MainActor func open(id: Int64) async -> Recipe? { openResult }

    /// Staged answer for `keep` (#107); nil answers success with the recipe given id 1.
    var keepResult: ParseResult?
    private(set) var keepCalls: [Recipe] = []

    @MainActor func keep(_ recipe: Recipe) async -> ParseResult {
        keepCalls.append(recipe)
        if let keepResult { return keepResult }
        var saved = recipe
        saved.id = 1
        return .success(saved)
    }

    /// Staged answers for "Update from source", edits and new recipes, and every call made.
    var updateFromSourceResult: ParseResult = .error(.nothingToShow)
    private(set) var updateFromSourceCalls: [Int64] = []
    var saveEditResult: Recipe?
    private(set) var saveEditCalls: [(id: Int64, draft: RecipeDraft)] = []
    var addManualResult: Recipe?
    private(set) var addManualCalls: [RecipeDraft] = []

    @MainActor func updateFromSource(id: Int64) async -> ParseResult {
        updateFromSourceCalls.append(id)
        return updateFromSourceResult
    }

    @MainActor func saveEdit(id: Int64, draft: RecipeDraft) async -> Recipe? {
        saveEditCalls.append((id, draft))
        return saveEditResult
    }

    @MainActor func addManual(draft: RecipeDraft) async -> Recipe? {
        addManualCalls.append(draft)
        return addManualResult
    }

    @MainActor func setChecked(id: Int64, checked: Set<Int>) async {
        setCheckedCalls.append((id, checked))
    }

    @MainActor func setNotes(id: Int64, notes: String) async {
        setNotesCalls.append((id, notes))
    }

    @MainActor func setCookProgress(id: Int64, progress: CookProgress) async {
        setCookProgressCalls.append((id, progress))
    }

    @MainActor func setServingsTarget(id: Int64, target: Int?) async {
        setServingsTargetCalls.append((id, target))
    }

    @MainActor func delete(id: Int64) async -> DeletedRecipe? {
        deleteCalls.append(id)
        return deleteResults[id]
    }

    @MainActor func restore(_ deleted: DeletedRecipe) async {
        restoreCalls.append(deleted)
    }

    /// Deletes that stood (#116: their photo files go).
    private(set) var forgetCalls: [DeletedRecipe] = []

    @MainActor func forget(_ deleted: DeletedRecipe) async {
        forgetCalls.append(deleted)
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
