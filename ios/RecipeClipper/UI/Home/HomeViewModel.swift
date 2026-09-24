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
}

@MainActor
@Observable
final class HomeViewModel {
    static let recentCount = 5

    private(set) var uiState = HomeUiState()
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()

    /// The most recent recipe is the "continue cooking" card; the five before it are "recent".
    /// There is deliberately no "Saved" section (see CLAUDE.md, UI decisions).
    init(repository: RecipeRepository) {
        repository.observeRecent(limit: Self.recentCount + 1)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] recent in
                guard let self else { return }
                uiState.loaded = true
                uiState.continueCooking = recent.first
                uiState.recent = Array(recent.dropFirst())
            }
            .store(in: &cancellables)
    }

    func onUrlChange(_ value: String) {
        uiState.urlInput = value
        uiState.urlError = false
    }

    /// The link to open, or nil (and an error shown) if what was typed isn't a link.
    func onGo() -> String? {
        let url = UrlInput.normalize(uiState.urlInput)
        uiState.urlError = url == nil
        if url != nil { uiState.urlInput = "" }
        return url
    }
}
