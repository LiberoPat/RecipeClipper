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
    /// No link, but a list (#149): `receiveList` holds its lines, for Groceries or the Pantry.
    case list
    /// Reddit wouldn't let the app read the post (#213): it is left for the app
    /// (`PendingClip`), which opens "Clip it yourself" on it; the card says to open the app.
    case clipInApp
    /// Images shared in (#226): left for the app (`PendingScan`), whose review reads them and
    /// the cook checks (never saved unchecked); the card says to open the app.
    case scanInApp
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
    @ObservationIgnored private let makeReceiveList: (@MainActor () -> ReceiveListViewModel?)?
    /// The `reddit` flag as the app mirrors it (#11), and where a blocked post is left for the
    /// app (#213); nil leaves nothing.
    @ObservationIgnored private let redditOn: () -> Bool
    @ObservationIgnored private let pendingClip: PendingClip?
    /// Where images shared in are left for the app (#226); nil leaves nothing.
    @ObservationIgnored private let pendingScan: PendingScan?
    /// The images a share carried, staged as a scan's pages (#226), when it had no link.
    @ObservationIgnored private var scanPages: [String] = []
    @ObservationIgnored private let clock: Clock
    @ObservationIgnored private var input: SharedInput?
    @ObservationIgnored private var text: String?
    /// "Add this list" (#149), made only for shared text with no link but with lines, and only
    /// when the grocery list and the pantry are on.
    @ObservationIgnored private(set) var receiveList: ReceiveListViewModel?
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var reconnectTask: Task<Void, Never>?

    /// `repository` is nil when the extension couldn't open the shared database (no App Group
    /// container, or the file wouldn't open): every import then fails as `saveFailed`.
    /// `makeReceiveList` makes the list sheet's ViewModel (#149); nil, or nil from it (the
    /// grocery list is off, or there's no database), keeps a list shared in as `.noLink`.
    init(
        repository: RecipeRepository?, connectivity: Connectivity = StaticConnectivity(),
        makeReceiveList: (@MainActor () -> ReceiveListViewModel?)? = nil,
        redditOn: @escaping () -> Bool = { true }, pendingClip: PendingClip? = nil,
        pendingScan: PendingScan? = nil, clock: Clock = SystemClock()
    ) {
        self.repository = repository
        self.connectivity = connectivity
        self.makeReceiveList = makeReceiveList
        self.redditOn = redditOn
        self.pendingClip = pendingClip
        self.pendingScan = pendingScan
        self.clock = clock
    }

    deinit {
        loadTask?.cancel()
        reconnectTask?.cancel()
    }

    /// Called once, with whatever the share carried (nil: no web link in it) and, when it had
    /// no link, its images staged as a scan's pages (#226) or else its plain `text`.
    func start(with input: SharedInput?, text: String? = nil, scanPages: [String] = []) {
        self.input = input
        self.text = text
        self.scanPages = scanPages
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
            // Images (#226): the app reads them, and the cook checks what it read.
            if !scanPages.isEmpty, let pendingScan {
                pendingClip?.clear()
                pendingScan.put(scanPages, at: clock.now())
                uiState = .scanInApp
                return
            }
            if let text, !ReceivedList.lines(text).isEmpty,
               let list = receiveList ?? makeReceiveList?() {
                receiveList = list
                list.open(text)
                uiState = .list
            } else {
                uiState = .noLink
            }
            return
        }
        guard let repository else {
            uiState = .failed(.saveFailed)
            return
        }
        uiState = .loading
        // A new share moves on from any post or images left for the app before.
        pendingClip?.clear()
        pendingScan?.clear()
        loadTask = Task { [weak self, repository, input] in
            let result = await repository.importFromUrl(input.url, renderedPage: input.page)
            guard !Task.isCancelled, let self else { return }
            switch result {
            case .success(let recipe):
                uiState = .saved(title: recipe.name)
            case .notKept(let recipe):
                uiState = .notKept(title: recipe.name)
            case .error(let error):
                // Reddit's block (#213): as in the app, the post goes to "Clip it yourself".
                if RedditUrls.clipsWhenBlocked(input.url, error: error, redditOn: redditOn()) {
                    pendingClip?.put(input.url, at: clock.now())
                    uiState = .clipInApp
                    return
                }
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
