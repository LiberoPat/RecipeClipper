import Combine
import Foundation
import Observation

/// `list` is nil while loading and again once the list has been deleted, so `loaded` is what
/// tells an empty list apart from one that hasn't arrived yet. `deleted` is set by the delete
/// action itself, so the screen navigates back exactly once and only for what the user did.
struct ListDetailUiState: Equatable {
    var loaded = false
    var list: RecipeList?
    var recipes: [RecipeSummary] = []
    var renaming = false
    var renameValue = ""
    var deleted = false
    /// The deletes not yet settled, for the one Undo snackbar (#268, #377): as Recipes' (`RecipeDeletions`).
    var pendingDeletes: [String] = []
    var deleteBatch: Int?
    var restoreRefused = false
    /// Selecting several of the list's recipes (#377): whether the screen is, and the ticked ones
    /// that are listed now.
    var selecting = false
    var selectedIds: Set<Int64> = []
    /// The recipes a Delete (a row's menu, or the selection's) asks to confirm; empty when none.
    var confirmingDelete: [RecipeSummary] = []
}

@MainActor
@Observable
final class ListDetailViewModel {
    private(set) var uiState = ListDetailUiState()

    @ObservationIgnored let listId: Int64
    @ObservationIgnored private let repository: ListRepository
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()
    // Deleting and sharing a row's recipe (#376, #377); nil without a recipe repository (tests that
    // aren't about them).
    @ObservationIgnored private let deletions: RecipeDeletions?
    @ObservationIgnored private let rowSupport: RecipeRowSupport?
    @ObservationIgnored private var selection = RecipeSelection()

    /// The list itself comes from `observeLists()` rather than its own query (the count comes
    /// along for free). It is collected here, independently of any view observing, so
    /// `onDelete` always sees the current list.
    init(
        listId: Int64, repository: ListRepository, recipes: RecipeRepository? = nil,
        preferences: AppPreferences? = nil, decisions: DecisionRepository? = nil,
        countdowns: TimerCountdownSync? = nil
    ) {
        self.listId = listId
        self.repository = repository
        deletions = recipes.map { RecipeDeletions(repository: $0, countdowns: countdowns) }
        rowSupport = recipes.flatMap { recipes in
            preferences.map { RecipeRowSupport(repository: recipes, preferences: $0, decisions: decisions) }
        }
        deletions?.onChange = { [weak self] in self?.syncDeletions() }
        repository.observeLists()
            .map { lists in lists.first { $0.id == listId } }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] list in self?.uiState.list = list }
            .store(in: &cancellables)
        repository.observeRecipesIn(listId: listId)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] recipes in
                self?.uiState.loaded = true
                self?.uiState.recipes = recipes
                self?.syncSelection()
            }
            .store(in: &cancellables)
    }

    /// Seeds the field with the current name, so renaming is an edit rather than a retype.
    func onStartRenaming() {
        uiState.renameValue = uiState.list?.name ?? ""
        uiState.renaming = true
    }

    func onCancelRenaming() {
        uiState.renaming = false
        uiState.renameValue = ""
    }

    /// The rename alert went away by itself (SwiftUI dismisses an alert on any button). Only
    /// the flag is cleared: whether SwiftUI runs Save's action before or after it resets the
    /// binding is not something to depend on, and clearing the value here would make a Save
    /// that runs second read an empty name and silently rename nothing. `onStartRenaming`
    /// reseeds the value next time anyway.
    func onRenameDismissed() { uiState.renaming = false }

    func onRenameValueChange(_ value: String) { uiState.renameValue = value }

    /// Built-ins are renameable; only deleting them is refused. A blank name is ignored.
    func onRenameConfirm() {
        let name = uiState.renameValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        Task {
            await repository.rename(listId: listId, name: name)
            uiState.renaming = false
            uiState.renameValue = ""
        }
    }

    /// Whether the overflow offers "Delete list": every list but Favorites.
    var canDelete: Bool { uiState.list.map { !$0.isFavorites } ?? false }

    /// Deletes the list, never the recipes in it. Refused for Favorites (and while the list
    /// hasn't resolved); every other list, seeded or not, can go.
    func onDelete() {
        guard let list = uiState.list, !list.isFavorites else { return }
        Task {
            await repository.deleteList(listId: list.id)
            uiState.deleted = true
        }
    }

    // MARK: Deleting, a row's menu and selecting several (#376, #377)

    private func syncDeletions() {
        guard let state = deletions?.state else { return }
        uiState.pendingDeletes = state.pendingDeletes
        uiState.deleteBatch = state.deleteBatch
        uiState.restoreRefused = state.restoreRefused
    }

    private func syncSelection() {
        uiState.selecting = selection.isSelecting
        uiState.selectedIds = selection.effective(in: uiState.recipes)
    }

    /// The top bar's Select shows once there is a row to select.
    var canSelect: Bool { !uiState.recipes.isEmpty }

    /// The selected recipes, in the order listed: what the selection's actions take.
    var selectedRecipes: [RecipeSummary] {
        uiState.recipes.filter { uiState.selectedIds.contains($0.id) }
    }

    /// Starts selecting: a row's menu begins with that row ticked, the overflow's Select with none.
    func onStartSelecting(_ recipe: RecipeSummary? = nil) {
        selection.start(recipe?.id)
        syncSelection()
    }

    func onToggleSelected(_ id: Int64) {
        selection.toggle(id)
        syncSelection()
    }

    /// Done, or Back: leaves selecting, dropping the ticks.
    func onExitSelection() {
        selection.exit()
        syncSelection()
    }

    /// "Remove from this list": leaving a list is not a deletion, so no confirmation and no
    /// snackbar. The recipes stay in Recipes (under Not in a list when this was their last).
    func onRemoveFromList(ids: [Int64]) {
        selection.exit()
        syncSelection()
        guard !ids.isEmpty else { return }
        Task { await repository.setMembershipAll(recipeIds: ids, listId: listId, inList: false) }
    }

    /// Asks to confirm deleting `recipes` from the library, not just this list.
    func onRequestDelete(_ recipes: [RecipeSummary]) {
        uiState.confirmingDelete = recipes
    }

    func onCancelDelete() { uiState.confirmingDelete = [] }

    /// The confirmation's Delete: every recipe in one transaction, one batch, one Undo; selecting ends.
    func onConfirmDelete() {
        let recipes = uiState.confirmingDelete
        uiState.confirmingDelete = []
        selection.exit()
        syncSelection()
        guard let deletions else { return }
        Task { await deletions.delete(recipes) }
    }

    func onUndoDelete() { deletions?.undo() }

    func onRestoreRefusedShown() { deletions?.restoreRefusedShown() }

    /// The snackbar timed out: the deletes stand.
    func onSnackbarDismissed() { deletions?.settle() }

    /// A row's Share: the recipe as the reading view would send it. Nil when the recipe is gone.
    func onShare(recipeId: Int64, labels: RecipeShareText.Labels = .english) async -> (title: String, text: String)? {
        await rowSupport?.shareText(id: recipeId, labels: labels)
    }

    /// A row's Add to plan starts from the recipe's own yield (nil when it has no number).
    func yieldServings(recipeId: Int64) async -> Int? {
        await rowSupport?.yieldServings(id: recipeId)
    }
}
