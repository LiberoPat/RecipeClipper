import Combine
import Foundation
import WidgetKit

/// Keeps the home-screen widgets (#309) current: the widget extension reads the App Group
/// database itself, so the app only asks WidgetKit to redraw when the plan, the list or the
/// `widgets` flag changes (Android's `HomeWidgets.start`). The flag is mirrored into the App
/// Group suite for the extension, which never sees the flags. The live app only.
@MainActor
final class WidgetRefresher {
    private let switchStore: DefaultsWidgetsSwitch
    private var subscription: AnyCancellable?

    init(switchStore: DefaultsWidgetsSwitch) {
        self.switchStore = switchStore
    }

    /// Follows today's plan (a day either side, so midnight is covered while the app runs) and
    /// the list, redrawing at most twice a second.
    func start(mealPlan: MealPlanRepository, groceries: GroceryRepository, today: Int64) {
        subscription = Publishers.CombineLatest(
            mealPlan.observeDays(start: today - 1, end: today + 1).removeDuplicates(),
            groceries.observeItems().removeDuplicates()
        )
        .debounce(for: .milliseconds(500), scheduler: DispatchQueue.main)
        .sink { _ in WidgetCenter.shared.reloadAllTimelines() }
    }

    /// The flag changed (or the app started): tell the extension, and redraw.
    func flag(on: Bool) {
        switchStore.store(on)
        WidgetCenter.shared.reloadAllTimelines()
    }
}
