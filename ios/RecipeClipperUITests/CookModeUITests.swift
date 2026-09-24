import XCTest

/// Cook mode end to end, the counterpart of Android's CookModeTest. Cook mode is a highlighted
/// scroll: the current step is a ringed card holding the timer and the only button that
/// advances; every other step is a button that makes it current without marking anything done.
///
/// Struck-through (done) and muted (upcoming) rows look different but read the same to
/// XCUITest, so which steps are done is proved by where "Done" goes next: it moves on to the
/// next unfinished step, then back to the earliest one skipped.
///
/// The `cook` scenario's "Weeknight Chili": 1 "Brown the beef in a large pot.", 2 "Simmer for
/// 20 minutes.", 3 "Rest off the heat for 3 seconds.", 4 "Serve with rice."
final class CookModeUITests: RecipeUITestCase {

    private func startCooking() {
        launch(.cook)
        openRecipe("Weeknight Chili")
        require(app.buttons["Start cooking"]).tap()
        require(text("Step 1 of 4"), "cook mode")
    }

    /// A step that isn't current: a button whose label carries the step's text.
    private func stepButton(_ text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private func done() { require(app.buttons["Done — next step"]).tap() }

    private func requireStep(_ n: Int, file: StaticString = #filePath, line: UInt = #line) {
        require(text("Step \(n) of 4"), "step \(n) to be current", file: file, line: line)
        require(text("STEP \(n)"), "step \(n)'s card", file: file, line: line)
    }

    // MARK: Step states

    func testCookingStartsOnTheFirstStepWithTheRestUpcoming() {
        startCooking()

        require(text("STEP 1"))
        require(app.buttons["Done — next step"])
        for step in ["Simmer for 20 minutes.", "Rest off the heat for 3 seconds.", "Serve with rice."] {
            require(stepButton(step), "\(step) as a tappable upcoming step")
        }
    }

    func testDoneAdvancesToTheNextStep() {
        startCooking()

        done()

        requireStep(2)
        assertAbsent(text("STEP 1"))
        require(stepButton("Brown the beef in a large pot."), "step 1 as a row once done")
    }

    /// Jumping is not progress. After a jump to step 3, Done goes on to 4 and "Done — finish"
    /// then comes back to step 1: neither the jump nor the steps skipped marked anything done.
    func testTappingAStepMakesItCurrentWithoutMarkingAnythingDone() {
        startCooking()

        stepButton("Rest off the heat for 3 seconds.").tap()
        requireStep(3)

        done()
        requireStep(4)
        require(app.buttons["Done — finish"]).tap()

        requireStep(1)
        done()
        requireStep(2)
    }

    func testTappingADoneStepGoesBackToIt() {
        startCooking()
        done()

        stepButton("Brown the beef in a large pot.").tap()

        requireStep(1)
        done()
        requireStep(2)
    }

    func testFinishingEveryStepLeavesCookMode() {
        startCooking()

        for n in 2...4 {
            done()
            requireStep(n)
        }
        require(app.buttons["Done — finish"]).tap()

        require(app.buttons["Start cooking"], "the reading view")
        assertAbsent(text("Step 1 of 4"))
    }

    /// Exit is not "abandon": coming back resumes on the same step.
    func testExitKeepsYourPlace() {
        startCooking()
        done()

        require(app.buttons["✕ Exit"]).tap()
        require(app.buttons["Start cooking"]).tap()

        requireStep(2)
    }

    // MARK: Timers

    func testOnlyAStepThatStatesATimeOffersATimer() {
        startCooking()

        assertAbsent(app.buttons.matching(NSPredicate(format: "label BEGINSWITH '⏱ Start'")).firstMatch)

        done()

        require(app.buttons["⏱ Start 20 min timer"])
    }

    func testATimerStartsAtTheStatedTimeAndCanBePausedResumedAndReset() {
        startCooking()
        done()

        require(app.buttons["⏱ Start 20 min timer"]).tap()

        // It is already counting by the time the tree is read.
        require(app.staticTexts.matching(NSPredicate(format: "label == '20:00' OR label BEGINSWITH '19:'")).firstMatch,
                "the clock counting down from 20:00")
        require(app.buttons["Pause"]).tap()
        require(app.buttons["Resume"]).tap()
        require(app.buttons["Pause"])

        require(app.buttons["Reset"]).tap()
        require(text("20:00"), "the full time back after Reset")
        require(app.buttons["Resume"])
    }

    /// Waits out a real 3-second timer: XCUITest drives the app from outside, so it can't move
    /// the app's clock the way the hosted RecipeViewModelTests move TestClock.
    func testATimerSaysTimesUpWhenItFinishes() {
        startCooking()
        stepButton("Rest off the heat for 3 seconds.").tap()
        requireStep(3)

        require(app.buttons["⏱ Start 3 sec timer"]).tap()

        require(text("Time's up"), "the timer to finish")
        require(text("0:00"))
        assertAbsent(app.buttons["Pause"])
        require(app.buttons["Reset"])
    }

    /// Steps overlap: a timer keeps running, and stays in view, on a step you've moved past.
    func testATimerStaysVisibleOnAStepYouHaveMovedPast() {
        startCooking()
        stepButton("Rest off the heat for 3 seconds.").tap()
        require(app.buttons["⏱ Start 3 sec timer"]).tap()

        done()
        requireStep(4)

        let restRow = stepButton("Rest off the heat for 3 seconds.")
        requireState(restRow, "label CONTAINS '⏱'", "the timer on the step moved past")
        requireState(restRow, "label CONTAINS \"Time's up\"", "the timer finishing on the step moved past")
    }

    // MARK: Sharing out

    /// The share icon sits in the reading view, beside Back; cook mode has no share or bookmark.
    func testShareIsInTheReadingViewAndNotInCookMode() {
        launch(.cook)
        openRecipe("Weeknight Chili")
        require(app.buttons["Share recipe"], "Share in the reading view")

        app.buttons["Start cooking"].tap()
        require(text("Step 1 of 4"))

        assertAbsent(app.buttons["Share recipe"])
        assertAbsent(bookmark)
    }
}
