import Foundation

/// How the loaded recipe is shown (#24, #234; Android's `RecipeDisplay`): the settings it renders
/// under (units, liquids, temperatures, amounts in steps, and the model's decisions), its chosen
/// servings, and rendering it again when one of those or Chef mode's short steps change. Pure
/// reducers over `RecipeUiState`, which the ViewModel assigns; `shortSteps` and `decisions` are
/// Chef mode's current inputs (`ChefMode.shortSteps`, `ChefMode.decisions`).
@MainActor
struct RecipeDisplay {
    let shortSteps: () -> [String?]
    let decisions: () -> Decisions

    /// What `RecipeRenderer` renders under while `state` is on screen.
    func settings(_ state: RecipeUiState) -> RecipeRenderer.Settings {
        RecipeRenderer.Settings(
            unitSystem: state.unitSystem,
            convertLiquids: state.convertLiquids,
            temperatureUnit: state.temperatureUnit,
            amountsInSteps: state.amountsInSteps,
            decisions: decisions()
        )
    }

    /// `recipe` as first shown, under `state`'s settings; Chef mode's short steps follow later.
    func content(_ recipe: Recipe, _ state: RecipeUiState) -> RecipeSuccess {
        RecipeRenderer.content(recipe, settings: settings(state))
    }

    /// A change to the global defaults, from Settings or from this screen's own dropdown,
    /// arriving while the recipe is open. Only a change that affects the text re-renders it:
    /// darkWhileCooking is a display choice and leaves the recipe alone, and scaled servings,
    /// ticks and cook progress are kept either way.
    func withSettings(_ state: RecipeUiState, _ settings: AppSettings) -> RecipeUiState {
        let rendersDifferently = settings.unitSystem != state.unitSystem
            || settings.convertLiquids != state.convertLiquids
            || settings.temperatureUnit != state.temperatureUnit
            || settings.amountsInSteps != state.amountsInSteps
        var next = state
        next.unitSystem = settings.unitSystem
        next.convertLiquids = settings.convertLiquids
        next.temperatureUnit = settings.temperatureUnit
        next.darkWhileCooking = settings.darkWhileCooking
        next.amountsInSteps = settings.amountsInSteps
        return rendersDifferently ? rerendered(next) : next
    }

    /// The units dropdown on the screen: `system`, at once, rather than waiting for the echo.
    func withUnitSystem(_ state: RecipeUiState, _ system: UnitSystem) -> RecipeUiState {
        var next = state
        next.unitSystem = system
        return rerendered(next)
    }

    /// The servings stepper: the loaded recipe scaled to `target`; nil when nothing is loaded or
    /// it has no yield to scale.
    func withServings(_ state: RecipeUiState, _ target: Int) -> RecipeSuccess? {
        guard let shown = state.content.success,
              let content = RecipeRenderer.withServings(shown, target: target, settings: settings(state)),
              content.servings != nil else { return nil }
        return content
    }

    /// The choice to save for `scale`: the recipe's own yield is saved as no choice at all.
    func savedServings(_ scale: ServingsScale) -> Int? { scale.target == scale.base ? nil : scale.target }

    /// The loaded recipe re-rendered under `state`'s settings, keeping its chosen servings.
    func rerendered(_ state: RecipeUiState) -> RecipeUiState {
        guard let content = state.content.success else { return state }
        var next = state
        next.content = .success(RecipeRenderer.rerender(content, settings: settings(state), shortSteps: shortSteps()))
        return next
    }

    /// Chef mode's short steps as they now stand, rendered like the steps they stand for; nil when
    /// nothing is loaded or the short steps shown are the same.
    func withShortSteps(_ state: RecipeUiState) -> RecipeUiState? {
        guard let content = state.content.success else { return nil }
        let shown = RecipeRenderer.withShortSteps(content, shortSteps(), settings: settings(state))
        guard shown.shortInstructions != content.shortInstructions else { return nil }
        var next = state
        next.content = .success(shown)
        return next
    }
}
