import XCTest

/// Settings end to end — untested on Android (docs/testing.md). An index of the sections with
/// their values, each opening its own page (#286). Exclusive choices are radio rows (one
/// selected at a time), independent toggles are switches, and "Also convert liquids" only
/// exists for Ounces.
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

    /// The index row's value (#286): its label is "Name, value".
    private func assertValue(_ section: String, _ value: String, _ what: String,
                             file: StaticString = #filePath, line: UInt = #line) {
        requireState(settingsRow(section), "label ENDSWITH \(quoted(", \(value)"))", what, file: file, line: line)
    }

    private func quoted(_ text: String) -> String { "'\(text)'" }

    func testTheIndexShowsEachSectionWithItsValue() {
        launch()
        openSettings()

        assertValue("units", "As written", "Units")
        assertValue("oven-temperature", "As written", "Oven temperature")
        // Dark mode is the system's call; Appearance's one choice is cook mode's.
        assertValue("appearance", "System theme", "Appearance")
        assertValue("steps", "Amounts off", "Steps")
        assertValue("pantry", "Reminders off", "Pantry")
        require(settingsRow("your-recipes"), "Your recipes")
        requireState(settingsRow("help"), "label == 'Help'", "Help, with no value")
        assertAbsent(settingsRow("unlimited"), "Unlimited recipes (the free tier is off)")
    }

    /// Seven taps on the version at the foot of the index open Developer settings (#87).
    func testSevenTapsOnTheVersionOpenDeveloperSettings() {
        launch()
        openSettings()
        let version = require(app.descendants(matching: .any)["settings.version"], "the version")
        for _ in 0..<6 { version.tap() }
        assertAbsent(text("Developer settings"), "Developer settings after six taps")
        version.tap()
        require(text("Developer settings"), "Developer settings after the seventh")
    }

    func testDefaultsAreAsWrittenWithNoLiquidsToggle() {
        launch()
        openSettings("units")

        assertSelected(unitAsWritten, true, "units As written")
        assertSelected(option("Metric"), false, "Metric")
        assertAbsent(liquids, "the liquids toggle")
        assertAbsent(option("Grams"), "a Grams option (dropped in #17)")
        back()
        settingsRow("oven-temperature").tap()
        assertSelected(temperatureAsWritten, true, "temperature As written")
        back()
        settingsRow("appearance").tap()
        requireState(require(darkWhileCooking), "value == '0'", "Dark while cooking off by default")
    }

    func testChoosingAUnitSelectsOnlyThatOne() {
        launch()
        openSettings("units")

        option("Metric").tap()

        assertSelected(option("Metric"), true, "Metric")
        assertSelected(unitAsWritten, false, "As written")
        assertSelected(option("Ounces"), false, "Ounces")
        // The index follows; temperature is independent of units.
        back()
        assertValue("units", "Metric", "the index's Units")
        assertValue("oven-temperature", "As written", "the index's Oven temperature")
    }

    func testTheLiquidsToggleAppearsOnlyForOunces() {
        launch()
        openSettings("units")

        option("Ounces").tap()
        require(liquids, "liquids toggle for Ounces")

        option("Metric").tap()
        requireGone(liquids, "liquids toggle for Metric")

        option("Ounces").tap()
        require(liquids)
        unitAsWritten.tap()
        requireGone(liquids, "liquids toggle for As written")
    }

    func testTemperatureIsItsOwnExclusiveChoice() {
        launch()
        openSettings("oven-temperature")

        option("Celsius (°C)").tap()
        assertSelected(option("Celsius (°C)"), true, "Celsius")
        assertSelected(temperatureAsWritten, false, "temperature As written")

        option("Fahrenheit (°F)").tap()
        assertSelected(option("Fahrenheit (°F)"), true, "Fahrenheit")
        assertSelected(option("Celsius (°C)"), false, "Celsius")
        // Units untouched.
        back()
        assertValue("oven-temperature", "Fahrenheit (°F)", "the index's Oven temperature")
        assertValue("units", "As written", "the index's Units")
    }

    /// Settings writes through: leave a page and come back (a fresh ViewModel), then relaunch.
    func testChoicesPersistAcrossVisitsAndRelaunch() {
        launch()
        openSettings("units")
        option("Ounces").tap()
        flip(require(liquids))
        requireState(liquids, "value == '1'", "liquids on")
        back()
        settingsRow("oven-temperature").tap()
        option("Celsius (°C)").tap()
        back()
        settingsRow("appearance").tap()
        flip(require(darkWhileCooking))
        requireState(darkWhileCooking, "value == '1'", "dark while cooking on")
        back()

        assertValue("units", "Ounces", "the index's Units")
        assertValue("oven-temperature", "Celsius (°C)", "the index's Oven temperature")
        assertValue("appearance", "Dark while cooking", "the index's Appearance")
        settingsRow("units").tap()
        assertSelected(option("Ounces"), true, "Ounces after revisiting")
        requireState(liquids, "value == '1'", "liquids after revisiting")

        app.terminate()
        launch(keepPrefs: true)
        openSettings()
        assertValue("units", "Ounces", "Units after relaunch")
        assertValue("oven-temperature", "Celsius (°C)", "Oven temperature after relaunch")
        assertValue("appearance", "Dark while cooking", "Appearance after relaunch")
        settingsRow("units").tap()
        requireState(require(liquids), "value == '1'", "liquids after relaunch")
    }

    /// Units are a global default: the recipe screen's menu shows what Settings chose.
    func testTheRecipeScreenPicksUpTheUnitChosenInSettings() {
        launch()
        openSettings("units")
        option("Metric").tap()
        assertSelected(option("Metric"), true, "Metric")
        back() // to the index
        back() // to Home

        openRecipe("Banana Bread")
        requireState(app.buttons["Change units"], "value == 'Metric'", "the units control reading Metric")
    }
}

