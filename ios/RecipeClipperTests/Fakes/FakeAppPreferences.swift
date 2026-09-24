@testable import RecipeClipper

/// Plain vars: enough to prove a ViewModel writes a choice through, which is what makes these
/// global defaults rather than per-recipe state.
final class FakeAppPreferences: AppPreferences {
    var unitSystem: UnitSystem
    var convertLiquids: Bool
    var temperatureUnit: TemperatureUnit
    var darkWhileCooking: Bool

    init(
        unitSystem: UnitSystem = .asWritten,
        convertLiquids: Bool = false,
        temperatureUnit: TemperatureUnit = .asWritten,
        darkWhileCooking: Bool = false
    ) {
        self.unitSystem = unitSystem
        self.convertLiquids = convertLiquids
        self.temperatureUnit = temperatureUnit
        self.darkWhileCooking = darkWhileCooking
    }
}
