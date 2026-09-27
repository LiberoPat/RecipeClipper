import XCTest
@testable import RecipeClipper

/// Port of Android's TemperatureConverterTest.
final class TemperatureConverterTests: XCTestCase {

    private func celsius(_ text: String) -> String { TemperatureConverter.convert(text, unit: .celsius) }
    private func fahrenheit(_ text: String) -> String { TemperatureConverter.convert(text, unit: .fahrenheit) }

    func testAsWrittenNeverChangesText() {
        XCTAssertEqual("Bake at 350°F", TemperatureConverter.convert("Bake at 350°F", unit: .asWritten))
    }

    func testFahrenheitOvensBecomeTheCelsiusNumbersRecipesUse() {
        XCTAssertEqual("Preheat the oven to 180°C.", celsius("Preheat the oven to 350°F."))
        XCTAssertEqual("Bake at 190°C for 20 minutes", celsius("Bake at 375 degrees F for 20 minutes"))
        XCTAssertEqual("Bake at 220°C", celsius("Bake at 425°F"))
        XCTAssertEqual("Bake at 200°C", celsius("Bake at 400F"))
        XCTAssertEqual("Bake at 160°C", celsius("Bake at 325° F"))
    }

    func testCelsiusOvensBecomeTheFahrenheitNumbersRecipesUse() {
        XCTAssertEqual("Preheat to 350°F", fahrenheit("Preheat to 180°C"))
        XCTAssertEqual("Preheat to 400°F", fahrenheit("Preheat to 200°C"))
        XCTAssertEqual("Preheat to 425°F", fahrenheit("Preheat to 220°C"))
        XCTAssertEqual("Preheat to 325°F", fahrenheit("Preheat to 160 degrees Celsius"))
    }

    func testCelsiusOptionConvertsIndependentlyOfUnitSystem() {
        XCTAssertEqual("Bake at 180°C", TemperatureConverter.convert("Bake at 350°F", unit: .celsius))
    }

    func testFoodAndDoughTemperaturesKeepDegreePrecision() {
        XCTAssertEqual("Cook to 74°C", celsius("Cook to 165°F"))
        XCTAssertEqual("Cook to 63°C", celsius("Cook to 145°F"))
        XCTAssertEqual("Use water at 43°C", celsius("Use water at 110°F"))
        XCTAssertEqual("Cook to 165°F", fahrenheit("Cook to 74°C"))
    }

    func testRangesConvertBothEnds() {
        XCTAssertEqual("Bake at 180-190°C", celsius("Bake at 350-375°F"))
        XCTAssertEqual("Bake at 180 to 190°C", celsius("Bake at 350 to 375°F"))
    }

    func testTextAlreadyInTheTargetScaleIsLeftAsWritten() {
        XCTAssertEqual("Bake at 350 F", fahrenheit("Bake at 350 F"))
        XCTAssertEqual("Bake at 180 degrees C", celsius("Bake at 180 degrees C"))
    }

    func testAPairCollapsesToTheHalfThatMatchesTheTarget() {
        XCTAssertEqual("Preheat to 180°C.", celsius("Preheat to 350°F (180°C)."))
        XCTAssertEqual("Preheat to 350°F.", fahrenheit("Preheat to 350°F (180°C)."))
        XCTAssertEqual("Preheat to 350°F.", fahrenheit("Preheat to 180°C/350°F."))
        XCTAssertEqual("Preheat to 180 degrees C.", celsius("Preheat to 350 degrees F (180 degrees C)."))
    }

    func testABareLetterWithAPlausibleTemperatureConverts() {
        XCTAssertEqual("Bake at 180°C", celsius("Bake at 350 F"))
        XCTAssertEqual("Bake at 350°F", fahrenheit("Bake at 180 C"))
        // A cup written "c." (#135) is never a temperature.
        XCTAssertEqual("Stir in 1/2 c. heavy cream", fahrenheit("Stir in 1/2 c. heavy cream"))
    }

    func testThingsThatAreNotTemperaturesAreLeftAlone() {
        XCTAssertEqual("Stir in 2 C flour", celsius("Stir in 2 C flour"))
        XCTAssertEqual("Stir in 20 C of sugar", celsius("Stir in 20 C of sugar"))
        XCTAssertEqual("Bake 25 to 30 minutes", celsius("Bake 25 to 30 minutes"))
        XCTAssertEqual("Whisk 1350F", celsius("Whisk 1350F"))
        XCTAssertEqual("Add 250 Flakes", celsius("Add 250 Flakes"))
    }

    func testSurroundingTextSuchAsFanIsKept() {
        XCTAssertEqual("Preheat to 350°F fan", fahrenheit("Preheat to 180°C fan"))
    }

    func testSeveralTemperaturesInOneStep() {
        XCTAssertEqual(
            "Start at 220°C, then lower to 180°C.",
            celsius("Start at 425°F, then lower to 350°F.")
        )
    }
}
