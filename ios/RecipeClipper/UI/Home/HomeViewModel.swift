import Combine
import Foundation
import Observation

/// `loaded` is false until the database has answered once, so the screen doesn't flash its
/// "nothing here yet" hint at someone who has plenty of history. `linkInput` is the "Paste a
/// link" alert's text (#286, the Recipes screen's alert), shown while `pastingLink`.
struct HomeUiState: Equatable {
    var loaded = false
    var pastingLink = false
    var linkInput = ""
    var continueCooking: RecipeSummary?
    /// Where the cook stopped on `continueCooking` (#286); nil when it isn't being cooked.
    var resume: CookResume?
    var recent: [RecipeSummary] = []
    /// The Recipes and Lists rows' counts (#286); nil until known.
    var recipeCount: Int?
    var listCount: Int?
    /// "Restore from a backup file" (#150): offered on an empty library; its outcome shows under it.
    var restore: BackupStatus = .idle
    /// The only recipe is the tour's sample (#151), which leaves the library as good as empty.
    var onlySample = false

    var libraryEmpty: Bool { loaded && (continueCooking == nil || onlySample) }

    /// The restore row shows on an empty library, and stays to say how the restore went.
    var showsRestore: Bool { libraryEmpty || restore != .idle }

    /// The alert's Go is enabled only for what reads as a link (`UrlInput`).
    var canOpenLink: Bool { UrlInput.normalize(linkInput) != nil }
}

/// A recipe being cooked (#286): cook mode was left on, or a step timer still runs. `step` is the
/// one stopped on (1-based) of `stepCount`; `timerSecondsLeft` is the running timer that ends
/// first, recomputed from its saved deadline, or nil when none runs.
struct CookResume: Equatable {
    let recipeId: Int64
    let step: Int
    let stepCount: Int
    let timerSecondsLeft: Int?

    /// Where `recipe` stands at `now`; nil when it isn't being cooked (or has no steps).
    static func of(_ recipe: Recipe, now: Int64) -> CookResume? {
        let count = recipe.instructions.count
        guard count > 0 else { return nil }
        let cook = recipe.cook
        let timer = cook.timers
            .filter { (0 ..< count).contains($0.key) }
            .compactMap(\.value.endsAt)
            .filter { $0 > now }
            .min()
            .map { CookSession.secondsUntil($0, now) }
        guard cook.active || timer != nil else { return nil }
        return CookResume(
            recipeId: recipe.id, step: min(max(cook.currentStep, 0), count - 1) + 1,
            stepCount: count, timerSecondsLeft: timer
        )
    }
}

@MainActor
@Observable
final class HomeViewModel {
    static let recentCount = 5
    /// How often a running timer's time left is recomputed, as in cook mode.
    static let tick = Duration.milliseconds(250)

    private(set) var uiState = HomeUiState()
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()
    // Restore (#150): the same file reading and merge as Settings' Import; nil offers none.
    @ObservationIgnored private let backups: BackupRepository?
    @ObservationIgnored private let files: BackupFiles?
    @ObservationIgnored private let repository: RecipeRepository
    @ObservationIgnored private let clock: Clock
    @ObservationIgnored private let sleep: Sleep
    // The resume card (#286): the latest recipe followed as its cook progress is saved.
    @ObservationIgnored private var followedId: Int64?
    @ObservationIgnored private var followed: Recipe?
    @ObservationIgnored private var cookCancellable: AnyCancellable?
    @ObservationIgnored private var tickTask: Task<Void, Never>?

