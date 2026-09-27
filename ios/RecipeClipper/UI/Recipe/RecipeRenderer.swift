import Foundation

/// Turns a recipe into what the reading view and cook mode show (#169; Android's
/// `RecipeRenderer`): the ingredients without the junk the model decided (#174), scaled and
/// converted, the steps with their oven temperatures converted, each step's timer, the amounts
/// inside steps (#101) and Chef mode's short steps (#100). Pure: the same inputs always render the same, with no state or tasks, so
/// the ViewModel only decides when to render. Pinned to the Kotlin by the `Render` rows of
/// `DifferentialCorpusTests`.
enum RecipeRenderer {

    /// What a recipe renders under: the user's global defaults (see `RecipeUiState`) and the
    /// on-device model's decisions (#104: count brackets, and junk after an ingredient, #174),
    /// `.none` until one lands.
    struct Settings: Equatable {
        var unitSystem: UnitSystem = .asWritten
        var convertLiquids = false
        var temperatureUnit: TemperatureUnit = .asWritten
        var amountsInSteps = false
        var decisions = Decisions.none
    }

    /// `recipe` as the screen shows it, at its chosen servings (else its own yield), with
    /// `shortSteps` (Chef mode's, as saved: one per step, nil for none) rendered like the steps.
    static func content(_ recipe: Recipe, settings: Settings, shortSteps: [String?] = []) -> RecipeSuccess {
        // The recipe's language picks the words, never the phone's (#14).
        let words = LanguageWords.forRecipe(recipe)
        let scale = servings(recipe, words: words)
        let content = RecipeSuccess(
            recipe: recipe,
            servings: scale,
            ingredients: ingredients(recipe, words: words, servings: scale, settings: settings),
            instructions: instructions(recipe, words: words, unit: settings.temperatureUnit),
            stepTimerSeconds: recipe.instructions.map { StepTimers.parse($0, words: words) },
            sourceDomain: SourceDomain.of(recipe.sourceUrl),
            words: words
        )
        return withShortSteps(content, shortSteps, settings: settings)
    }

    /// The yield's servings stepper at the chosen servings; nil when the yield has no number.
    static func servings(_ recipe: Recipe, words: LanguageWords?) -> ServingsScale? {
        Servings.parse(recipe.yield, words: words).map { base in
            ServingsScale(base: base, target: recipe.servingsTarget.map { min(max($0, 1), Servings.max) } ?? base)
        }
    }

    /// `content` at `target` servings, kept within the stepper's range; nil when it has no
    /// stepper. Only the ingredients (and the amounts inside steps) change.
    static func withServings(_ content: RecipeSuccess, target: Int, settings: Settings) -> RecipeSuccess? {
        guard let servings = content.servings else { return nil }
        var content = content
        let scale = ServingsScale(base: servings.base, target: min(max(target, 1), Servings.max))
        content.servings = scale
        content.ingredients = ingredients(content.recipe, words: content.words, servings: scale, settings: settings)
        return withStepAmounts(content, on: settings.amountsInSteps)
    }

    /// `content` again under new `settings`, keeping its chosen servings.
    static func rerender(_ content: RecipeSuccess, settings: Settings, shortSteps: [String?]) -> RecipeSuccess {
        var content = content
        content.ingredients = ingredients(content.recipe, words: content.words, servings: content.servings, settings: settings)
        content.instructions = instructions(content.recipe, words: content.words, unit: settings.temperatureUnit)
        return withShortSteps(content, shortSteps, settings: settings)
    }

    /// Chef mode's saved short steps, rendered like the steps they stand for. They show only
    /// when there is one entry per step; otherwise (none yet, or steps since changed) none do.
    static func withShortSteps(_ content: RecipeSuccess, _ shortSteps: [String?], settings: Settings) -> RecipeSuccess {
        var content = content
        let shorts = shortSteps.count == content.recipe.instructions.count ? shortSteps : []
        let words = content.words
        content.shortInstructions = shorts.map { $0.map { step($0, words: words, unit: settings.temperatureUnit) } }
        return withStepAmounts(content, on: settings.amountsInSteps)
    }

    // Junk hidden first (#174), then scaled, then converted, so a converted amount always matches
    // the chosen servings. Every line stays at its index, so ticks never shift.
    static func ingredients(
        _ recipe: Recipe, words: LanguageWords?, servings: ServingsScale?, settings: Settings
    ) -> [String] {
        let factor = servings.map { Double($0.target) / Double($0.base) } ?? 1.0
        return IngredientRendering.render(
            recipe.ingredients, factor: factor, system: settings.unitSystem, convertLiquids: settings.convertLiquids,
            words: words, decisions: settings.decisions
        )
    }

    // Instructions aren't scaled, but oven temperatures follow the chosen temperature unit —
    // independent of the ingredient unit system.
    static func instructions(_ recipe: Recipe, words: LanguageWords?, unit: TemperatureUnit) -> [String] {
        recipe.instructions.map { step($0, words: words, unit: unit) }
    }

    /// One step as shown, as written or Chef mode's short version (#100): the same rendering.
    static func step(_ step: String, words: LanguageWords?, unit: TemperatureUnit) -> String {
        TemperatureConverter.convert(step, unit: unit, words: words)
    }

    /// Amounts inside steps (#101), from the lines as rendered, so they follow servings and
    /// units; Chef mode's short steps (#100) get theirs the same way. Nil when `on` is false.
    static func withStepAmounts(_ content: RecipeSuccess, on: Bool) -> RecipeSuccess {
        var content = content
        content.stepAmounts = on
            ? StepAmounts.annotate(content.instructions, lines: content.ingredients, words: content.words)
            : nil
        content.shortStepAmounts = on && content.shortInstructions.contains { $0 != nil }
            ? StepAmounts.annotate(content.shortInstructions.map { $0 ?? "" }, lines: content.ingredients, words: content.words)
            : nil
        return content
    }
}
