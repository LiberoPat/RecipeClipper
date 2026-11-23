import XCTest

/// The Plan's quiet pantry note (#456), end to end: Miso Soup planned today with no miso paste or
/// green onions in the pantry says so in one muted line; Spaghetti Carbonara, with everything, says
/// nothing. A tap opens the note without opening the recipe; "Add to groceries" adds the two lines,
/// offers one Undo, and the note then says "On your grocery list"; Undo brings it back. Android's
/// `WeekPantryNoteScreenTest`.
final class WeekPantryNoteUITests: RecipeUITestCase {

    private static let pantry = "dashi,block tofu,spaghetti,guanciale,egg yolks,egg,pecorino romano,black pepper"

    private func open(plan: String) {
        launch(.walkthrough, flags: ["planPantryNote"], extraArguments: [
            "-uiTestPlanToday", plan, "-uiTestPantry", Self.pantry, "-uiTestHoldUndo",
        ])
        require(app.tabBars.firstMatch.buttons["Plan"], "the Plan tab").tap()
        require(app.staticTexts["weekRange"], "the Week")
    }

    private var notes: XCUIElementQuery {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'pantryGap-'"))
    }

    func testTheMissingIngredientsShowUnderTheMealAndATapAddsThemWithOneUndo() {
        open(plan: "lunch:Miso Soup,dinner:Spaghetti Carbonara")

        let note = app.buttons["Not in your pantry: white miso paste, green onions"]
        require(note, "the note under Miso Soup")
        // Only the meal missing something has one.
        XCTAssertEqual(notes.count, 1)

        note.tap()
        // The recipe didn't open: the Plan is still showing, with the note's button under it.
        require(app.staticTexts["weekRange"], "still the Plan")
        let add = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'pantryGapAdd-'")).firstMatch
        require(add, "Add to groceries").tap()

        require(app.staticTexts["Added 2 to groceries"], "the undo snackbar")
        require(app.staticTexts["On your grocery list"], "the note, seen to")
        XCTAssertFalse(app.buttons["On your grocery list"].exists, "the settled note offers nothing")

        require(app.buttons["Undo"], "Undo").tap()
        require(app.buttons["Not in your pantry: white miso paste, green onions"], "the note back after Undo")
        XCTAssertFalse(app.staticTexts["On your grocery list"].exists)
    }

    func testPastThreeNamesItSaysHowManyMoreAndATapShowsThemAll() {
        open(plan: "lunch:Chicken Adobo")
        let capped = app.buttons["Not in your pantry: chicken thighs, soy sauce and 5 more"]
        require(capped, "the capped note").tap()
        require(
            app.buttons["Not in your pantry: chicken thighs, soy sauce, white vinegar, garlic, onion, bay leaves, black peppercorns"],
            "every missing name"
        ).tap()
        require(capped, "closed again")
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'pantryGapAdd-'")).firstMatch.exists)
    }
}
