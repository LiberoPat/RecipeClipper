import Foundation

/// What a recipe row's menu needs from a recipe it hasn't opened (#376): the text the reading
/// view would share, and the yield "Add to plan" starts from. Reads with `byIds`, which is not a
/// view, so the row's menu never reorders Recently viewed.
@MainActor
struct RecipeRowSupport {
    let repository: RecipeRepository
    let preferences: AppPreferences
    let decisions: DecisionRepository?

    /// The recipe as the reading view shares it: its saved servings, the user's units, the
    /// model's decisions so far. Nil when the recipe is gone.
    func shareText(id: Int64, labels: RecipeShareText.Labels) async -> (title: String, text: String)? {
        guard let content = await rendered(id) else { return nil }
        let text = RecipeShareText.format(
            recipe: content.recipe, servings: content.servings, ingredients: content.ingredients,
            instructions: content.instructions, labels: labels
        )
        return (content.recipe.name, text)
    }

    /// The recipe's own yield for "Add to plan" (as the recipe screen passes `servings.base`);
    /// nil when it has no number.
    func yieldServings(id: Int64) async -> Int? {
        guard let recipe = await repository.byIds([id]).first else { return nil }
        return RecipeRenderer.servings(recipe, words: LanguageWords.forRecipe(recipe))?.base
    }

    private func rendered(_ id: Int64) async -> RecipeSuccess? {
        guard let recipe = await repository.byIds([id]).first else { return nil }
        let settings = preferences.current
        let renderSettings = RecipeRenderer.Settings(
            unitSystem: settings.unitSystem, convertLiquids: settings.convertLiquids,
            temperatureUnit: settings.temperatureUnit, amountsInSteps: settings.amountsInSteps,
            decisions: await decisions?.current() ?? .none
        )
        return RecipeRenderer.content(recipe, settings: renderSettings)
    }
}
