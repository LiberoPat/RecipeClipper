import Foundation
import Observation

// Compiled into both the app and the share extension (see project.yml): the extension runs
// it, and the app target carries it so the hosted unit tests can reach it.

/// What the share extension shows: the import in progress, the saved recipe, or why not.
enum ShareImportUiState: Equatable {
    case loading
    /// Saved (or, after a failed fetch, found already saved) under this title.
    case saved(title: String)
    case failed(ParseError)
    /// The free library is full and every recipe is protected (#107): parsed, not saved. The
    /// unlock is bought in the app, which this extension can't open.
    case notKept(title: String)
    /// Nothing shared carried a web link.
    case noLink
}

/// The share extension's one screen. The import itself is `RecipeRepository.importFromUrl`, the
/// same call the app's import screen makes, so every rule comes with it: the link is cleaned,
/// the recipe is upserted and history culled, a block or blip is retried once after a pause,
/// a link saved before opens from the saved copy, and a cancelled import writes nothing.
@MainActor
@Observable
final class ShareImportViewModel {
    private(set) var uiState: ShareImportUiState = .loading

    @ObservationIgnored private let repository: RecipeRepository?
    @ObservationIgnored private let connectivity: Connectivity
    @ObservationIgnored private var input: SharedInput?
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var reconnectTask: Task<Void, Never>?

    /// `repository` is nil when the extension couldn't open the shared database (no App Group
    /// container, or the file wouldn't open): every import then fails as `saveFailed`.
    init(repository: RecipeRepository?, connectivity: Connectivity = StaticConnectivity()) {
        self.repository = repository
        self.connectivity = connectivity
    }

    deinit {
        loadTask?.cancel()
        reconnectTask?.cancel()
    }

    /// Called once, with whatever the share carried (nil: no web link in it).
    func start(with input: SharedInput?) {
        self.input = input
        load()
    }

    func onRetry() { load() }

    /// The user dismissed the card. A running import stops and writes nothing.
    func onCancel() {
        loadTask?.cancel()
        reconnectTask?.cancel()
    }

    /// The running import, for a caller (the tests) that needs to wait for it.
    var currentLoad: Task<Void, Never>? { loadTask }

    private func load() {
        loadTask?.cancel()
        reconnectTask?.cancel()
        reconnectTask = nil
        guard let input else {
            uiState = .noLink
            return
        }
        guard let repository else {
            uiState = .failed(.saveFailed)
            return
        }
        uiState = .loading
        loadTask = Task { [weak self, repository, input] in
            let result = await repository.importFromUrl(input.url, renderedPage: input.page)
            guard !Task.isCancelled, let self else { return }
            switch result {
            case .success(let recipe):
                uiState = .saved(title: recipe.name)
            case .notKept(let recipe):
                uiState = .notKept(title: recipe.name)
            case .error(let error):
                uiState = .failed(error)
                if error.reloadsOnReconnect { reloadOnReconnect() }
            }
        }
    }

    /// As on the recipe screen: while an offline or fetch-failed error shows, a real
    /// offline→online transition loads once more.
    private func reloadOnReconnect() {
        reconnectTask = Task { [weak self, connectivity] in
            let reconnected = await connectivity.waitForReconnect()
            guard reconnected, !Task.isCancelled, let self else { return }
            load()
        }
    }
}
