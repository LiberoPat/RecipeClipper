import Foundation

/// The deletes a screen has done and not yet settled, for its one Undo snackbar (#268, #345,
/// #377): shared by Recipes (a swipe, a row's Delete, a selection's) and a list's detail.
/// A burst shares one snackbar and one all-or-nothing Undo, whoever asked for it.
///
/// Plain state plus `onChange`, which the owning ViewModel uses to copy it into its `uiState`.
@MainActor
final class RecipeDeletions {
    struct State: Equatable {
        /// The titles deleted, newest last, for the snackbar's message.
        var pendingDeletes: [String] = []
        /// The burst they belong to, nil when none is pending: the snackbar's timeout is keyed on it.
        var deleteBatch: Int?
        /// An Undo was refused, the free library being full (#345): the screen says so once.
        var restoreRefused = false
    }

    private(set) var state = State() { didSet { if state != oldValue { onChange() } } }
    var onChange: () -> Void = {}

    private let repository: RecipeRepository
    /// The countdowns outside the app (#309), squared with the database after a delete (#333).
    private let countdowns: TimerCountdownSync?

    // What the deletes captured, keyed by recipe id and kept in order, so a second one within
    // the snackbar's few seconds cannot overwrite the first and strand it.
    private var captured: [Int64: DeletedRecipe] = [:]
    private var capturedOrder: [Int64] = []
    private var batches = 0

    init(repository: RecipeRepository, countdowns: TimerCountdownSync?) {
        self.repository = repository
        self.countdowns = countdowns
    }

    /// Deletes `recipes` in one transaction, keeping what it removed so `undo` can restore it.
    /// The first of a burst starts a batch; the rest join it.
    func delete(_ recipes: [RecipeSummary]) async {
        guard !recipes.isEmpty else { return }
        let removed = await repository.deleteAll(ids: recipes.map(\.id))
        guard !removed.isEmpty else { return }
        state.restoreRefused = false // a new delete's Undo snackbar takes the place
        // Their countdowns go with them; Undo brings the timers back when a recipe is opened.
        await countdowns?.reconcile()
        let titles = Dictionary(recipes.map { ($0.id, $0.title) }, uniquingKeysWith: { first, _ in first })
        var next = state
        for deleted in removed {
            let id = deleted.recipe.id
            if captured[id] == nil { capturedOrder.append(id) }
            captured[id] = deleted
            next.pendingDeletes.append(titles[id] ?? deleted.recipe.name)
        }
        if next.deleteBatch == nil {
            batches += 1
            next.deleteBatch = batches
        }
        state = next
    }

    /// Restores everything still pending, oldest first. All or nothing: one snackbar covers the
    /// batch, so there is no way to pick one out of it. On the free tier at its limit (#345)
    /// none comes back: `State.restoreRefused`, and the deletes stand.
    func undo() {
        guard !capturedOrder.isEmpty else { return }
        let removed = capturedOrder.compactMap { captured[$0] }
        clear()
        Task {
            // The limit is checked in the repository's transaction: a burst that wouldn't fit
            // restores none, and the photo files go as when the snackbar times out.
            if await repository.restoreAll(removed) == .libraryFull {
                state.restoreRefused = true
                for deleted in removed { await repository.forget(deleted) }
            }
        }
    }

    /// The screen has said the Undo was refused.
    func restoreRefusedShown() { state.restoreRefused = false }

    /// The snackbar timed out: the deletes stand.
    func settle() {
        // Their photos (#116) were kept only for Undo.
        let settled = capturedOrder.compactMap { captured[$0] }
        clear()
        if !settled.isEmpty {
            Task { for deleted in settled { await repository.forget(deleted) } }
        }
    }

    private func clear() {
        captured.removeAll()
        capturedOrder.removeAll()
        state.pendingDeletes = []
        state.deleteBatch = nil
    }
}
