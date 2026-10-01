import Foundation

/// Cook mode's effects around `CookSession` (#10, #234; Android's `CookController`): the alerts
/// its timers need, the one tick loop for every running timer, and saving the cook's place, on
/// every action, through the screen's one write queue (`writes`). The ViewModel still owns the
/// state: `cook` reads the `CookState` on screen and `onCook` writes the next one; `content` is
/// the recipe on screen, nil while loading or showing an error. Nothing it starts holds it
/// strongly, and it stops its tick loop when it goes, so it leaves with its screen.
@MainActor
final class CookController {
    static let tick = Duration.milliseconds(250)

    /// The recipe on screen. Set by the ViewModel.
    var content: () -> RecipeSuccess? = { nil }
    /// The cook's place on screen. Set by the ViewModel.
    var cook: () -> CookState = { CookState() }
    /// Shows the cook's next place. Set by the ViewModel.
    var onCook: (CookState) -> Void = { _ in }

    // Cook mode's steps and timers; this keeps only the tick loop and the alert calls.
    private var session: CookSession
    private let alarms: TimerAlarmScheduler
    private let sleep: Sleep
    private let writes: OrderedWrites
    private let repository: RecipeRepository
    private var tickTask: Task<Void, Never>?

    init(clock: Clock, alarms: TimerAlarmScheduler, sleep: @escaping Sleep, writes: OrderedWrites, repository: RecipeRepository) {
        session = CookSession(clock: clock)
        self.alarms = alarms
        self.sleep = sleep
        self.writes = writes
        self.repository = repository
    }

    deinit { tickTask?.cancel() }

    /// Picks up where the cook left off (`CookSession.restore`). The recipe's pending alerts are
    /// replaced by its running timers', which also clears any left over from a re-share that
    /// changed the steps.
    func restore(_ recipe: Recipe) {
        let restored = session.restore(recipe)
        alarms.replaceAll(recipeId: recipe.id, with: restored.alarms)
        onCook(restored.cook)
        if session.hasRunningTimers { ensureTicking() }
    }

    /// Stops every running timer and cancels its alert on recipe `recipeId`: the recipe was
    /// deleted (its timers must not ring later), or its steps may have changed.
    func stopTimers(recipeId: Int64) {
        for step in session.stopTimers() { alarms.cancel(recipeId: recipeId, step: step) }
    }

    func start() {
        guard let content = content() else { return }
        let count = content.instructions.count
        guard count > 0 else { return }
        let start = session.start(cook(), stepCount: count)
        // A finished run starts fresh, and its timers go with it, so their alerts must too.
        for step in start.stopped { alarms.cancel(recipeId: content.recipe.id, step: step) }
        if let cook = start.cook { onCook(cook) }
        save()
    }

    func exit() {
        onCook(session.exit(cook()))
        save()
    }

    func toggleIngredients() { onCook(session.toggleIngredients(cook())) }

    /// Tapping a step makes it current. Only `done` ever advances or marks progress.
    func select(_ step: Int) {
        onCook(session.select(cook(), step: step))
        save()
    }

    /// "Done — next step" on `current`: the cook after it, whether that was the last step left,
    /// and then what was ticked in `checked` (#147), as `content` shows it, for the pantry's
    /// use-up sheet; nil when nothing was. The ViewModel shows it and then calls `save`.
    struct Done {
        let cook: CookState
        let finished: Bool
        let finishedCook: FinishedCook?
    }

    func done(_ current: CookState, content: RecipeSuccess, checked: Set<Int>) -> Done {
        let done = session.done(current, stepCount: content.instructions.count)
        // A finished run finishes again only after starting fresh, so once per cook.
        let finishedCook = done.finished ? session.finishedCook(content, ticked: checked) : nil
        return Done(cook: done.cook, finished: done.finished, finishedCook: finishedCook)
    }

    // MARK: Step timers. Several can run at once, since steps overlap.

    func startTimer(_ step: Int) {
        guard let content = content(),
              step >= 0, step < content.stepTimerSeconds.count,
              let total = content.stepTimerSeconds[step] else { return }
        applyTimer(session.startTimer(cook(), step: step, totalSeconds: total), content.recipe, step)
    }

    func toggleTimer(_ step: Int) {
        guard let recipe = content()?.recipe,
              let change = session.toggleTimer(cook(), step: step) else { return }
        applyTimer(change, recipe, step)
    }

    // Shows a timer's change, schedules or cancels its alert, and saves.
    private func applyTimer(_ change: CookSession.TimerChange, _ recipe: Recipe, _ step: Int) {
        onCook(change.cook)
        if let endsAt = change.endsAt {
            alarms.schedule(StepAlarm(recipeId: recipe.id, recipeTitle: recipe.name, step: step, endsAt: endsAt))
            ensureTicking()
        } else {
            alarms.cancel(recipeId: recipe.id, step: step)
        }
        save()
    }

    func resetTimer(_ step: Int) {
        guard let next = session.resetTimer(cook(), step: step) else { return }
        onCook(next)
        if let id = content()?.recipe.id { alarms.cancel(recipeId: id, step: step) }
        save()
    }

    func alerted(_ step: Int) {
        if let next = session.alerted(cook(), step: step) { onCook(next) }
    }

    /// Saves the cook's place as it now stands (`CookSession.progress`), behind every earlier write.
    func save() {
        guard let id = content()?.recipe.id else { return }
        let progress = session.progress(cook())
        writes.enqueue { [repository] in await repository.setCookProgress(id: id, progress: progress) }
    }

    /// One tick loop for every running timer. Remaining time is recomputed from the wall
    /// clock each tick (`CookSession.tick`) rather than decremented, so a pause in delivery never
    /// drifts it. The loop ends by itself once no timer is running, and `deinit` cancels it.
    private func ensureTicking() {
        guard tickTask == nil else { return }
        // Weak across the sleep, so a screen popped with a timer running lets this go.
        tickTask = Task { [weak self, sleep] in
            while self?.session.hasRunningTimers == true {
                do { try await sleep(Self.tick) } catch { break }
                self?.tickOnce()
            }
            self?.tickTask = nil
        }
    }

    /// Whether the tick loop is alive. For tests: it must not outlive the last running timer.
    var isTicking: Bool { tickTask != nil }

    private func tickOnce() {
        if let next = session.tick(cook()) { onCook(next) }
    }
}
