import Foundation

/// Cook mode's state machine (#169; Android's `CookSession`): the current, done and upcoming
/// steps, the ingredients bar, the step timers and the end of cooking (#147). Pure over
/// `clock`: each transition takes the `CookState` on screen and returns the next one, and the
/// one thing it keeps is each running timer's wall-clock deadline, so remaining time is
/// recomputed on every tick rather than counted down, and survives a pause, a closed app or a
/// killed one. It never touches alerts: the ViewModel keeps the tick loop and schedules or
/// cancels what a transition says.
struct CookSession {

    private let clock: Clock
    // Step index -> wall-clock time (ms) its timer ends. Only running timers are in here.
    private var deadlines: [Int: Int64] = [:]

    init(clock: Clock) { self.clock = clock }

    /// Whether any timer is running: the tick loop runs exactly while this holds.
    var hasRunningTimers: Bool { !deadlines.isEmpty }

    /// The saved progress brought back, and the alerts its running timers need, by step.
    struct Restored {
        let cook: CookState
        let alarms: [StepAlarm]
    }

    /// Picks up where the cook left off, even if the app was closed or killed meanwhile. A timer
    /// that was running resumes from its saved deadline; one whose deadline passed shows as
    /// finished and already alerted (its notification announced it). Indexes past the last step
    /// are dropped.
    mutating func restore(_ recipe: Recipe) -> Restored {
        deadlines = [:]
        let saved = recipe.cook
        let count = recipe.instructions.count
        let now = clock.now()
        var timers: [Int: StepTimer] = [:]
        var running: [StepAlarm] = []
        for (step, timer) in saved.timers where step >= 0 && step < count {
            if let endsAt = timer.endsAt, endsAt > now {
                deadlines[step] = endsAt
                running.append(StepAlarm(recipeId: recipe.id, recipeTitle: recipe.name, step: step, endsAt: endsAt))
                timers[step] = StepTimer(
                    totalSeconds: timer.totalSeconds, remainingSeconds: Self.secondsUntil(endsAt, now), running: true
                )
            } else if timer.endsAt != nil || timer.remainingSeconds == 0 {
                timers[step] = StepTimer(totalSeconds: timer.totalSeconds, remainingSeconds: 0, running: false, alerted: true)
            } else {
                timers[step] = StepTimer(totalSeconds: timer.totalSeconds, remainingSeconds: timer.remainingSeconds, running: false)
            }
        }
        let cook = CookState(
            active: saved.active && count > 0,
            currentStep: count > 0 ? min(max(saved.currentStep, 0), count - 1) : 0,
            doneSteps: saved.doneSteps.filter { $0 >= 0 && $0 < count },
            timers: timers
        )
        return Restored(cook: cook, alarms: running.sorted { $0.step < $1.step })
    }

    /// Stops every running timer and returns their steps, whose alerts must go too.
    mutating func stopTimers() -> [Int] {
        let steps = deadlines.keys.sorted()
        deadlines = [:]
        return steps
    }

    /// Cook mode on. `cook` is nil for a recipe with no steps, which has nothing to cook. A
    /// finished run starts fresh and its timers go with it: `stopped` are their steps, whose
    /// alerts must go too. Anything else resumes where it was left.
    struct Start {
        let cook: CookState?
        let stopped: [Int]
    }

    mutating func start(_ cook: CookState, stepCount: Int) -> Start {
        let finished = cook.doneSteps.count >= stepCount
        let stopped = finished ? stopTimers() : []
        guard stepCount > 0 else { return Start(cook: nil, stopped: stopped) }
        var next = finished ? CookState() : cook
        next.active = true
        next.currentStep = min(max(next.currentStep, 0), stepCount - 1)
        return Start(cook: next, stopped: stopped)
    }

    /// Cook mode off. Its progress is kept, so a stray tap on Exit loses nothing.
    func exit(_ cook: CookState) -> CookState {
        var cook = cook
        cook.active = false
        return cook
    }

    /// Opens or closes the ingredients bar.
    func toggleIngredients(_ cook: CookState) -> CookState {
        var cook = cook
        cook.ingredientsExpanded.toggle()
        return cook
    }

    /// Tapping a step makes it current. Only `done` ever advances or marks progress.
    func select(_ cook: CookState, step: Int) -> CookState {
        var cook = cook
        cook.currentStep = step
        return cook
    }

    /// "Done — next step": the cook after it, and whether that was the last step left.
    struct Done {
        let cook: CookState
        let finished: Bool
    }

    func done(_ cook: CookState, stepCount count: Int) -> Done {
        var cook = cook
        cook.doneSteps.insert(cook.currentStep)
        // Next unfinished step after this one, else the earliest one skipped, else finished.
        let done = cook.doneSteps
        let next = (cook.currentStep + 1 ..< max(count, cook.currentStep + 1)).first { !done.contains($0) }
            ?? (0 ..< count).first { !done.contains($0) }
        guard let next else {
            cook.active = false
            return Done(cook: cook, finished: true)
        }
        cook.currentStep = next
        return Done(cook: cook, finished: false)
    }

