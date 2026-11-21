import XCTest
@testable import RecipeClipper

/// Cook progress across a change of steps (#432). Android: `CookProgressTest`.
final class CookProgressTests: XCTestCase {
    private let timer = SavedTimer(totalSeconds: 600, remainingSeconds: 300, endsAt: 9_000)

    func testTheOldLastStepsNowBeingTheTipsKeepsTheProgress() {
        XCTAssertTrue(CookProgress.keptWhenTipsSplitOff(
            oldSteps: ["Bake.", "Cool.", "A tip."], newSteps: ["Bake.", "Cool."], newTips: ["A tip."]))
        XCTAssertTrue(CookProgress.keptWhenTipsSplitOff(
            oldSteps: ["Bake.", "A.", "B."], newSteps: ["Bake."], newTips: ["A.", "B."]))
    }

    func testAnyOtherChangeIsNotASplit() {
        // A real step changed too.
        XCTAssertFalse(CookProgress.keptWhenTipsSplitOff(
            oldSteps: ["Bake.", "Cool.", "A tip."], newSteps: ["Bake well.", "Cool."], newTips: ["A tip."]))
        // A tip that was never a step.
        XCTAssertFalse(CookProgress.keptWhenTipsSplitOff(
            oldSteps: ["Bake.", "Cool."], newSteps: ["Bake.", "Cool."], newTips: ["A tip."]))
        // The tips in another order.
        XCTAssertFalse(CookProgress.keptWhenTipsSplitOff(
            oldSteps: ["Bake.", "A.", "B."], newSteps: ["Bake."], newTips: ["B.", "A."]))
        // Compared exactly, as stored.
        XCTAssertFalse(CookProgress.keptWhenTipsSplitOff(
            oldSteps: ["Bake.", "a tip."], newSteps: ["Bake."], newTips: ["A tip."]))
    }

    func testNoTipsIsNeverASplitEvenWhenStepsWereOnlyDropped() {
        XCTAssertFalse(CookProgress.keptWhenTipsSplitOff(oldSteps: ["Bake.", "Cool."], newSteps: ["Bake."], newTips: []))
    }

    func testClampingDropsMarksAndTimersPastTheLastStepAndMovesTheCurrentStepBack() {
        let progress = CookProgress(active: true, currentStep: 3, doneSteps: [0, 2, 3], timers: [1: timer, 3: timer])
        XCTAssertEqual(
            progress.clamped(to: 3),
            CookProgress(active: true, currentStep: 2, doneSteps: [0, 2], timers: [1: timer])
        )
    }

    func testClampingLeavesProgressInsideTheStepsAsItIs() {
        let progress = CookProgress(active: true, currentStep: 1, doneSteps: [0], timers: [1: timer])
        XCTAssertEqual(progress.clamped(to: 2), progress)
    }

    func testNoStepsLeftNoProgress() {
        XCTAssertTrue(CookProgress(active: true, currentStep: 1).clamped(to: 0).isEmpty)
    }
}
