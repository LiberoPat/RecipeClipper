import Foundation
import Observation

/// What to draw (#318): one recipe for the print dialog, or a cookbook PDF for the share sheet.
enum PrintJob: Equatable {
    case recipe(title: String, blocks: [RecipePrint.Block])
    /// `recipes` are each recipe's name with its card, in the list's order; `contents` heads the contents.
    case cookbook(title: String, contents: String, recipes: [CookbookRecipe])
}

struct CookbookRecipe: Equatable {
    var name: String
    var blocks: [RecipePrint.Block]
}

/// `busy` while a job is built or drawn (the screen shows it, #331); `job` is ready for the
/// screen to draw; `failed` once it couldn't be made.
struct PrintUiState: Equatable {
    var busy = false
    var job: PrintJob?
    var failed = false
}

/// Print or save as PDF (#318). The recipe screen hands over its card, already built from what it
/// shows; a list's cookbook renders each recipe as the reading view would open it (its chosen
/// servings, the cook's units), without the note: a cookbook is for giving, as Send as file
/// leaves the note out. Drawing the PDF is the screen's (`PrintEffect`). Leaving the screen
/// (`onLeave`) drops the job, so nothing pops up on return (#331). Android's `PrintViewModel`.
@MainActor
@Observable
final class PrintViewModel {
    private(set) var uiState = PrintUiState()

    @ObservationIgnored private let recipes: RecipeRepository
    @ObservationIgnored private let preferences: AppPreferences
    @ObservationIgnored private let decisions: DecisionRepository?
    @ObservationIgnored private(set) var currentWork: Task<Void, Never>?

    init(recipes: RecipeRepository, preferences: AppPreferences, decisions: DecisionRepository?) {
        self.recipes = recipes
        self.preferences = preferences
        self.decisions = decisions
    }

    func printRecipe(title: String, blocks: [RecipePrint.Block]) {
        guard !uiState.busy else { return }
        uiState = PrintUiState(busy: true, job: .recipe(title: title, blocks: blocks))
    }

    /// The list named `title`, its recipes `recipeIds` in order; a recipe gone meanwhile is skipped.
    func makeCookbook(title: String, recipeIds: [Int64], labels: RecipePrint.Labels, contents: String) {
        guard !uiState.busy, !recipeIds.isEmpty else { return }
        uiState = PrintUiState(busy: true)
        let recipes = recipes, preferences = preferences, decisions = decisions
        currentWork = Task {
            let current = preferences.current
            let settings = RecipeRenderer.Settings(
                unitSystem: current.unitSystem,
                convertLiquids: current.convertLiquids,
                temperatureUnit: current.temperatureUnit,
                decisions: await decisions?.current() ?? Decisions.none
            )
            // One query for the lot, then each rendered off the main actor (#331).
            let found = Dictionary(await recipes.byIds(recipeIds).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
            guard !Task.isCancelled else { return }
            let cards = await Task.detached(priority: .userInitiated) { () -> [CookbookRecipe] in
                recipeIds.compactMap { found[$0] }.map { recipe in
                    let shown = RecipeRenderer.content(recipe, settings: settings)
                    return CookbookRecipe(name: recipe.name, blocks: RecipePrint.blocks(
                        recipe: recipe, servings: shown.servings, ingredients: shown.ingredients,
                        instructions: shown.instructions, sourceDomain: shown.sourceDomain, note: nil, labels: labels
                    ))
                }
            }.value
            guard !Task.isCancelled else { return }
            uiState = cards.isEmpty
                ? PrintUiState(failed: true)
                : PrintUiState(busy: true, job: .cookbook(title: title, contents: contents, recipes: cards))
        }
    }

    /// The screen drew the job (and opened the dialog or the share sheet).
    func onDone() {
        uiState = PrintUiState()
    }

    /// The screen couldn't draw the job.
    func onFailed() {
        uiState = PrintUiState(failed: true)
    }

    /// The failure alert has gone; a job started meanwhile stays.
    func onFailureShown() {
        if uiState.failed { uiState = PrintUiState() }
    }

    /// The screen was left: whatever was being built or drawn is dropped, and nothing shows on return.
    func onLeave() {
        currentWork?.cancel()
        currentWork = nil
        uiState = PrintUiState()
    }
}
