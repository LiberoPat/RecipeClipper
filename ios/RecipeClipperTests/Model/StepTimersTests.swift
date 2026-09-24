import XCTest
@testable import RecipeClipper

/// Port of Android's StepTimersTest.
final class StepTimersTests: XCTestCase {

    func testAStatedDurationBecomesATimer() {
        XCTAssertEqual(1200, StepTimers.parse("Simmer for 20 minutes."))
        XCTAssertEqual(30, StepTimers.parse("Stir for 30 seconds."))
        XCTAssertEqual(7200, StepTimers.parse("Roast for 2 hours."))
        XCTAssertEqual(600, StepTimers.parse("Rest for 10 mins."))
    }

    func testARangeUsesItsLowerBound() {
        XCTAssertEqual(1500, StepTimers.parse("Bake 25 to 30 minutes."))
        XCTAssertEqual(3600, StepTimers.parse("Chill for 1-2 hours."))
    }

    func testFractionsAndCompoundDurationsAddUp() {
        XCTAssertEqual(5400, StepTimers.parse("Cook for 1 1/2 hours."))
        XCTAssertEqual(5400, StepTimers.parse("Braise for 1 hour 30 minutes."))
        XCTAssertEqual(150, StepTimers.parse("Boil 2 minutes and 30 seconds."))
    }

    func testAnAdjectiveFormStillCounts() {
        XCTAssertEqual(1200, StepTimers.parse("Let it go for a 20-minute simmer."))
    }

    func testOnlyTheFirstDurationInAStepIsUsed() {
        XCTAssertEqual(300, StepTimers.parse("Sear 5 minutes per side, then rest 10 minutes."))
    }

    func testStepsWithoutAStatedTimeGetNoTimer() {
        XCTAssertNil(StepTimers.parse("Whisk until smooth."))
        XCTAssertNil(StepTimers.parse("Heat the oven to 350°F."))
        XCTAssertNil(StepTimers.parse("Add 2 cups of flour and mix."))
        XCTAssertNil(StepTimers.parse("Mince the garlic finely."))
        XCTAssertNil(StepTimers.parse(""))
    }

    func testClockFormatting() {
        XCTAssertEqual("20:00", StepTimers.clock(1200))
        XCTAssertEqual("0:05", StepTimers.clock(5))
        XCTAssertEqual("1:05:00", StepTimers.clock(3900))
        XCTAssertEqual("0:00", StepTimers.clock(-3))
    }

    func testButtonLabelWording() {
        XCTAssertEqual("20 min", StepTimers.label(1200))
        XCTAssertEqual("1 hr 30 min", StepTimers.label(5400))
        XCTAssertEqual("2 hr", StepTimers.label(7200))
        XCTAssertEqual("45 sec", StepTimers.label(45))
        XCTAssertEqual("2 min 30 sec", StepTimers.label(150))
    }

    func testADecimalCommaDurationIsReadAsADecimal() {
        XCTAssertEqual(5400, StepTimers.parse("Bake for 1,5 hours."))
        XCTAssertEqual(150, StepTimers.parse("Simmer 2,5 minutes"))
        XCTAssertNil(StepTimers.parse("Rest for 1,500 seconds"))
    }
}
