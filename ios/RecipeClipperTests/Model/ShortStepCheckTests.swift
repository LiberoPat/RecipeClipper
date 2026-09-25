import XCTest
@testable import RecipeClipper

/// Chef mode's gate (#100). The differential corpus's `Short` rows pin it to the Kotlin; these
/// name the rules.
final class ShortStepCheckTests: XCTestCase {
    private let en = LanguageWords.english

    func testAShorterStepWithTheSameNumbersPasses() {
        XCTAssertEqual(
            ShortStepCheck.accept("Bake for 25 to 30 minutes, until the top is golden.", "Bake 25–30 min until golden.", words: en),
            "Bake 25–30 min until golden."
        )
    }

    func testANumberThatIsNotInTheStepFails() {
        XCTAssertNil(ShortStepCheck.accept("Bake for 20 minutes, until the top is golden.", "Bake 25 min.", words: en))
    }

    func testAChangedOrDroppedTimeOrTemperatureFails() {
        XCTAssertNil(ShortStepCheck.accept("Microwave for 30 seconds, then stir well.", "Microwave 30 min, stir.", words: en))
        XCTAssertNil(ShortStepCheck.accept("Simmer for 20 minutes, stirring often so it doesn't catch.", "Simmer, stirring often.", words: en))
        XCTAssertNil(ShortStepCheck.accept("Roast at 200°C for 1 hour, turning halfway through.", "Roast at 200°F, 1 hr.", words: en))
    }

    func testItMustBeShorterAndThereMustBeWords() {
        XCTAssertNil(ShortStepCheck.accept("Stir well.", "Stir it well.", words: en))
        XCTAssertNil(ShortStepCheck.accept("Stir well until smooth.", "   ", words: en))
        XCTAssertNil(ShortStepCheck.accept("Stir well until smooth.", nil, words: en))
        XCTAssertNil(ShortStepCheck.accept("Stir well until smooth.", "Stir.", words: nil))
    }

    func testNumbersAreReadAsWritten() {
        XCTAssertEqual(ShortStepCheck.numbers("1,5 kg, 1 1/2 cups, 1 ½, ½, 10–12"), ["1,5", "1 1/2", "1½", "½", "10", "12"])
    }
}
