import Combine
import Foundation
import Observation

/// `loaded` is false until the database has answered once: the built-in lists always exist,
/// so a momentary empty list would be a lie rather than a state.
///
/// `coverPhotos` is each list's cover (#286): up to four photo URLs, most recently added
/// first. A list with none is absent and gets a plain tile.
///
/// `draggingId` is the list the cook is holding (#381); while it is set, `lists` is in the
/// order the drag has made so far and nothing is stored yet.
struct ListsUiState: Equatable {
    var loaded = false
    var lists: [RecipeList] = []
    var coverPhotos: [Int64: [CoverPhoto]] = [:]
    /// A list with no photo shows its recipes' designed images (#403); an empty one the bookmark tile.
    var coverPlaceholders: [Int64: [NoPhotoSource]] = [:]
    var creatingList = false
    var newListName = ""
    var draggingId: Int64?
}

/// Every list as a cover with its count, creating a new one, and putting the covers in the
/// cook's own order (#381). Renaming and deleting live on the list's own screen, so there is one
/// place a list is managed from.
@MainActor
@Observable
final class ListsViewModel {
    private(set) var uiState = ListsUiState()
    @ObservationIgnored private let repository: ListRepository
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()

    /// The stored order, and the order a drag in flight has made of it (nil: no drag).
    @ObservationIgnored private var stored: [RecipeList] = []
    @ObservationIgnored private var dragOrder: [Int64]?
    /// The drop is written but the database has not told us yet: the drag's order stays on
    /// show until it does, so the covers don't snap back to the old order and then forward again.
    @ObservationIgnored private var settling = false

    init(repository: ListRepository) {
        self.repository = repository
        repository.observeLists()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] lists in
                guard let self else { return }
                stored = lists
                uiState.loaded = true
                // The write the drop made has come back: the stored order is the one on show.
                if settling { settling = false; dragOrder = nil }
                showCurrentOrder()
            }
            .store(in: &cancellables)
        repository.observeCoverPhotos()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] photos in
                self?.uiState.coverPhotos = photos.mapValues { Array($0.prefix(ListDao.coverPhotoLimit)) }
            }
            .store(in: &cancellables)
        repository.observeCoverPlaceholders()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] placeholders in
                self?.uiState.coverPlaceholders = placeholders.mapValues { Array($0.prefix(ListDao.coverPhotoLimit)) }
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

    // MARK: - Reordering (#381)

    /// The cook lifts a cover. Nothing is stored until `onDragEnd`.
    func onDragStart(_ listId: Int64) {
        guard stored.contains(where: { $0.id == listId }) else { return }
        settling = false
        dragOrder = stored.map(\.id)
        uiState.draggingId = listId
        showCurrentOrder()
    }

    /// The held cover is over another's place: it takes that place, the ones between shift.
    func onDragOver(_ targetId: Int64) {
        guard let held = uiState.draggingId, var order = dragOrder,
              let from = order.firstIndex(of: held), let to = order.firstIndex(of: targetId),
              from != to else { return }
        order.insert(order.remove(at: from), at: to)
        dragOrder = order
        showCurrentOrder()
    }

    /// The cook let go over a cover (`targetId`) or the screen around the covers (nil). Over a
    /// cover, the held one takes its place first, whether or not the drag was reported entering
    /// it: on a slow device the lift can finish with the finger already there, and the system
    /// then never says "entered" (a nightly dropped Lunch on Favorites and nothing moved).
    func onDrop(on targetId: Int64?) {
        if let targetId { onDragOver(targetId) }
        onDragEnd()
    }

    /// The cook let go: the order the drag made is stored, once.
    func onDragEnd() {
        guard let order = dragOrder else { return }
        Task {
            if order != stored.map(\.id) { await repository.reorderLists(order) }
            uiState.draggingId = nil
            if stored.map(\.id) == order {
                dragOrder = nil
            } else {
                // The write is done, its publication is on its way. If it never comes (the
                // write failed) the stored order goes back on show after a moment.
                settling = true
                Task {
                    try? await Task.sleep(for: .seconds(1))
                    guard settling else { return }
                    settling = false
                    dragOrder = nil
                    showCurrentOrder()
                }
            }
            showCurrentOrder()
        }
    }

    /// The drag ended with no drop (the screen went away): the covers go back, nothing is stored.
    func onDragCancel() {
        guard dragOrder != nil else { return }
        settling = false
        dragOrder = nil
        uiState.draggingId = nil
        showCurrentOrder()
    }

    /// The accessible alternative to dragging: one place earlier, stored at once.
    func onMoveEarlier(_ listId: Int64) { move(listId, by: -1) }

    /// The accessible alternative to dragging: one place later, stored at once.
    func onMoveLater(_ listId: Int64) { move(listId, by: 1) }

    private func move(_ listId: Int64, by step: Int) {
        var order = stored.map(\.id)
        guard let from = order.firstIndex(of: listId), order.indices.contains(from + step) else { return }
        order.insert(order.remove(at: from), at: from + step)
        Task { await repository.reorderLists(order) }
    }

    /// `lists` as the stored order, or as the drag in flight has made it; a list the drag's
    /// order does not name (just created) goes last.
    private func showCurrentOrder() {
        guard let order = dragOrder else {
            uiState.lists = stored
            return
        }
        let byId = Dictionary(stored.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let named = Set(order)
        uiState.lists = order.compactMap { byId[$0] } + stored.filter { !named.contains($0.id) }
    }
}
