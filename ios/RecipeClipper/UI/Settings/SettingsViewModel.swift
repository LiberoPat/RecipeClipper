import Combine
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
/// app-wide defaults. State is seeded synchronously so the first frame is right, then kept in
/// step with `preferences.settings`; each setter also updates it at once, alongside the
/// write, rather than waiting for the publisher to echo it back.
@MainActor
@Observable
final class SettingsViewModel {
    private(set) var uiState: SettingsUiState
    @ObservationIgnored private let preferences: AppPreferences
    @ObservationIgnored private var settingsSubscription: AnyCancellable?

    init(preferences: AppPreferences) {
        self.preferences = preferences
        uiState = Self.uiState(preferences.current)
        settingsSubscription = preferences.settings
            .receive(on: DispatchQueue.main)
            .sink { [weak self] settings in self?.uiState = Self.uiState(settings) }
    }

    private static func uiState(_ settings: AppSettings) -> SettingsUiState {
        SettingsUiState(
            unitSystem: settings.unitSystem,
            convertLiquids: settings.convertLiquids,
            temperatureUnit: settings.temperatureUnit,
            darkWhileCooking: settings.darkWhileCooking
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
