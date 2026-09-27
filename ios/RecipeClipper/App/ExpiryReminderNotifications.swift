import Combine
import Foundation
import Observation
import UserNotifications

/// Keeps the scheduled expiry reminders (#52) in step with the pantry, the setting and the
/// `mealPlan` flag (the pantry is invisible without it, so it reminds of nothing). Started once
/// by the live container; each change replaces every pending reminder (Android's
/// `ExpiryReminderCoordinator`). Coming back to the foreground plans again, so a pending list
/// planned days ago still starts from today.
@MainActor
final class ExpiryReminderCoordinator {
    private let pantry: PantryRepository
    private let preferences: AppPreferences
    private let flags: FeatureFlags
    private let scheduler: ExpiryReminderScheduler
    private let clock: Clock
    private let zone: TimeZone?
    private var items: [PantryItem]?
    private var settingOn = false
    private var subscriptions: Set<AnyCancellable> = []

    /// `zone` nil is the phone's current one, read each time.
    init(
        pantry: PantryRepository, preferences: AppPreferences, flags: FeatureFlags,
        scheduler: ExpiryReminderScheduler, clock: Clock, zone: TimeZone? = nil
    ) {
        self.pantry = pantry
        self.preferences = preferences
        self.flags = flags
        self.scheduler = scheduler
        self.clock = clock
        self.zone = zone
    }

    func start() {
        pantry.observeItems()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items in
                self?.items = items
                self?.reschedule()
            }
            .store(in: &subscriptions)
        preferences.settings
            .map(\.expiryReminders)
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] on in
                self?.settingOn = on
                self?.reschedule()
            }
            .store(in: &subscriptions)
        observeFlag()
    }

    /// Plans again from what is known now (the app came back to the foreground).
    func reschedule() {
        guard let items else { return }
        let on = settingOn && flags.isOn(.mealPlan)
        scheduler.replaceAll(on ? plan(items) : [])
    }

    private func plan(_ items: [PantryItem]) -> [ExpiryReminder] {
        let now = clock.now()
        let zone = zone ?? .current
        let offset = Int64(zone.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(now) / 1000))) * 1000
        let today = PlanDays.epochDay(millis: now, offsetMillis: offset)
        let minute = Int((now + offset - today * PlanDays.millisPerDay) / 60_000)
        return ExpiryReminders.plan(items, today: today, minuteOfDay: minute)
    }

    /// FeatureFlags is @Observable: re-plan whenever the flag flips, and keep watching.
    private func observeFlag() {
        withObservationTracking {
            _ = flags.isOn(.mealPlan)
        } onChange: { [weak self] in
            Task { @MainActor in
                self?.reschedule()
                self?.observeFlag()
            }
        }
    }
}

/// The real `ExpiryReminderScheduler`: one pending local notification per morning, at 9:00 on
/// the calendar (`UNCalendarNotificationTrigger`), replaced wholesale on every change. With no
/// receiver to re-read the pantry when it fires, each one carries its text as planned; any
/// change to the pantry replaces them, so what fires is what was last planned.
@MainActor
final class NotificationExpiryReminderScheduler: ExpiryReminderScheduler {
    static let prefix = "expiry."
    private let center: UNUserNotificationCenter
    private var last: Task<Void, Never>?

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    func replaceAll(_ reminders: [ExpiryReminder]) {
        let previous = last
        last = Task { [center] in
            await previous?.value
            let stale = await center.pendingNotificationRequests()
                .map(\.identifier)
                .filter { $0.hasPrefix(Self.prefix) }
            center.removePendingNotificationRequests(withIdentifiers: stale)
            guard !reminders.isEmpty else { return }
            // Never asks: Settings turned reminders on only once they were allowed.
            let status = await center.notificationSettings().authorizationStatus
            guard status == .authorized || status == .provisional else { return }
            for reminder in reminders {
                let content = UNMutableNotificationContent()
                content.title = Strings.expiryNotificationTitle
                content.body = Strings.expiryNotificationBody(reminder)
                content.sound = .default
                content.userInfo = [NotificationRouter.openTabKey: AppTab.pantry.rawValue]
                var date = DateComponents()
                let utc = PlanDays.utcDate(reminder.day)
                var gregorian = Calendar(identifier: .gregorian)
                gregorian.timeZone = TimeZone(identifier: "UTC")!
                let parts = gregorian.dateComponents([.year, .month, .day], from: utc)
                date.year = parts.year
                date.month = parts.month
                date.day = parts.day
                date.hour = ExpiryReminders.hour
                date.minute = 0
                let request = UNNotificationRequest(
                    identifier: "\(Self.prefix)\(reminder.day)",
                    content: content,
                    trigger: UNCalendarNotificationTrigger(dateMatching: date, repeats: false)
                )
                try? await center.add(request)
            }
        }
    }
}

/// Schedules nothing: unit tests and UI-test seeding.
final class NoOpExpiryReminderScheduler: ExpiryReminderScheduler {
    nonisolated init() {}
    func replaceAll(_ reminders: [ExpiryReminder]) {}
}

/// The system prompt, shown only while the user hasn't answered (iOS asks once).
final class SystemNotificationPermission: NotificationPermission {
    nonisolated init() {}

    func request() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) == true
    }
}

/// Answers without asking: UI tests, which must never meet the system prompt.
final class FixedNotificationPermission: NotificationPermission {
    private let granted: Bool
    nonisolated init(granted: Bool) { self.granted = granted }
    func request() async -> Bool { granted }
}
