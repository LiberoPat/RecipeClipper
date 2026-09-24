import Combine
import Foundation
import Observation

/// `loaded` is false until the database has answered once: the built-in lists always exist,
/// so a momentary empty list would be a lie rather than a state.
struct ListsUiState: Equatable {
    var loaded = false
    var lists: [RecipeList] = []
    var creatingList = false
    var newListName = ""
}

/// Every list with its count, and creating a new one. Renaming and deleting live on the
/// list's own screen, so there is one place a list is managed from.
@MainActor
@Observable
final class ListsViewModel {
    private(set) var uiState = ListsUiState()
    @ObservationIgnored private let repository: ListRepository
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()

    init(repository: ListRepository) {
        self.repository = repository
        repository.observeLists()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] lists in
                self?.uiState.loaded = true
                self?.uiState.lists = lists
            }
            .store(in: &cancellables)
    }

    func onStartCreating() { uiState.creatingList = true }

    func onCancelCreating() {
        uiState.creatingList = false
        uiState.newListName = ""
    }

    func onNewListNameChange(_ value: String) { uiState.newListName = value }

    /// No recipe in hand here, so the new list starts empty. A blank name is ignored.
    func onCreateList() {
        let name = uiState.newListName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        Task {
            await repository.createList(name: name, addRecipeId: nil)
            uiState.creatingList = false
            uiState.newListName = ""
        }
    }
}