    /// The most recent recipe is the "continue cooking" card; the five before it are "recent".
    /// There is deliberately no "Saved" section (see CLAUDE.md, UI decisions). `lists` gives the
    /// Lists row its count (#286; nil shows none).
    init(
        repository: RecipeRepository, backups: BackupRepository? = nil, files: BackupFiles? = nil,
        lists: ListRepository? = nil, clock: Clock = SystemClock(), sleep: @escaping Sleep = Sleeps.real
    ) {
        self.repository = repository
        self.backups = backups
        self.files = files
        self.clock = clock
        self.sleep = sleep
        observeRecent()
        repository.observeHistory(query: "").map(\.count)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] count in self?.uiState.recipeCount = count }
            .store(in: &cancellables)
        lists?.observeLists().map(\.count)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] count in self?.uiState.listCount = count }
            .store(in: &cancellables)
    }

    deinit { tickTask?.cancel() }

    private func observeRecent() {
        repository.observeRecent(limit: Self.recentCount + 1)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] recent in
                guard let self else { return }
                uiState.loaded = true
                uiState.continueCooking = recent.first
                uiState.recent = Array(recent.dropFirst())
                uiState.onlySample = false
                follow(recent.first?.id)
                // The sample alone (#151) still offers the restore row: there is nothing of
                // the user's to restore over.
                if recent.count == 1, let only = recent.first {
                    Task { [weak self, repository] in
                        let sample = await repository.sampleId()
                        guard let self, uiState.continueCooking?.id == only.id, uiState.recent.isEmpty else { return }
                        uiState.onlySample = sample == only.id
                    }
                }
            }
            .store(in: &cancellables)
    }

    /// Follows Continue cooking's recipe (#286): a newer one never shows the last one's place.
    private func follow(_ id: Int64?) {
        guard id != followedId else { return }
        followedId = id
        followed = nil
        uiState.resume = nil
        cookCancellable = id.map { id in
            repository.observeRecipe(id: id)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] recipe in
                    guard let self, recipe == nil || recipe?.id == followedId else { return }
                    followed = recipe
                    refreshResume()
                }
        }
    }

    /// Recomputes the card's place from the saved progress and the clock; writes nothing.
    private func refreshResume() {
        let resume = followed.flatMap { CookResume.of($0, now: clock.now()) }
        if uiState.resume != resume { uiState.resume = resume }
        if resume?.timerSecondsLeft != nil { ensureTicking() }
    }

    /// One tick loop while a timer runs, recomputing its time left from its deadline (as cook
    /// mode does); it ends by itself once none runs, and `deinit` cancels it.
    private func ensureTicking() {
        guard tickTask == nil else { return }
        tickTask = Task { [weak self, sleep] in
            while self?.uiState.resume?.timerSecondsLeft != nil {
                do { try await sleep(Self.tick) } catch { break }
                self?.refreshResume()
            }
            self?.tickTask = nil
        }
    }

    /// Whether the tick loop is alive. For tests: it must not outlive the last running timer.
    var isTicking: Bool { tickTask != nil }

    // MARK: Paste a link (#286): the Recipes screen's alert, opened from Home's tile.

    func onPasteLink() {
        uiState.linkInput = ""
        uiState.pastingLink = true
    }

    func onLinkChange(_ text: String) { uiState.linkInput = text }

    /// The alert went away by itself. Only the flag is cleared, as on Recipes: SwiftUI may run
    /// Go's action before or after resetting the binding.
    func onLinkDismissed() { uiState.pastingLink = false }

    /// The link to import, closing the alert; nil, and the alert stays, if it isn't one.
    func onOpenLink() -> String? {
        guard let url = UrlInput.normalize(uiState.linkInput) else { return nil }
        uiState.pastingLink = false
        uiState.linkInput = ""
        return url
    }

    // MARK: Restore (#150)

    /// Whether "Restore from a backup file" can be offered at all.
    var canRestore: Bool { backups != nil && files != nil }

    /// "Restore from a backup file" (#150): the file the user picked, merged in like Import.
    /// Returns the work so a test can await it.
    @discardableResult
    func onRestorePicked(_ url: URL) -> Task<Void, Never>? {
        guard let backups, let files, uiState.restore != .importing else { return nil }
        uiState.restore = .importing
        return Task {
            uiState.restore = BackupStatus(await backups.importFile(url, files: files))
        }
    }

    /// The file importer failed before a file was chosen (a provider error, not a cancel).
    func onRestorePickFailed() {
        guard uiState.restore != .importing else { return }
        uiState.restore = .failed(.readFailed)
    }
}
