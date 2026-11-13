import Foundation

/// The one path that squares the countdowns shown outside the app (#309) with the database
/// (#333; Android's `TimerCountdownSync`): `reconcile` hands the scheduler the timers still
/// running there, and it ends every other Live Activity. Called after a recipe is deleted
/// (the Recipes swipe and the recipe screen), after a re-share, an edit or "Update from
/// source" (whose changed steps drop the cook's progress), when the app becomes active (the
/// share extension re-shares in its own process) and, from `AppContainer`, whenever
/// `liveTimers` changes. The alerts need none of this: opening the recipe squares them.
@MainActor
final class TimerCountdownSync {
    private let repository: RecipeRepository
    private let alarms: TimerAlarmScheduler

    init(repository: RecipeRepository, alarms: TimerAlarmScheduler) {
        self.repository = repository
        self.alarms = alarms
    }

    func reconcile() async {
        alarms.reconcile(running: await repository.runningTimers())
    }
}
