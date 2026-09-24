import Foundation

/// A countdown on one step. `alerted` is set once the "time's up" sound has played.
struct StepTimer: Equatable {
    var totalSeconds: Int
    var remainingSeconds: Int
    var running: Bool
    var alerted = false

    var finished: Bool { remainingSeconds == 0 && !running }
}

/// Cook mode is a boolean on the recipe screen (`active`), not a destination. Progress is kept
/// when the user leaves and returns. `currentStep` is what is being cooked now; `doneSteps`
/// are struck off. Tapping a step moves `currentStep` without touching `doneSteps`.
/// Screen state, not domain (in memory only, like Android today).
struct CookState: Equatable {
    var active = false
    var currentStep = 0
    var doneSteps: Set<Int> = []
    var timers: [Int: StepTimer] = [:]
    var ingredientsExpanded = false
}

/// `ingredients` and `instructions` are what the screen shows: scaled and converted.
/// `servings` is nil when the yield has no usable number. `stepTimerSeconds` lines up with
/// the steps: the duration each one states, or nil.
struct RecipeSuccess: Equatable {
    var recipe: Recipe
    var servings: ServingsScale?
    var ingredients: [String]
    var instructions: [String]
    var stepTimerSeconds: [Int?]
}

enum RecipeContent: Equatable {
    case loading
    case success(RecipeSuccess)
    case error(ParseError)

    var success: RecipeSuccess? {
        if case .success(let s) = self { return s }
        return nil
    }
}

/// `unitSystem`, `convertLiquids` and `temperatureUnit` are the user's global defaults (set on
/// the Settings screen); servings belong to one recipe. `darkWhileCooking` forces ink in cook
/// mode; off by default, so cook mode follows the system theme.
struct RecipeUiState: Equatable {
    var content: RecipeContent = .loading
    var checkedIngredients: Set<Int> = []
    var unitSystem: UnitSystem = .asWritten
    var convertLiquids = false
    var temperatureUnit: TemperatureUnit = .asWritten
    var darkWhileCooking = false
    var cook = CookState()
    /// Set once the recipe has been deleted, so the screen can navigate back.
    var deleted = false

    /// In cook mode with a recipe to cook.
    var cooking: Bool { content.success != nil && cook.active }
    /// The recipe screen goes onto ink only when cooking with "Dark while cooking" on.
    var forceDark: Bool { cooking && darkWhileCooking }
}
