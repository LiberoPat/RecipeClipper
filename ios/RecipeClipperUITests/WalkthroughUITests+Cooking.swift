import XCTest

// Walkthroughs 13, 17, 18, 20 and 21: "I made this" (#116), the first-run tour (#151), Done
// shopping and the On list tag (#146), using up the pantry after cooking (#147), and what
// Settings says about Chef mode on a phone that can't run it (#144, the model's answer simulated
// with `-uiTestChefUnsupported`).

extension WalkthroughUITests {

    /// A photo from the library (the recording script adds one to the simulator's Photos) and a
    /// note.
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
        // The system's photo picker, newest first: the recording script's photo.
        print("PICKER-TREE-BEGIN"); print(app.debugDescription); print("PICKER-TREE-END")
        let photo = app.scrollViews.images.firstMatch
        require(photo, "a photo in the picker").tap()
        pause()
        let add = app.buttons["Add"]
        if add.waitForExistence(timeout: 3) { add.tap() }
        // The photo just added opens for its note.
        let note = require(app.textFields["cooked.note"], "the note")
        note.tap()
        note.typeText("Cut the dough into gingerbread men for the kids.")
        pause(2)
        require(app.buttons["Close"], "Close").tap()
        pause(2)
        let thumbnail = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Your photo, ")).firstMatch
        scrollTo(thumbnail, "the photo").tap()
        pause(3)
        require(app.buttons["Close"], "Close").tap()
        pause()
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
        require(app.switches["In stock: onions"], "the onions' switch").tap() // run out: onto the list
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

    func test21_chefModeUnsupportedSimulated() {
        start(flags: ["chefMode"], extraArguments: ["-uiTestChefUnsupported"])
        require(app.buttons["Settings"]).tap()
        pause()
        scrollTo(app.descendants(matching: .any)["settings.chefModeNote"], "the Chef mode note")
        app.swipeUp() // clear of the foot of the screen
        pause(4)
    }
}
