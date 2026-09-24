import Foundation
import Observation

/// Mirrors AppPreferences — the app's global defaults, not any one recipe's.
struct SettingsUiState: Equatable {
    var unitSystem: UnitSystem = .asWritten
    var convertLiquids = false
    var temperatureUnit: TemperatureUnit = .asWritten
    var darkWhileCooking = false
}

/// Injects AppPreferences directly rather than going through a repository: these are
/// app-wide defaults. Preferences are plain vars, so state is seeded once here and updated
/// alongside each write.
@MainActor
@Observable
final class SettingsViewModel {
    private(set) var uiState: SettingsUiState
    @ObservationIgnored private let preferences: AppPreferences

    init(preferences: AppPreferences) {
        self.preferences = preferences
        uiState = SettingsUiState(
            unitSystem: preferences.unitSystem,
            convertLiquids: preferences.convertLiquids,
            temperatureUnit: preferences.temperatureUnit,
            darkWhileCooking: preferences.darkWhileCooking
        )
    }

    func onUnitSystemChange(_ system: UnitSystem) {
        preferences.unitSystem = system
        uiState.unitSystem = system
    }

    func onConvertLiquidsChange(_ enabled: Bool) {
        preferences.convertLiquids = enabled
        uiState.convertLiquids = enabled
    }

    func onTemperatureUnitChange(_ unit: TemperatureUnit) {
        preferences.temperatureUnit = unit
        uiState.temperatureUnit = unit
    }

    func onDarkWhileCookingChange(_ enabled: Bool) {
        preferences.darkWhileCooking = enabled
        uiState.darkWhileCooking = enabled
    }

    /// "Also convert liquids" only means something for Grams and Ounces.
    var showsConvertLiquids: Bool {
        uiState.unitSystem == .grams || uiState.unitSystem == .ounces
    }
}
