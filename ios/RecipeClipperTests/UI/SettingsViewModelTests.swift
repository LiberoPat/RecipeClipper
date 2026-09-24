import XCTest
@testable import RecipeClipper

@MainActor
final class SettingsViewModelTests: XCTestCase {

    func testStateIsSeededFromPreferencesOnConstruction() {
        let preferences = FakeAppPreferences(
            unitSystem: .metric, convertLiquids: true, temperatureUnit: .celsius, darkWhileCooking: true
        )
        let vm = SettingsViewModel(preferences: preferences)

        XCTAssertEqual(vm.uiState.unitSystem, .metric)
        XCTAssertTrue(vm.uiState.convertLiquids)
        XCTAssertEqual(vm.uiState.temperatureUnit, .celsius)
        XCTAssertTrue(vm.uiState.darkWhileCooking)
    }

    func testDefaultsMatchAppPreferencesDefaults() {
        let vm = SettingsViewModel(preferences: FakeAppPreferences())

        XCTAssertEqual(vm.uiState, SettingsUiState())
        XCTAssertEqual(vm.uiState.unitSystem, .asWritten)
        XCTAssertFalse(vm.uiState.convertLiquids)
        XCTAssertEqual(vm.uiState.temperatureUnit, .asWritten)
        XCTAssertFalse(vm.uiState.darkWhileCooking)
    }

    func testOnUnitSystemChangeWritesThroughAndUpdatesState() {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(preferences: preferences)

        vm.onUnitSystemChange(.grams)

        XCTAssertEqual(vm.uiState.unitSystem, .grams)
        XCTAssertEqual(preferences.unitSystem, .grams)
    }

    func testOnConvertLiquidsChangeWritesThroughAndUpdatesState() {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(preferences: preferences)

        vm.onConvertLiquidsChange(true)

        XCTAssertTrue(vm.uiState.convertLiquids)
        XCTAssertTrue(preferences.convertLiquids)
    }

    func testOnTemperatureUnitChangeWritesThroughIndependentlyOfUnitSystem() {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(preferences: preferences)

        vm.onTemperatureUnitChange(.fahrenheit)

        XCTAssertEqual(vm.uiState.temperatureUnit, .fahrenheit)
        XCTAssertEqual(preferences.temperatureUnit, .fahrenheit)
        XCTAssertEqual(vm.uiState.unitSystem, .asWritten)
    }

    func testOnDarkWhileCookingChangeWritesThroughAndUpdatesState() {
        let preferences = FakeAppPreferences()
        let vm = SettingsViewModel(preferences: preferences)

        vm.onDarkWhileCookingChange(true)

        XCTAssertTrue(vm.uiState.darkWhileCooking)
        XCTAssertTrue(preferences.darkWhileCooking)
    }

    func testConvertLiquidsIsOfferedOnlyForGramsAndOunces() {
        let vm = SettingsViewModel(preferences: FakeAppPreferences())
        let offered = UnitSystem.allCases.filter { system in
            vm.onUnitSystemChange(system)
            return vm.showsConvertLiquids
        }
        XCTAssertEqual(offered, [.grams, .ounces])
    }
}
