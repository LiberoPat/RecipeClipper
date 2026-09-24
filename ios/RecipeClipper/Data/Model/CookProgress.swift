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
/// steps drops this (see `RecipeDao.upsert`). The chosen servings are saved separately
/// (`Recipe.servingsTarget`) because they survive a change of steps.
struct CookProgress: Equatable {
    var active = false
    var currentStep = 0
    var doneSteps: Set<Int> = []
    var timers: [Int: SavedTimer] = [:]

    var isEmpty: Bool { self == CookProgress() }
}

/// One running timer: what a background alert needs to announce it.
struct StepAlarm: Equatable {
    let recipeId: Int64
    let recipeTitle: String
    let step: Int
    let endsAt: Int64
}
