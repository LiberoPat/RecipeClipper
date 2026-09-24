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
}

@MainActor
@Observable
final class ListDetailViewModel {
    private(set) var uiState = ListDetailUiState()

    @ObservationIgnored let listId: Int64
    @ObservationIgnored private let repository: ListRepository
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()

    /// The list itself comes from `observeLists()` rather than its own query (the count comes
    /// along for free). It is collected here, independently of any view observing, so
    /// `onDelete` always sees the current list.
    init(listId: Int64, repository: ListRepository) {
        self.listId = listId
        self.repository = repository
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
}
