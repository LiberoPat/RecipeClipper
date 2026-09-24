import XCTest

/// Settings end to end — untested on any surface on Android (CLAUDE.md). Exclusive choices
/// are radio rows (one selected at a time), independent toggles are switches, and "Also
/// convert liquids" only exists for Grams and Ounces.
final class SettingsUITests: RecipeUITestCase {

    /// A radio row; its label is "Title, description".
    private func option(_ title: String) -> XCUIElement { row(title) }

    /// Unit and temperature rows both have an "As written"; the unit one comes first.
    private var unitAsWritten: XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'As written, Exactly the units'")).firstMatch
    }

    private var temperatureAsWritten: XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'As written, Exactly the temperature'")).firstMatch
    }

    private var liquids: XCUIElement {
        app.switches.matching(NSPredicate(format: "label BEGINSWITH 'Also convert liquids'")).firstMatch
    }

    private var darkWhileCooking: XCUIElement {
        app.switches.matching(NSPredicate(format: "label BEGINSWITH 'Dark while cooking'")).firstMatch
    }

    private func assertSelected(_ element: XCUIElement, _ selected: Bool, _ what: String,
                                file: StaticString = #filePath, line: UInt = #line) {
        requireState(element, "isSelected == \(selected ? "true" : "false")", what, file: file, line: line)
    }

    /// Flips a SwiftUI Toggle. Tapping the element's centre can land on its label; the switch
    /// itself sits at the trailing edge.
    private func flip(_ toggle: XCUIElement) {
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    }

    func testDefaultsAreAsWrittenWithNoLiquidsToggle() {
        launch()
        openSettings()

        assertSelected(unitAsWritten, true, "units As written")
        assertSelected(option("Metric"), false, "Metric")
        assertSelected(temperatureAsWritten, true, "temperature As written")
        assertAbsent(liquids, "the liquids toggle")
        requireState(darkWhileCooking, "value == '0'", "Dark while cooking off by default")
    }

    func testChoosingAUnitSelectsOnlyThatOne() {
        launch()
        openSettings()

        option("Grams").tap()

        assertSelected(option("Grams"), true, "Grams")
        assertSelected(unitAsWritten, false, "As written")
        assertSelected(option("Ounces"), false, "Ounces")
        assertSelected(option("Metric"), false, "Metric")
        // Temperature is independent of units.
        assertSelected(temperatureAsWritten, true, "temperature As written")
    }

    func testTheLiquidsToggleAppearsOnlyForGramsAndOunces() {
        launch()
        openSettings()

        option("Grams").tap()
        require(liquids, "liquids toggle for Grams")

        option("Ounces").tap()
        require(liquids, "liquids toggle for Ounces")

        option("Metric").tap()
        requireGone(liquids, "liquids toggle for Metric")

        option("Grams").tap()
        require(liquids)
        unitAsWritten.tap()
        requireGone(liquids, "liquids toggle for As written")
    }

    func testTemperatureIsItsOwnExclusiveChoice() {
        launch()
        openSettings()

        option("Celsius (°C)").tap()
        assertSelected(option("Celsius (°C)"), true, "Celsius")
        assertSelected(temperatureAsWritten, false, "temperature As written")

        option("Fahrenheit (°F)").tap()
        assertSelected(option("Fahrenheit (°F)"), true, "Fahrenheit")
        assertSelected(option("Celsius (°C)"), false, "Celsius")
        // Units untouched.
        assertSelected(unitAsWritten, true, "units As written")
    }

    /// Settings writes through: leave and come back (a fresh ViewModel), then relaunch.
    func testChoicesPersistAcrossVisitsAndRelaunch() {
        launch()
        openSettings()

        option("Ounces").tap()
        option("Celsius (°C)").tap()
        flip(require(liquids))
        requireState(liquids, "value == '1'", "liquids on")
        flip(darkWhileCooking)
        requireState(darkWhileCooking, "value == '1'", "dark while cooking on")

        back()
        openSettings()
        assertSelected(option("Ounces"), true, "Ounces after revisiting")
        assertSelected(option("Celsius (°C)"), true, "Celsius after revisiting")
        requireState(liquids, "value == '1'", "liquids after revisiting")
        requireState(darkWhileCooking, "value == '1'", "dark while cooking after revisiting")

        app.terminate()
        launch(keepPrefs: true)
        openSettings()
        assertSelected(option("Ounces"), true, "Ounces after relaunch")
        assertSelected(option("Celsius (°C)"), true, "Celsius after relaunch")
        requireState(liquids, "value == '1'", "liquids after relaunch")
    }

    /// Units are a global default: the recipe screen's menu shows what Settings chose.
    func testTheRecipeScreenPicksUpTheUnitChosenInSettings() {
        launch()
        openSettings()
        option("Metric").tap()
        assertSelected(option("Metric"), true, "Metric")
        back()

        openRecipe("Banana Bread")
        requireState(app.buttons["Change units"], "value == 'Metric'", "the units control reading Metric")
    }
}
