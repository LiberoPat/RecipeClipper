import Combine
import Foundation
import Observation

/// `recipes` is nil until the database has answered, to tell "loading" from "nothing
/// matched". `pendingDeletes` holds the titles swiped away but not yet settled, newest last,
/// for the undo snackbar.
struct RecipesUiState: Equatable {
    var query = ""
    var recipes: [RecipeSummary]?
    var pendingDeletes: [String] = []
}

@MainActor
@Observable
final class RecipesViewModel {
    static let debounce = Duration.milliseconds(250)

    private(set) var uiState = RecipesUiState()

    @ObservationIgnored private let repository: RecipeRepository
    @ObservationIgnored private let sleep: Sleep
    @ObservationIgnored private var debounceTask: Task<Void, Never>?
    @ObservationIgnored private var appliedQuery: String?
    @ObservationIgnored private var historySubscription: AnyCancellable?

    // What swipe-to-delete captured, keyed by recipe id and kept in swipe order, so a second
    // swipe within the snackbar's few seconds cannot overwrite the first and strand it.
    @ObservationIgnored private var captured: [Int64: DeletedRecipe] = [:]
    @ObservationIgnored private var capturedOrder: [Int64] = []

    init(repository: RecipeRepository, sleep: @escaping Sleep = Sleeps.real) {
        self.repository = repository
        self.sleep = sleep
        schedule(query: "")
    }

    func onQueryChange(_ text: String) {
        uiState.query = text
        schedule(query: text)
    }

    /// Debounce, then drop a repeat of the query already applied, then switch to the latest
    /// query's results (the old subscription is cancelled) — Android's
    /// `debounce(250).distinctUntilChanged().flatMapLatest`.
    private func schedule(query: String) {
        debounceTask?.cancel()
        debounceTask = Task { [weak self, sleep] in
            do { try await sleep(Self.debounce) } catch { return }
            guard !Task.isCancelled, let self, query != self.appliedQuery else { return }
            self.appliedQuery = query
            self.historySubscription = self.repository.observeHistory(query: query)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] recipes in self?.uiState.recipes = recipes }
        }
    }

    /// Swipe-to-delete: deletes immediately, keeping what it removed so `onUndoDelete` can
    /// restore it.
    func onDelete(_ recipe: RecipeSummary) {
        Task {
            guard let removed = await repository.delete(id: recipe.id) else { return }
            if captured[recipe.id] == nil { capturedOrder.append(recipe.id) }
            captured[recipe.id] = removed
            uiState.pendingDeletes.append(recipe.title)
        }
    }

    /// Restores everything still pending, oldest first. All or nothing: one snackbar covers
    /// the batch, so there is no way to pick one out of it.
    func onUndoDelete() {
        guard !capturedOrder.isEmpty else { return }
        let removed = capturedOrder.compactMap { captured[$0] }
        clearCaptured()
        Task {
            for deleted in removed { await repository.restore(deleted) }
        }
    }

    /// The snackbar timed out: the deletes stand.
    func onSnackbarDismissed() {
        clearCaptured()
    }

    private func clearCaptured() {
        captured.removeAll()
        capturedOrder.removeAll()
        uiState.pendingDeletes = []
    }
}

/// How long the undo snackbar stays, and what settles the batch when it goes. Run from the
/// screen's `.task(id: pendingDeletes)`, so it is restarted by every new delete and cancelled
/// when the screen goes away; Android's `LaunchedEffect(pendingDeletes)` around
/// `showSnackbar`.
///
/// On cancellation neither outcome runs — no undo, no dismissal — so a restart for a second
/// swipe leaves the first capture for the new snackbar's Undo, and leaving the screen neither
/// restores anything nor forgets it (coming back re-shows the snackbar, as on Android; popping
/// History drops the ViewModel and with it the captures, so the deletes stand).
enum SnackbarTimeout {
    static let duration = Duration.seconds(4)

    @MainActor
    static func run(pending: [String], sleep: Sleep = Sleeps.real, onTimeout: () -> Void) async {
        guard !pending.isEmpty else { return }
        do { try await sleep(duration) } catch { return }
        guard !Task.isCancelled else { return }
        onTimeout()
    }
}
