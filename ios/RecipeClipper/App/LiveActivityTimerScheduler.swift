import ActivityKit
import Foundation

/// Shows running timers outside the app (#309): the seam between the scheduler and ActivityKit,
/// so tests pass a fake. Android's twin is the ongoing notification in `TimerNotifications`.
@MainActor
protocol TimerActivityDisplay: AnyObject {
    /// Starts `alarm`'s countdown, or moves an existing one to its new deadline.
    func show(_ alarm: StepAlarm)
    func end(recipeId: Int64, step: Int)
    /// Ends every countdown of `recipeId` but `alarms`', and shows those.
    func replaceAll(recipeId: Int64, with alarms: [StepAlarm])
    /// Ends every countdown but `alarms`' (#333), whichever recipe's; shows nothing.
    func keepOnly(_ alarms: [StepAlarm])
}

/// The real `TimerAlarmScheduler` with `liveTimers` (#309): the time's-up alerts it wraps, plus a
/// countdown for each running timer. Ending one never depends on the flag, so turning the flag
/// off mid-cook leaves nothing behind.
@MainActor
final class LiveActivityTimerScheduler: TimerAlarmScheduler {
    private let alarms: TimerAlarmScheduler
    private let display: TimerActivityDisplay
    private let flags: FeatureFlags

    init(wrapping alarms: TimerAlarmScheduler, display: TimerActivityDisplay, flags: FeatureFlags) {
        self.alarms = alarms
        self.display = display
        self.flags = flags
    }

    func schedule(_ alarm: StepAlarm) {
        alarms.schedule(alarm)
        if flags.isOn(.liveTimers) { display.show(alarm) }
    }

    func cancel(recipeId: Int64, step: Int) {
        alarms.cancel(recipeId: recipeId, step: step)
        display.end(recipeId: recipeId, step: step)
    }

    func replaceAll(recipeId: Int64, with alarms: [StepAlarm]) {
        self.alarms.replaceAll(recipeId: recipeId, with: alarms)
        display.replaceAll(recipeId: recipeId, with: flags.isOn(.liveTimers) ? alarms : [])
    }

    func ended(recipeId: Int64, step: Int) {
        alarms.ended(recipeId: recipeId, step: step)
        display.end(recipeId: recipeId, step: step)
    }

    func reconcile(running: [StepAlarm]) {
        alarms.reconcile(running: running)
        display.keepOnly(flags.isOn(.liveTimers) ? running : [])
    }
}

/// One Live Activity per running timer (`TimerActivityAttributes`). Every operation waits for
/// the previous one, since ending is asynchronous: a pause then a quick resume must not find
/// the activity the pause is still ending and update it.
@MainActor
final class ActivityKitTimerDisplay: TimerActivityDisplay {
    private let clock: Clock
    private var last: Task<Void, Never>?

    init(clock: Clock) { self.clock = clock }

    func show(_ alarm: StepAlarm) {
        enqueue { [clock] in await Self.show(alarm, now: clock.now()) }
    }

    func end(recipeId: Int64, step: Int) {
        enqueue { await Self.end { $0.recipeId == recipeId && $0.step == step } }
    }

    func replaceAll(recipeId: Int64, with alarms: [StepAlarm]) {
        let steps = Set(alarms.map(\.step))
        enqueue { [clock] in
            await Self.end { $0.recipeId == recipeId && !steps.contains($0.step) }
            for alarm in alarms { await Self.show(alarm, now: clock.now()) }
        }
    }

    func keepOnly(_ alarms: [StepAlarm]) {
        let keep = Set(alarms.map { Key(recipeId: $0.recipeId, step: $0.step) })
        enqueue { await Self.end { !keep.contains(Key(recipeId: $0.recipeId, step: $0.step)) } }
    }

    private struct Key: Hashable {
        let recipeId: Int64
        let step: Int
    }

    private func enqueue(_ operation: @escaping @MainActor () async -> Void) {
        let previous = last
        last = Task { @MainActor in
            await previous?.value
            await operation()
        }
    }

    private static func activities(_ match: (TimerActivityAttributes) -> Bool) -> [Activity<TimerActivityAttributes>] {
        Activity<TimerActivityAttributes>.activities.filter { match($0.attributes) }
    }

    private static func show(_ alarm: StepAlarm, now: Int64) async {
        guard alarm.endsAt > now else { return }
        let endsAt = Date(timeIntervalSince1970: Double(alarm.endsAt) / 1000)
        let content = ActivityContent(state: TimerActivityAttributes.ContentState(endsAt: endsAt), staleDate: endsAt)
        for existing in activities({ $0.recipeId == alarm.recipeId && $0.step == alarm.step }) {
            // Still showing (past its stale date it reads "Time's up", and moves to the new
            // deadline). One the system ended (a timer over eight hours, #333) is no use: it
            // is cleared and a new one requested.
            switch existing.activityState {
            case .active, .stale:
                await existing.update(content)
                return
            default:
                await existing.end(nil, dismissalPolicy: .immediate)
            }
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = TimerActivityAttributes(recipeId: alarm.recipeId, recipeTitle: alarm.recipeTitle, step: alarm.step)
        // Refused (too many activities, or the app isn't in front): the alert still comes.
        _ = try? Activity.request(attributes: attributes, content: content, pushType: nil)
    }

    private static func end(_ match: (TimerActivityAttributes) -> Bool) async {
        for activity in activities(match) {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }
}

/// Shows nothing: under XCTest and UI-test seeding, like `NoOpTimerAlarmScheduler`.
final class NoOpTimerActivityDisplay: TimerActivityDisplay {
    nonisolated init() {}

    func show(_ alarm: StepAlarm) {}
    func end(recipeId: Int64, step: Int) {}
    func replaceAll(recipeId: Int64, with alarms: [StepAlarm]) {}
    func keepOnly(_ alarms: [StepAlarm]) {}
}
