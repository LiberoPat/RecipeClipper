import Combine
import Foundation
import Observation

/// `loaded` is false until the database has answered once, so the screen doesn't flash its
/// "nothing here yet" hint at someone who has plenty of history.
struct HomeUiState: Equatable {
    var loaded = false
    var urlInput = ""
    var urlError = false
    var continueCooking: RecipeSummary?
    var recent: [RecipeSummary] = []
    /// "Restore from a backup file" (#150): offered on an empty library; its outcome shows under it.
    var restore: BackupStatus = .idle
    /// The only recipe is the tour's sample (#151), which leaves the library as good as empty.
    var onlySample = false

    var libraryEmpty: Bool { loaded && (continueCooking == nil || onlySample) }

    /// The restore row shows on an empty library, and stays to say how the restore went.
    var showsRestore: Bool { libraryEmpty || restore != .idle }
}

@MainActor
@Observable
final class HomeViewModel {
    static let recentCount = 5

    private(set) var uiState = HomeUiState()
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()
    // Restore (#150): the same file reading and merge as Settings' Import; nil offers none.
    @ObservationIgnored private let backups: BackupRepository?
    @ObservationIgnored private let files: BackupFiles?

    /// The most recent recipe is the "continue cooking" card; the five before it are "recent".
    /// There is deliberately no "Saved" section (see CLAUDE.md, UI decisions).
    init(repository: RecipeRepository, backups: BackupRepository? = nil, files: BackupFiles? = nil) {
        self.backups = backups
        self.files = files
        repository.observeRecent(limit: Self.recentCount + 1)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] recent in
                guard let self else { return }
                uiState.loaded = true
                uiState.continueCooking = recent.first
                uiState.recent = Array(recent.dropFirst())
                uiState.onlySample = false
                // The sample alone (#151) still offers the restore row: there is nothing of
                // the user's to restore over.
                if recent.count == 1, let only = recent.first {
                    Task { [weak self] in
                        let sample = await repository.sampleId()
                        guard let self, uiState.continueCooking?.id == only.id, uiState.recent.isEmpty else { return }
                        uiState.onlySample = sample == only.id
                    }
                }
            }
            .store(in: &cancellables)
    }

    func onUrlChange(_ value: String) {
        uiState.urlInput = value
        uiState.urlError = false
    }

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

    /// The link to open, or nil (and an error shown) if what was typed isn't a link.
    func onGo() -> String? {
        let url = UrlInput.normalize(uiState.urlInput)
        uiState.urlError = url == nil
        if url != nil { uiState.urlInput = "" }
        return url
    }
}
