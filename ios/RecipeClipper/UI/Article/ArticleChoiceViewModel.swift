import Foundation
import Observation

/// One of the article's recipes (#435): its place on the page, name, counts, and its id once
/// saved. Android's `ArticleChoiceRow`.
struct ArticleChoiceRow: Equatable {
    let index: Int
    let name: String
    let ingredients: Int
    let steps: Int
    var savedId: Int64? = nil
}

/// The chooser over a page's recipes (#435): the page's headline and photo above one row per
/// recipe. Android's `ArticleChoiceUiState`.
struct ArticleChoiceUiState: Equatable {
    var headline: String
    var image: String?
    var rows: [ArticleChoiceRow]
}

/// Several recipes written inside one article (#435): the cook picks one to check in the editor.
/// Nothing is saved here. Each time the screen shows again (`onShown`, back from the editor or a
/// saved recipe) it asks which are saved by now, so a saved one says so and opens as itself. The
/// route carries the recipes (the share extension's hand-off too). Android's `ArticleChoiceViewModel`.
@MainActor
@Observable
final class ArticleChoiceViewModel {
    private(set) var uiState: ArticleChoiceUiState
    @ObservationIgnored let articles: [ArticleRecipe]
    @ObservationIgnored private let repository: RecipeRepository

    init(articles: [ArticleRecipe], repository: RecipeRepository) {
        self.articles = articles
        self.repository = repository
        uiState = ArticleChoiceUiState(
            headline: articles.first?.headline ?? "", image: articles.first?.image,
            rows: articles.enumerated().map { i, recipe in
                ArticleChoiceRow(index: i, name: recipe.name, ingredients: recipe.ingredients.count, steps: recipe.steps.count)
            }
        )
    }

    @ObservationIgnored private var refreshTask: Task<Void, Never>?

    /// The running check of what is saved, for a caller (the tests) that needs to wait for it.
    var currentRefresh: Task<Void, Never>? { refreshTask }

    /// The screen is showing: which recipes are saved by now.
    func onShown() {
        refreshTask?.cancel()
        refreshTask = Task { [weak self, repository, articles] in
            let saved = await repository.savedIds(links: articles.map(\.sourceUrl))
            guard let self, !Task.isCancelled else { return }
            var rows = uiState.rows
            for i in rows.indices { rows[i].savedId = saved[articles[i].sourceUrl] }
            uiState.rows = rows
        }
    }
}
