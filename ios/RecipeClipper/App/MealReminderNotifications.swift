import Combine
import Foundation
import Observation
import UserNotifications

/// Keeps the scheduled evening and Tonight reminders (#397) in step with the plan, the settings
/// and the flag. Started once by the live container; each change replaces every pending reminder
/// (Android's `MealReminderCoordinator`, which arms one alarm and builds the reminder when it
/// fires: nothing runs when a notification fires here, so each carries its words as planned, and
/// any change to the plan replaces them). Coming back to the foreground plans again from today:
/// the share extension saves in its own process (a re-shared recipe may have new steps), and a
/// pending list planned days ago would still start from the day it was planned.
@MainActor
final class MealReminderCoordinator {
    private let mealPlan: MealPlanRepository
    private let recipes: RecipeRepository
    private let preferences: AppPreferences
    private let flags: FeatureFlags
    private let scheduler: MealReminderScheduler
    private let clock: Clock
    private let zone: TimeZone?
    /// Pro (#413): without it only a reminder the cook switched on runs (`ProReminders`).
    private let pro: ProAccess
    private var settings: AppSettings
    private var meals: [PlannedMeal]?
    private var types: [MealType]?
    private var subscriptions: Set<AnyCancellable> = []
    private var planSubscription: AnyCancellable?

    /// The planning in flight, so a test can await it.
    private(set) var planning: Task<Void, Never>?

    /// `zone` nil is the phone's current one, read each time.
    init(
        mealPlan: MealPlanRepository, recipes: RecipeRepository, preferences: AppPreferences, flags: FeatureFlags,
        scheduler: MealReminderScheduler, clock: Clock, zone: TimeZone? = nil, pro: ProAccess = UngatedProAccess()
    ) {
        self.pro = pro
        self.mealPlan = mealPlan
        self.recipes = recipes
        self.preferences = preferences
        self.flags = flags
        self.scheduler = scheduler
        self.clock = clock
        self.zone = zone
        settings = preferences.current
    }

    func start() {
        preferences.settings
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] settings in
                self?.settings = settings
                self?.reschedule()
            }
            .store(in: &subscriptions)
        followFlag()
        followPro()
        subscribePlan()
    }

    /// The app came back to the foreground: plan again from today.
    func refresh() {
        subscribePlan()
    }

    private func followFlag() {
        _ = withObservationTracking { flags.isOn(.eveningReminders) } onChange: { [weak self] in
            Task { @MainActor in
                self?.reschedule()
                self?.followFlag()
            }
        }
    }

    /// A purchase, a refund or the Developer override (#413) plans again at once.
    private func followPro() {
        _ = withObservationTracking { pro.state } onChange: { [weak self] in
            Task { @MainActor in
                self?.reschedule()
                self?.followPro()
            }
        }
    }

    // Today's meals and the next eight days': the furthest a reminder reaches is the day after
    // the last of `MealReminders.maxDays` evenings.
    private func subscribePlan() {
        let today = self.today
        planSubscription = Publishers.CombineLatest(
            mealPlan.observeDays(start: today, end: today + Int64(MealReminders.maxDays) + 2),
            mealPlan.observeMealTypes()
        )
        .receive(on: DispatchQueue.main)
        .sink { [weak self] meals, types in
            self?.meals = meals
            self?.types = types
            self?.reschedule()
        }
    }

    private var offset: Int64 {
        let now = clock.now()
        return Int64((zone ?? .current).secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(now) / 1000))) * 1000
    }

    private var today: Int64 { PlanDays.epochDay(millis: clock.now(), offsetMillis: offset) }

    /// Plans again from what is known now.
    func reschedule() {
        planning?.cancel()
        guard flags.isOn(.eveningReminders) else {
            scheduler.replaceAll([])
            return
        }
        guard let meals, let types else { return }
        let now = clock.now()
        let today = self.today
        let minute = Int((now + offset - today * PlanDays.millisPerDay) / 60_000)
        let settings = self.settings
        let pro = self.pro.state
        let evening = ProReminders.runs(on: settings.eveningReminder, chosen: settings.eveningReminderChosen, pro: pro)
        let tonight = ProReminders.runs(on: settings.tonightReminder, chosen: true, pro: pro)
        planning = Task { [recipes, scheduler] in
            var scheduled: [ScheduledMealReminder] = []
            if evening {
                // The steps of every recipe the evenings name, read once, in the recipe's own language.
                let first = MealReminders.firstDay(today: today, minuteOfDay: minute, atMinute: settings.eveningReminderMinute)
                let ids = MealReminders.recipeIds((first..<(first + Int64(MealReminders.maxDays))).flatMap {
                    MealReminders.meals(.evening, fireDay: $0, meals: meals, types: types)
                })
                let ahead: [Int64: AheadStep] = ids.isEmpty ? [:] : Dictionary(
                    await recipes.byIds(ids).compactMap { recipe in
                        AheadSteps.find(recipe.instructions, words: LanguageWords.forRecipe(recipe)).map { (recipe.id, $0) }
                    },
                    uniquingKeysWith: { first, _ in first }
                )
                scheduled += MealReminders.plan(
                    .evening, atMinute: settings.eveningReminderMinute, today: today, minuteOfDay: minute,
                    meals: meals, types: types, aheadFor: { ahead[$0] }
                ).map { ScheduledMealReminder(reminder: $0, minute: settings.eveningReminderMinute) }
            }
            if tonight {
                scheduled += MealReminders.plan(
                    .tonight, atMinute: settings.tonightReminderMinute, today: today, minuteOfDay: minute,
                    meals: meals, types: types
                ).map { ScheduledMealReminder(reminder: $0, minute: settings.tonightReminderMinute) }
            }
            guard !Task.isCancelled else { return }
            scheduler.replaceAll(scheduled.sorted { ($0.reminder.day, $0.minute) < ($1.reminder.day, $1.minute) })
        }
    }
}

