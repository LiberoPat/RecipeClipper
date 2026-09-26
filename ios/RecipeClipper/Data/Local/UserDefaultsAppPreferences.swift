import Combine
import Foundation

/// The real, UserDefaults-backed AppPreferences (Android's SharedPrefsAppPreferences).
/// Deliberately not in SQLite: these are settings, not recipe data. Enums are stored by
/// rawValue, which equals the Android enum name; an unknown stored value reads as the default.
final class UserDefaultsAppPreferences: AppPreferences {
    private enum Key {
        static let unitSystem = "unit_system"
        static let convertLiquids = "convert_liquids"
        static let temperatureUnit = "temperature_unit"
        static let darkWhileCooking = "dark_while_cooking"
        static let expiryReminders = "expiry_reminders"
        static let chefMode = "chef_mode"
        static let amountsInSteps = "amounts_in_steps"
        static let recipeSort = "recipe_sort"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var unitSystem: UnitSystem {
        // A stored GRAMS (the option #17 removed) reads as metric.
        get { UnitSystem(storedName: defaults.string(forKey: Key.unitSystem)) }
        set { defaults.set(newValue.rawValue, forKey: Key.unitSystem) }
    }

    var convertLiquids: Bool {
        get { defaults.bool(forKey: Key.convertLiquids) }   // false when unset
        set { defaults.set(newValue, forKey: Key.convertLiquids) }
    }

    var temperatureUnit: TemperatureUnit {
        get { defaults.string(forKey: Key.temperatureUnit).flatMap(TemperatureUnit.init(rawValue:)) ?? .asWritten }
        set { defaults.set(newValue.rawValue, forKey: Key.temperatureUnit) }
    }

    var darkWhileCooking: Bool {
        get { defaults.bool(forKey: Key.darkWhileCooking) }
        set { defaults.set(newValue, forKey: Key.darkWhileCooking) }
    }

    var expiryReminders: Bool {
        get { defaults.bool(forKey: Key.expiryReminders) }
        set { defaults.set(newValue, forKey: Key.expiryReminders) }
    }

    var chefMode: Bool {
        get { defaults.bool(forKey: Key.chefMode) }
        set { defaults.set(newValue, forKey: Key.chefMode) }
    }

    var amountsInSteps: Bool {
        get { defaults.bool(forKey: Key.amountsInSteps) }
        set { defaults.set(newValue, forKey: Key.amountsInSteps) }
    }

    var recipeSort: RecipeSort {
        get { RecipeSort(storedName: defaults.string(forKey: Key.recipeSort)) }
        set { defaults.set(newValue.rawValue, forKey: Key.recipeSort) }
    }

    /// Over `UserDefaults.didChangeNotification` (Android: the SharedPreferences change
    /// listener). The notification says only that something changed, and is also posted for
    /// other suites, so each one re-reads every value and repeats are dropped: the
    /// publisher always carries a whole, consistent snapshot. Not filtered by `object`, so a
    /// write through another UserDefaults instance on the same suite is seen too.
    var settings: AnyPublisher<AppSettings, Never> {
        NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)
            .map { [weak self] _ in self?.current }
            .compactMap { $0 }
            .prepend(current)
            .removeDuplicates()
            .eraseToAnyPublisher()
    }
}
