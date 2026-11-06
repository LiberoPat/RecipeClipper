import Combine
import Foundation
import WidgetKit

/// Keeps the home-screen widgets (#309) current: the widget extension reads the App Group
/// database itself, so the app only asks WidgetKit to redraw when the plan, the meal types, the
/// list or the `widgets` flag changes (Android's `HomeWidgets.start`). The flag is mirrored
/// into the App Group suite for the extension, which never sees the flags. The live app only.
@MainActor
final class WidgetRefresher {
    private let switchStore: DefaultsWidgetsSwitch
    private let pause: DispatchQueue.SchedulerTimeType.Stride
    private let reload: () -> Void
    private var subscription: AnyCancellable?

    /// `reload` asks WidgetKit for new timelines, at most once per `pause` (twice a second).
    init(
        switchStore: DefaultsWidgetsSwitch,
        pause: DispatchQueue.SchedulerTimeType.Stride = .milliseconds(500),
        reload: @escaping () -> Void = { WidgetCenter.shared.reloadAllTimelines() }
    ) {
        self.switchStore = switchStore
        self.pause = pause
        self.reload = reload
    }

    /// Follows the plan from the day before `today` on, **with no last day** (#329): the app
    /// can stay open, or come back, long after the day it started on, and a window around that
    /// day stopped seeing today's plan two days later. Days only go forward, so one open-ended
    /// window needs no re-anchoring, and it covers tomorrow's meals, which Tonight's timeline
    /// already holds. Also the meal types (a rename shows in Tonight) and the list.
    func start(mealPlan: MealPlanRepository, groceries: GroceryRepository, today: Int64) {
        subscription = Publishers.CombineLatest3(
            mealPlan.observeDays(start: today - 1, end: .max).removeDuplicates(),
            mealPlan.observeMealTypes().removeDuplicates(),
            groceries.observeItems().removeDuplicates()
        )
        .debounce(for: pause, scheduler: DispatchQueue.main)
        .sink { [reload] _ in reload() }
    }

    /// The flag changed (or the app started): tell the extension, and redraw.
    func flag(on: Bool) {
        switchStore.store(on)
        reload()
    }
}
