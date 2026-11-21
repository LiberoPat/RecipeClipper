import Foundation

// Android's data/model/CookProgress.kt.

/// A step timer as it is saved. `endsAt` is the wall-clock deadline (epoch ms) while the timer
/// runs, and nil while it is paused or has been reset; `remainingSeconds` is what a paused timer
/// had left. A running timer's remaining time is always recomputed from `endsAt`, so it keeps
/// counting while the app is closed.
struct SavedTimer: Equatable {
    var totalSeconds: Int
    var remainingSeconds: Int
    var endsAt: Int64?
}

/// Where a cook stands on one recipe, persisted so closing the app mid-cook loses nothing.
/// Step indexes point into the recipe's instructions, which is why a re-share that changes the
/// steps drops this (see `RecipeDao.upsert`), except when the only change is that its last steps
/// became tips (#432, `keptWhenTipsSplitOff`). The chosen servings are saved separately
/// (`Recipe.servingsTarget`) because they survive a change of steps.
struct CookProgress: Equatable {
    var active = false
    var currentStep = 0
    var doneSteps: Set<Int> = []
    var timers: [Int: SavedTimer] = [:]

    var isEmpty: Bool { self == CookProgress() }

    /// This progress on a recipe with only `stepCount` steps: done marks and timers on a step past
    /// the last are dropped, and the current step moves back to the last step. No steps at all,
    /// no progress.
    func clamped(to stepCount: Int) -> CookProgress {
        guard stepCount > 0 else { return CookProgress() }
        let steps = 0..<stepCount
        return CookProgress(
            active: active,
            currentStep: min(max(currentStep, 0), stepCount - 1),
            doneSteps: doneSteps.filter { steps.contains($0) },
            timers: timers.filter { steps.contains($0.key) }
        )
    }

    /// True when the steps changed only because their last ones are now tips (#432): a recipe
    /// saved before tips existed, its tips stored as its last steps, refreshed by a re-share. Its
    /// step indexes still mean the same steps, so its cook progress stays, `clamped(to:)` the steps
    /// left. Compared as the steps are stored, exactly; any other change is not this.
    static func keptWhenTipsSplitOff(oldSteps: [String], newSteps: [String], newTips: [String]) -> Bool {
        !newTips.isEmpty && oldSteps == newSteps + newTips
    }
}

/// One running timer: what a background alert needs to announce it.
struct StepAlarm: Equatable {
    let recipeId: Int64
    let recipeTitle: String
    let step: Int
    let endsAt: Int64
}
