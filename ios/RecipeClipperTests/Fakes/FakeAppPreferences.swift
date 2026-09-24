import Combine
@testable import RecipeClipper

/// Holds its values in one CurrentValueSubject, so a write through any var re-emits on
/// `settings`, as the real UserDefaults notification does. A test that sets a value here
/// directly is Settings changing a default while another screen is open.
final class FakeAppPreferences: AppPreferences {
    private let subject: CurrentValueSubject<AppSettings, Never>

    init(
        unitSystem: UnitSystem = .asWritten,
        convertLiquids: Bool = false,
        temperatureUnit: TemperatureUnit = .asWritten,
        darkWhileCooking: Bool = false
    ) {
        subject = CurrentValueSubject(AppSettings(
            unitSystem: unitSystem,
            convertLiquids: convertLiquids,
            temperatureUnit: temperatureUnit,
            darkWhileCooking: darkWhileCooking
        ))
    }

    var settings: AnyPublisher<AppSettings, Never> {
        subject.removeDuplicates().eraseToAnyPublisher()
    }

    var unitSystem: UnitSystem {
        get { subject.value.unitSystem }
        set { subject.value.unitSystem = newValue }
    }

    var convertLiquids: Bool {
        get { subject.value.convertLiquids }
        set { subject.value.convertLiquids = newValue }
    }

    var temperatureUnit: TemperatureUnit {
        get { subject.value.temperatureUnit }
        set { subject.value.temperatureUnit = newValue }
    }

    var darkWhileCooking: Bool {
        get { subject.value.darkWhileCooking }
        set { subject.value.darkWhileCooking = newValue }
    }
}
