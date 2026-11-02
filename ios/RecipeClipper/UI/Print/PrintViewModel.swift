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

/// `job` is ready for the screen to draw; `failed` once it couldn't be made.
struct PrintUiState: Equatable {
    var busy = false
    var job: PrintJob?
    var failed = false
}

/// Print or save as PDF (#318). The recipe screen hands over its card, already built from what it
/// shows; a list's cookbook renders each recipe as the reading view would open it (its chosen
/// servings, the cook's units), without the note: a cookbook is for giving, as Send as file
/// leaves the note out. Drawing the PDF is the screen's (`PrintEffect`). Android's `PrintViewModel`.
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
            var cards: [CookbookRecipe] = []
            for id in recipeIds {
                guard let recipe = await Self.first(recipes, id) else { continue }
                let shown = RecipeRenderer.content(recipe, settings: settings)
                cards.append(CookbookRecipe(name: recipe.name, blocks: RecipePrint.blocks(
                    recipe: recipe, servings: shown.servings, ingredients: shown.ingredients,
                    instructions: shown.instructions, sourceDomain: shown.sourceDomain, note: nil, labels: labels
                )))
            }
            uiState = cards.isEmpty
                ? PrintUiState(failed: true)
                : PrintUiState(busy: true, job: .cookbook(title: title, contents: contents, recipes: cards))
        }
    }

    private static func first(_ recipes: RecipeRepository, _ id: Int64) async -> Recipe? {
        for await recipe in recipes.observeRecipe(id: id).values { return recipe }
        return nil
    }

    /// The screen drew the job (and opened the dialog or the share sheet).
    func onDone() {
        uiState = PrintUiState()
    }

    /// The screen couldn't draw the job.
    func onFailed() {
        uiState = PrintUiState(failed: true)
    }

    func onFailureShown() {
        uiState = PrintUiState()
    }
}
