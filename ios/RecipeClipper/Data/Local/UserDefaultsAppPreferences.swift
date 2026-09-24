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
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var unitSystem: UnitSystem {
        get { defaults.string(forKey: Key.unitSystem).flatMap(UnitSystem.init(rawValue:)) ?? .asWritten }
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
}
