import Foundation
@testable import RecipeClipper

/// Keeps the pending alerts as real state (recipe id and step to the alarm), as the
/// notification center does, and records every call in order.
final class FakeTimerAlarmScheduler: TimerAlarmScheduler {
    struct Key: Hashable {
        let recipeId: Int64
        let step: Int
    }

    private(set) var pending: [Key: StepAlarm] = [:]
    private(set) var scheduleCalls: [StepAlarm] = []
    private(set) var cancelCalls: [Key] = []
    private(set) var replaceAllCalls: [(recipeId: Int64, alarms: [StepAlarm])] = []

    func schedule(_ alarm: StepAlarm) {
        scheduleCalls.append(alarm)
        pending[Key(recipeId: alarm.recipeId, step: alarm.step)] = alarm
    }

    func cancel(recipeId: Int64, step: Int) {
        cancelCalls.append(Key(recipeId: recipeId, step: step))
        pending[Key(recipeId: recipeId, step: step)] = nil
    }

    func replaceAll(recipeId: Int64, with alarms: [StepAlarm]) {
        replaceAllCalls.append((recipeId, alarms))
        pending = pending.filter { $0.key.recipeId != recipeId }
        for alarm in alarms { pending[Key(recipeId: alarm.recipeId, step: alarm.step)] = alarm }
    }
}