    /// The end of cooking (#147): the `ticked` lines as `content` shows them (scaled and
    /// converted), for using up the pantry; nil when nothing is ticked.
    func finishedCook(_ content: RecipeSuccess, ticked: Set<Int>) -> FinishedCook? {
        let lines = ticked.sorted().compactMap { content.ingredients.indices.contains($0) ? content.ingredients[$0] : nil }
        return lines.isEmpty ? nil : FinishedCook(language: content.words?.language, lines: lines)
    }

    /// A timer's next state and its alert: `endsAt` to schedule one then, nil to cancel it.
    struct TimerChange {
        let cook: CookState
        let endsAt: Int64?
    }

    /// Starts step `step`'s `totalSeconds` timer from the top.
    mutating func startTimer(_ cook: CookState, step: Int, totalSeconds: Int) -> TimerChange {
        let endsAt = clock.now() + Int64(totalSeconds) * 1000
        deadlines[step] = endsAt
        var cook = cook
        cook.timers[step] = StepTimer(totalSeconds: totalSeconds, remainingSeconds: totalSeconds, running: true)
        return TimerChange(cook: cook, endsAt: endsAt)
    }

    /// Pauses step `step`'s running timer, or resumes a paused one with time left; nil when it
    /// has no timer or it has finished, which leaves nothing to do.
    mutating func toggleTimer(_ cook: CookState, step: Int) -> TimerChange? {
        guard var timer = cook.timers[step] else { return nil }
        var cook = cook
        if timer.running {
            deadlines[step] = nil
            timer.running = false
            cook.timers[step] = timer
            return TimerChange(cook: cook, endsAt: nil)
        } else if timer.remainingSeconds > 0 {
            let endsAt = clock.now() + Int64(timer.remainingSeconds) * 1000
            deadlines[step] = endsAt
            timer.running = true
            cook.timers[step] = timer
            return TimerChange(cook: cook, endsAt: endsAt)
        }
        return nil
    }

    /// Step `step`'s timer back to its full time, stopped (its alert goes); nil with no timer.
    mutating func resetTimer(_ cook: CookState, step: Int) -> CookState? {
        guard let timer = cook.timers[step] else { return nil }
        deadlines[step] = nil
        var cook = cook
        cook.timers[step] = StepTimer(totalSeconds: timer.totalSeconds, remainingSeconds: timer.totalSeconds, running: false)
        return cook
    }

    /// The "time's up" sound has played for step `step`; nil with no timer.
    func alerted(_ cook: CookState, step: Int) -> CookState? {
        guard cook.timers[step] != nil else { return nil }
        var cook = cook
        cook.timers[step]?.alerted = true
        return cook
    }

    /// Every running timer's seconds left now, from its deadline; nil when none changed. A timer
    /// that reaches zero stops running and keeps its alert: its deadline has passed, so the alert
    /// has been delivered or is being delivered, and removing it would only race it. While this
    /// recipe is on screen the notification is suppressed (NotificationRouter) and the in-app
    /// beep sounds instead.
    mutating func tick(_ cook: CookState) -> CookState? {
        let now = clock.now()
        var timers = cook.timers
        var changed = false
        for (step, end) in deadlines {
            let seconds = Self.secondsUntil(end, now)
            if seconds == 0 { deadlines[step] = nil }
            guard var timer = timers[step], timer.remainingSeconds != seconds else { continue }
            timer.remainingSeconds = seconds
            timer.running = seconds > 0
            timers[step] = timer
            changed = true
        }
        guard changed else { return nil }
        var cook = cook
        cook.timers = timers
        return cook
    }

    /// The cook's place as saved. A running timer is saved by its deadline, so ticks never write
    /// and a closed app's timer keeps counting. `ingredientsExpanded` and `alerted` are screen
    /// state and aren't saved.
    func progress(_ cook: CookState) -> CookProgress {
        var timers: [Int: SavedTimer] = [:]
        for (step, timer) in cook.timers {
            timers[step] = SavedTimer(
                totalSeconds: timer.totalSeconds, remainingSeconds: timer.remainingSeconds, endsAt: deadlines[step]
            )
        }
        return CookProgress(active: cook.active, currentStep: cook.currentStep, doneSteps: cook.doneSteps, timers: timers)
    }

    /// Whole seconds from `now` until `end`, rounded up; 0 once it has passed.
    static func secondsUntil(_ end: Int64, _ now: Int64) -> Int {
        max(0, Int((Double(end - now) / 1000).rounded(.up)))
    }
}
