import Combine
import Foundation
import Observation

/// `creatingList` is the "+ New list" field expanded inline — never a dialog on the sheet.
struct SaveToListUiState: Equatable {
    var lists: [RecipeList] = []
    var creatingList = false
    var newListName = ""

    /// What the bookmark icon renders from: filled once the recipe is in at least one list.
    var isSaved: Bool { lists.contains { $0.containsRecipe } }
}

/// Backs both the save-to-list sheet and the recipe screen's bookmark icon, which is why the
/// recipe screen holds one whether or not the sheet is open. The recipe id arrives through
/// `setRecipe` because on the import route there is no id until the parse finishes.
@MainActor
@Observable
final class SaveToListViewModel {
    private(set) var uiState = SaveToListUiState()

    @ObservationIgnored private let repository: ListRepository
    @ObservationIgnored private var recipeId: Int64?
    @ObservationIgnored private var listsSubscription: AnyCancellable?

    init(repository: ListRepository) {
        self.repository = repository
    }

    /// Called once the recipe screen knows which recipe it is showing. Switches to that
    /// recipe's lists, dropping any earlier subscription (flatMapLatest).
    func setRecipe(_ id: Int64) {
        guard id != recipeId else { return }
        recipeId = id
        listsSubscription = repository.observeListsFor(recipeId: id)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] lists in self?.uiState.lists = lists }
    }

    /// Writes straight through: the sheet is dismissed, not submitted.
    func onListToggled(listId: Int64, inList: Bool) {
        guard let recipe = recipeId else { return }
        Task { await repository.setMembership(recipeId: recipe, listId: listId, inList: inList) }
    }

    func onStartCreating() { uiState.creatingList = true }

    func onCancelCreating() {
        uiState.creatingList = false
        uiState.newListName = ""
    }

    func onNewListNameChange(_ value: String) { uiState.newListName = value }

    /// Creating a list from the sheet puts the current recipe in it; a blank name is ignored.
    func onCreateList() {
        let name = uiState.newListName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        let recipe = recipeId
        Task {
            await repository.createList(name: name, addRecipeId: recipe)
            uiState.creatingList = false
            uiState.newListName = ""
        }
    }
}
