import Foundation
import UserNotifications

/// The real `TimerAlarmScheduler`: one local notification per running step timer, due at its
/// deadline, so the alert sounds with the app in the background, suspended or killed.
///
/// Authorisation is requested on the first `schedule` (the first timer start); iOS shows the
/// prompt only while the answer is undetermined, so later calls are silent. Refused, the timer
/// still runs and the in-app beep still sounds while the recipe is open.
///
/// Every operation is chained behind the previous one, because the notification center is
/// asynchronous: a `replaceAll` on opening a recipe must not remove a timer the cook starts a
/// moment later, and a cancel must not be overtaken by the add it cancels.
@MainActor
final class NotificationTimerScheduler: TimerAlarmScheduler {
    private let center: UNUserNotificationCenter
    private let clock: Clock
    private var last: Task<Void, Never>?

    init(center: UNUserNotificationCenter = .current(), clock: Clock) {
        self.center = center
        self.clock = clock
    }

    static func identifier(recipeId: Int64, step: Int) -> String { "timer.\(recipeId).\(step)" }
    private static func prefix(_ recipeId: Int64) -> String { "timer.\(recipeId)." }

    func schedule(_ alarm: StepAlarm) {
        enqueue { [center, clock] in
            await Self.add(alarm, center: center, clock: clock)
        }
    }

    func cancel(recipeId: Int64, step: Int) {
        enqueue { [center] in
            center.removePendingNotificationRequests(
                withIdentifiers: [Self.identifier(recipeId: recipeId, step: step)]
            )
        }
    }

    func replaceAll(recipeId: Int64, with alarms: [StepAlarm]) {
        enqueue { [center, clock] in
            let prefix = Self.prefix(recipeId)
            let stale = await center.pendingNotificationRequests()
                .map(\.identifier)
                .filter { $0.hasPrefix(prefix) }
            center.removePendingNotificationRequests(withIdentifiers: stale)
            for alarm in alarms { await Self.add(alarm, center: center, clock: clock) }
        }
    }

    private func enqueue(_ operation: @escaping @MainActor () async -> Void) {
        let previous = last
        last = Task { @MainActor in
            await previous?.value
            await operation()
        }
    }

    private static func add(_ alarm: StepAlarm, center: UNUserNotificationCenter, clock: Clock) async {
        guard (try? await center.requestAuthorization(options: [.alert, .sound])) == true else { return }
        let content = UNMutableNotificationContent()
        content.title = Strings.timerNotificationTitle(step: alarm.step + 1)
        content.body = alarm.recipeTitle
        content.sound = .default
        content.userInfo = [NotificationRouter.recipeIdKey: NSNumber(value: alarm.recipeId)]
        let seconds = max(1, Double(alarm.endsAt - clock.now()) / 1000)
        let request = UNNotificationRequest(
            identifier: identifier(recipeId: alarm.recipeId, step: alarm.step),
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)
        )
        try? await center.add(request)
    }
}

/// Schedules nothing. Used under XCTest and UI-test seeding, so a test run never raises the
/// notification prompt.
final class NoOpTimerAlarmScheduler: TimerAlarmScheduler {
    // Nonisolated so it can be a default argument (evaluated outside the main actor).
    nonisolated init() {}

    func schedule(_ alarm: StepAlarm) {}
    func cancel(recipeId: Int64, step: Int) {}
    func replaceAll(recipeId: Int64, with alarms: [StepAlarm]) {}
}
