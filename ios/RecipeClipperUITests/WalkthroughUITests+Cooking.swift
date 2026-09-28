import XCTest

// Walkthroughs 13, 17, 18, 20, 21 and 23: "I made this" (#116), the first-run tour (#151), Done
// shopping and the On list tag (#146), using up the pantry after cooking (#147), what Settings
// says about Chef mode on a phone that can't run it (#144, the model's answer simulated with
// `-uiTestChefUnsupported`), and "Mark as cooked" (#173).

extension WalkthroughUITests {

    /// A photo from the library (the recording script adds one to the simulator's Photos), which
    /// opens for its note: typed with the keyboard up, then × closes the viewer, and the photo
    /// reopens from its thumbnail (Android's test13).
    func test13_iMadeThis() {
        start(flags: ["cookedPhotos"])
        openRecipes()
        scrollTo(row("Chocolate Chip Cookies"), "the cookies").tap()
        require(bookmark, "the recipe")
        pause()
        scrollTo(app.buttons["cooked.iMadeThis"], "I made this").tap()
        pause(0.8)
        require(app.buttons["Choose from library"], "the library choice").tap()
        pause(2.5)
        // The system's photo picker, newest first: the recording script's photo, added today.
        require(app.images.matching(identifier: "PXGGridLayout-Info").firstMatch, "the newest photo in the picker").tap()
        pause()
        require(app.navigationBars["Photos"].buttons["Done"], "the picker's Done").tap()
        // The photo just added opens full screen, dated, for its note.
        let note = require(app.descendants(matching: .any).matching(identifier: "cooked.note").firstMatch, "the note")
        pause(1.5)
        note.tap()
        note.typeText("Cut the dough into gingerbread men for the kids.")
        pause(2)
        require(app.buttons["Close"], "Close").tap()
        let photo = require(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Your photo, ")).firstMatch, "the photo in Your cooks")
        pause(2)
        photo.tap()
        require(note, "the photo, reopened with its note")
        pause(2.5)
        require(app.buttons["Close"], "Close").tap()
        pause(1.5)
    }

    func test17_firstRunTour() {
        startFirstRun(flags: ["mealPlan"])
        for _ in 0..<3 {
            require(app.buttons["welcome.next"], "Next").tap()
            pause(2.5)
        }
        require(app.buttons["welcome.trySample"], "Try it with a sample recipe").tap()
        let tip = require(app.buttons["tip.RECIPE"], "the recipe tip")
        pause(3)
        tip.tap()
        pause()
    }

    func test18_doneShoppingAndOnList() {
        start(flags: ["mealPlan"], scenario: .walkthroughPantry)
        tab("Groceries")
        for item in ["soy sauce", "garlic", "bay leaves"] {
            require(line(item), item).tap()
            pause(1)
        }
        pause()
        require(app.buttons["doneShopping"], "Done shopping").tap()
        pause()
        // Not in the pantry yet, so unticked: ticked to go in.
        require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'putAway-new-'")).firstMatch, "bay leaves").tap()
        pause()
        require(app.buttons["putAwayButton"], "Put away and clear").tap()
        let undo = require(app.buttons["Undo"], "the snackbar")
        pause()
        undo.tap()
        pause(2.5)
        tab("Pantry")
        require(app.buttons["Ran out: onions"], "the onions' Ran out").tap() // onto the list
        require(app.buttons["On list"], "the On list tag")
        pause(2.5)
        tab("Groceries")
        pause(2)
    }

    func test20_pantryUseUpAfterCooking() {
        start(flags: ["mealPlan"], scenario: .walkthroughPantry)
        open("Chicken Adobo")
        for ingredient in ["2 lb chicken thighs", "1/2 cup soy sauce", "1/3 cup white vinegar"] {
            require(line(ingredient), ingredient).tap()
            pause(1)
        }
        pause()
        require(app.buttons["Start cooking"]).tap()
        pause(2)
        for _ in 0..<3 {
            require(app.buttons["Done — next step"]).tap()
            pause(1.2)
        }
        require(app.buttons["Done — finish"]).tap()
        require(text("Update the pantry"), "the sheet")
        pause(3)
        require(app.buttons["soy sauce: Running low"], "Running low").tap()
        pause(2)
        require(app.buttons["useUpButton"], "the sheet's button").tap()
        let undo = require(app.buttons["Undo"], "the snackbar")
        pause(2)
        undo.tap()
        pause(2)
    }

    /// "Mark as cooked" (#173): a cooking with no photo, "Cooked" in its place, and a note;
    /// closing it offers the pantry's use-up sheet (#147) for every line.
    func test23_markAsCooked() {
        start(flags: ["mealPlan", "cookedPhotos"], scenario: .walkthroughPantry)
        open("Chicken Adobo")
        scrollTo(app.buttons["cooked.iMadeThis"], "I made this").tap()
        pause(0.8)
        require(app.buttons["Mark as cooked"], "Mark as cooked").tap()
        require(text("Cooked"), "Cooked in place of a picture")
        pause(2)
        let note = require(app.descendants(matching: .any).matching(identifier: "cooked.note").firstMatch, "the note")
        note.tap()
        note.typeText("Doubled the garlic.")
        pause(2)
        require(app.buttons["Close"], "Close").tap()
        require(text("Update the pantry"), "the use-up sheet")
        pause(3.5)
        require(app.buttons["useUpButton"], "the sheet's button").tap()
        require(app.buttons["Undo"], "the snackbar")
        pause(3)
    }

    func test21_chefModeUnsupportedSimulated() {
        start(flags: ["chefMode"], extraArguments: ["-uiTestChefUnsupported"])
        require(app.buttons["Settings"]).tap()
        pause()
        scrollTo(app.descendants(matching: .any)["settings.chefModeNote"], "the Chef mode note")
        app.swipeUp() // clear of the foot of the screen
        pause(4)
    }
}