/// The real `MealReminderScheduler`: one pending local notification per reminder, at its time on
/// the calendar (`UNCalendarNotificationTrigger`), replaced wholesale on every change. Each
/// carries its words as planned. Never asks for permission: Settings asks when a switch goes on.
@MainActor
final class NotificationMealReminderScheduler: MealReminderScheduler {
    static let prefix = "meals."
    private let center: UNUserNotificationCenter
    private var last: Task<Void, Never>?

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
    }

    func replaceAll(_ reminders: [ScheduledMealReminder]) {
        let previous = last
        last = Task { [center] in
            await previous?.value
            let stale = await center.pendingNotificationRequests()
                .map(\.identifier)
                .filter { $0.hasPrefix(Self.prefix) }
            center.removePendingNotificationRequests(withIdentifiers: stale)
            guard !reminders.isEmpty else { return }
            let status = await center.notificationSettings().authorizationStatus
            guard status == .authorized || status == .provisional else { return }
            for scheduled in reminders {
                let reminder = scheduled.reminder
                let content = UNMutableNotificationContent()
                content.title = Strings.mealReminderTitle(reminder)
                if let body = Strings.mealReminderBody(reminder) { content.body = body }
                content.sound = .default
                content.userInfo = Self.userInfo(reminder)
                var date = DateComponents()
                var gregorian = Calendar(identifier: .gregorian)
                gregorian.timeZone = TimeZone(identifier: "UTC")!
                let parts = gregorian.dateComponents([.year, .month, .day], from: PlanDays.utcDate(reminder.day))
                date.year = parts.year
                date.month = parts.month
                date.day = parts.day
                date.hour = scheduled.minute / 60
                date.minute = scheduled.minute % 60
                let request = UNNotificationRequest(
                    identifier: "\(Self.prefix)\(reminder.kind == .evening ? "evening" : "tonight").\(reminder.day)",
                    content: content,
                    trigger: UNCalendarNotificationTrigger(dateMatching: date, repeats: false)
                )
                try? await center.add(request)
            }
        }
    }

    /// What a tap opens: the first planned recipe on the Week's stack, or the Week tab when every
    /// meal is a note.
    static func userInfo(_ reminder: MealReminder) -> [AnyHashable: Any] {
        if let id = reminder.recipeId { return [NotificationRouter.openWeekRecipeKey: NSNumber(value: id)] }
        return [NotificationRouter.openTabKey: AppTab.week.rawValue]
    }
}

/// Schedules nothing: unit tests and UI-test seeding.
final class NoOpMealReminderScheduler: MealReminderScheduler {
    nonisolated init() {}
    func replaceAll(_ reminders: [ScheduledMealReminder]) {}
}
