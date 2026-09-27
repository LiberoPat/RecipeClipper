import Combine
import Foundation
import Observation

/// `renaming` and `deleting` are the meal type whose rename field or delete confirmation is
/// open. `creating` is the "+ New meal type" field, expanded inline as on Lists.
struct MealTypesUiState: Equatable {
    var types: [MealType] = []
    var creating = false
    var newName = ""
    var renaming: MealType?
    var renameText = ""
    var deleting: MealType?
}

/// The Week menu's "Meal types" screen (#49; Android's MealTypesViewModel): add, rename and
/// reorder any, delete the user's own (their meals move to Dinner).
@MainActor
@Observable
final class MealTypesViewModel {
    private(set) var uiState = MealTypesUiState()

    @ObservationIgnored private let repository: MealPlanRepository
    @ObservationIgnored private var typesSubscription: AnyCancellable?
    /// Writes run one after another, so two quick moves reach the database in the order made.
    @ObservationIgnored private var lastWrite: Task<Void, Never>?

    init(repository: MealPlanRepository) {
        self.repository = repository
        typesSubscription = repository.observeMealTypes()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] types in self?.uiState.types = types }
    }

    func onStartCreating() { uiState.creating = true }

    func onNewNameChange(_ value: String) { uiState.newName = value }

    func onCancelCreating() {
        uiState.creating = false
        uiState.newName = ""
    }

    func onCreate() {
        let name = uiState.newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        onCancelCreating()
        write { await $0.addMealType(name: name) }
    }

    func onRenameStart(_ type: MealType) {
        uiState.renaming = type
        uiState.renameText = type.name
    }

    func onRenameTextChange(_ value: String) { uiState.renameText = value }

    func onRenameConfirm() {
        guard let type = uiState.renaming else { return }
        let name = uiState.renameText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        onRenameDismissed()
        write { await $0.renameMealType(id: type.id, name: name) }
    }

    func onRenameDismissed() {
        uiState.renaming = nil
        uiState.renameText = ""
    }

    func onMoveUp(_ type: MealType) { move(type, by: -1) }

    func onMoveDown(_ type: MealType) { move(type, by: 1) }

    private func move(_ type: MealType, by offset: Int) {
        var order = uiState.types
        guard let from = order.firstIndex(where: { $0.id == type.id }) else { return }
        let to = from + offset
        guard order.indices.contains(to) else { return }
        order.insert(order.remove(at: from), at: to)
        // Shown at once; the database's echo agrees.
        uiState.types = order
        let ids = order.map(\.id)
        write { await $0.reorderMealTypes(ids) }
    }

    /// Only the user's own types offer Delete; a seeded one is ignored here too.
    func onDeleteStart(_ type: MealType) {
        guard !type.isBuiltIn else { return }
        uiState.deleting = type
    }

    func onDeleteConfirm() {
        guard let type = uiState.deleting else { return }
        uiState.deleting = nil
        write { await $0.deleteMealType(id: type.id) }
    }

    func onDeleteDismissed() { uiState.deleting = nil }

    private func write(_ body: @escaping (MealPlanRepository) async -> Void) {
        let previous = lastWrite
        lastWrite = Task { [repository] in
            await previous?.value
            await body(repository)
        }
    }

    /// Waits for every queued write. For tests.
    func settleWrites() async { await lastWrite?.value }
}
