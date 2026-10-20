import Combine
import Foundation
import Observation

/// `loaded` is false until the database has answered once: the built-in lists always exist,
/// so a momentary empty list would be a lie rather than a state.
///
/// `coverPhotos` is each list's cover (#286): up to four photo URLs, most recently added
/// first. A list with none is absent and gets a plain tile.
struct ListsUiState: Equatable {
    var loaded = false
    var lists: [RecipeList] = []
    var coverPhotos: [Int64: [String]] = [:]
    var creatingList = false
    var newListName = ""
}

/// Every list as a cover with its count, and creating a new one. Renaming and deleting live on the
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
        repository.observeCoverPhotos()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] photos in
                self?.uiState.coverPhotos = photos.mapValues { Array($0.prefix(ListDao.coverPhotoLimit)) }
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
