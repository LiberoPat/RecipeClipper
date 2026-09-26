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
/// Screen state, not domain. Saved as it changes (`CookProgress`), so a closed or killed app
/// picks up at the same step, with its timers still counting.
struct CookState: Equatable {
    var active = false
    var currentStep = 0
    var doneSteps: Set<Int> = []
    var timers: [Int: StepTimer] = [:]
    var ingredientsExpanded = false
}

/// `ingredients` and `instructions` are what the screen shows: scaled and converted.
/// `servings` is nil when the yield has no usable number. `stepTimerSeconds` lines up with
/// the steps: the duration each one states, or nil. `sourceDomain` is the site credited under
/// the title ("smittenkitchen.com"), or nil when the source link has no recognisable host
/// (then no credit is shown). `words` are the recipe's language's (#14), which the view uses
/// for the yield's kind and the timer labels; nil for a language the app has no words for.
/// `stepAmounts` lines up with `instructions`: each step with the ingredient amounts inside it
/// (#101), or nil when "Amounts in steps" is off.
struct RecipeSuccess: Equatable {
    var recipe: Recipe
    var servings: ServingsScale?
    var ingredients: [String]
    var instructions: [String]
    var stepTimerSeconds: [Int?]
    var sourceDomain: String?
    var words: LanguageWords?
    var stepAmounts: [[StepAmounts.Part]]? = nil
    /// Chef mode (#100): each step's short version, rendered like `instructions`, lined up with
    /// them; nil (or a short array) where a step has none yet, or none passed the check.
    /// `shortStepAmounts` are their amounts inside steps, as `stepAmounts` are the steps'.
    var shortInstructions: [String?] = []
    var shortStepAmounts: [[StepAmounts.Part]]? = nil

    private func showsShort(_ index: Int, _ asWritten: Set<Int>) -> Bool {
        index < shortInstructions.count && shortInstructions[index] != nil && !asWritten.contains(index)
    }

    /// Step `index`'s short version, unless the cook asked to see it as written.
    func shownStep(_ index: Int, asWritten: Set<Int>) -> String {
        showsShort(index, asWritten) ? shortInstructions[index]! : instructions[index]
    }

    func hasShortStep(_ index: Int) -> Bool { index < shortInstructions.count && shortInstructions[index] != nil }

    /// Step `index` with its amounts, or nil to show it as written.
    func stepParts(_ index: Int) -> [StepAmounts.Part]? {
        guard let stepAmounts, index < stepAmounts.count else { return nil }
        return stepAmounts[index]
    }

    /// The amounts inside the step as `shownStep` shows it; nil when amounts are off.
    func shownStepParts(_ index: Int, asWritten: Set<Int>) -> [StepAmounts.Part]? {
        guard stepAmounts != nil else { return nil }
        guard showsShort(index, asWritten) else { return stepParts(index) }
        guard let shortStepAmounts, index < shortStepAmounts.count else { return nil }
        return shortStepAmounts[index]
    }

    /// The same, with every step as written: how the view shows it while the flag is off.
    var withoutStepAmounts: RecipeSuccess {
        var copy = self
        copy.stepAmounts = nil
        return copy
    }
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
    /// The user's note as typed; empty when there is none. Saved by the ViewModel.
    var notes = ""
    var unitSystem: UnitSystem = .asWritten
    var convertLiquids = false
    var temperatureUnit: TemperatureUnit = .asWritten
    var darkWhileCooking = false
    /// The Settings switch (#101), behind the `amountsInSteps` flag, which the view checks.
    var amountsInSteps = false
    var cook = CookState()
    /// Set once the recipe has been deleted, so the screen can navigate back.
    var deleted = false
    /// The prefilled "Report this site" issue link. Non-nil only while a shared link's
    /// `.noRecipeFound` is on screen: never for a block, offline or a failed fetch, which mean
    /// "try again", not "unsupported". The view opens it; nothing is sent.
    var reportSiteUrl: String?
    /// "Update from source" (#29) is fetching; the recipe stays on screen meanwhile.
    var updatingFromSource = false
    /// Why the last "Update from source" failed, until the view has shown it. The recipe on
    /// screen is unchanged.
    var updateError: ParseError?

    /// The shared link to clip by hand ("Clip it yourself", #37). Set exactly when
    /// `reportSiteUrl` is: only a page that loaded with no recipe data can be clipped.
    var clipUrl: String?

    /// Chef mode (#100): the steps the cook tapped to see as written, not short.
    var asWrittenSteps: Set<Int> = []

    /// In cook mode with a recipe to cook.
    var cooking: Bool { content.success != nil && cook.active }
    /// The recipe screen goes onto ink only when cooking with "Dark while cooking" on.
    var forceDark: Bool { cooking && darkWhileCooking }
}
