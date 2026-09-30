import XCTest

// Walkthroughs 24–30: tooltips (#190), the pantry's three states (#194), singular and plural
// names in What I need (#191), a Reddit post imported (#11) from a fixture listing, its photo
// read (#198), other languages (#208), and a recipe card scanned (#226).

extension WalkthroughUITests {

    /// Taps a tooltip's "Got it" and waits for the bubble to go.
    private func gotIt(_ id: String, _ what: String, hold: Double = 2.5) {
        let bubble = require(app.buttons["tooltip.\(id)"], what)
        pause(hold)
        bubble.tap()
        requireGone(bubble, "\(what), dismissed")
        pause()
    }

    /// A fresh install's tooltips: one a visit, at its control; the next on a later visit; and
    /// Settings' "Show tips again".
    func test24_tooltips() {
        startFirstRun(flags: ["mealPlan"])
        gotIt("home_link", "Home's first tooltip")
        require(row("Tomato and White Bean Soup"), "the sample recipe").tap()
        gotIt("recipe_units", "the recipe's first tooltip", hold: 4) // a longer text
        back()
        gotIt("home_new_recipe", "Home's next tooltip, on a later visit")
        require(app.buttons["Settings"]).tap()
        pause()
        let settingsTip = app.buttons["tooltip.settings_units"]
        if settingsTip.waitForExistence(timeout: 4) { gotIt("settings_units", "Settings' first tooltip") }
        scrollTo(app.buttons["settings.showTips"], "Show tips again")
        pause()
        app.buttons["settings.showTips"].tap()
        pause(1.5)
        back()
        require(app.buttons["tooltip.home_link"], "Home's first tooltip, again")
        pause(3)
    }

    private func pantryRow(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(name), ")).firstMatch
    }

    /// The row as VoiceOver reads it, holding `state` ("Run out", "In stock", …).
    private func pantryRow(_ name: String, _ state: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@", "\(name), ", ", \(state)")).firstMatch
    }

    /// Scrolls the Pantry (a lazy List: rows off screen aren't in the tree) until `element` can
    /// be tapped, down first, then back up.
    @discardableResult
    private func reveal(_ element: XCUIElement, _ what: String) -> XCUIElement {
        for _ in 0..<3 where !(element.exists && element.isHittable) { app.swipeUp(); pause(0.6) }
        for _ in 0..<4 where !(element.exists && element.isHittable) { app.swipeDown(); pause(0.6) }
        // Hittable even under the tab bar, where a swipe would land on the bar: drag until it
        // clears it.
        for _ in 0..<4 where element.exists && element.frame.maxY > tabBar.frame.minY - 8 {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6))
                .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)))
            _ = element.waitForExistence(timeout: 0.6)
        }
        return require(element, what)
    }

    /// In stock, Running low, Run out (#194, #199; no row button since 2026-09-29): a swipe runs
    /// a row out, the edit sheet's stock control, the long-press menu (both other states), and a
    /// swipe each way. Run out items sit in their own section at the foot of the Pantry.
    func test25_pantryStates() {
        start(flags: ["mealPlan"], scenario: .walkthroughPantry)
        tab("Pantry")
        pause()
        reveal(pantryRow("soy sauce"), "soy sauce").swipeLeft()
        pause(1.5)
        require(app.buttons["Ran out"], "the swipe's Ran out").tap()
        pause()
        reveal(pantryRow("soy sauce", "Run out"), "soy sauce, run out")
        pause(2.5)
        // Tapping a row opens its sheet, whose stock control shows every state (#199).
        reveal(pantryRow("olive oil"), "olive oil").tap()
        let control = require(app.segmentedControls["pantryEditStock"], "the sheet's stock control")
        pause(2)
        control.buttons["Running low"].tap()
        pause(2.5)
        require(app.buttons["pantryEditSave"], "Save").tap()
        let lowTag = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'low-'")).firstMatch
        reveal(lowTag, "the Low tag")
        pause(2.5)
        // The long-press menu offers both other states: Restock and Ran out, while running low.
        reveal(pantryRow("olive oil"), "olive oil").press(forDuration: 1.2)
        let restock = require(app.buttons["Restock"], "the menu's Restock")
        require(app.buttons["Ran out"], "the menu's Ran out")
        pause(2.5)
        restock.tap()
        pause(2)
        reveal(pantryRow("basmati rice"), "basmati rice").swipeLeft()
        pause(1.5)
        require(app.buttons["Running low"], "the swipe's Running low").tap()
        pause(2)
        reveal(pantryRow("garlic"), "garlic").swipeRight()
        pause(1.5)
        require(app.buttons["Restock"], "the swipe's Restock").tap()
        pause()
        reveal(pantryRow("garlic", "In stock"), "garlic, back in stock")
        pause(2.5)
    }

    /// "onions" in the pantry covers the Adobo's "1 onion, sliced" (#191); the Guacamole's red
    /// onion is another thing, so it stays To buy.
    func test26_onionPlurals() {
        start(flags: ["mealPlan"])
        tab("Pantry")
        type("onions\n", into: app.textFields["Add to the pantry"])
        pause()
        tab("Week")
        planToday("Chicken Adobo")
        planToday("Guacamole")
        recipeMenu("What I need")
        require(textContaining("red onion"), "the red onion, To buy")
        pause(2.5)
        scrollTo(textContaining("1 onion, sliced"), "the Adobo's onion, in the pantry")
        pause(3)
    }

    /// A Reddit post (#11): an image post whose recipe is in the poster's comment. reddit.com
    /// refuses this machine, so the link goes through the real parser over a fixture listing
    /// (`RC_UITEST_REDDIT_LISTING`, read by the stub source).
    func test27_redditImport() throws {
        let fixture = try XCTUnwrap(Bundle(for: WalkthroughUITests.self)
            .url(forResource: "recipes-image-op-comment", withExtension: "json", subdirectory: "reddit"))
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "mealPlan,reddit"]
        app.launchEnvironment["RC_UITEST_REDDIT_LISTING"] = try String(contentsOf: fixture, encoding: .utf8)
        app.launch()
        self.app = app
        let field = require(app.textFields["Recipe URL"], "Home")
        mark("START")
        pause()
        field.tap()
        field.typeText("https://www.reddit.com/r/recipes/comments/1f3k9xq/sticky_honey_garlic_chicken_thighs/")
        pause()
        app.scrollViews.firstMatch.buttons["Go"].tap()
        require(bookmark, "the recipe screen")
        pause(3)
        app.swipeUp()
        pause(2.5)
        app.swipeUp()
        pause(2.5)
    }

    /// "Read the photo" (#198) with the real Vision reader: an untranscribed r/Old_Recipes card
    /// (the fixture listing through the real parser, its pictures swapped for the local card
    /// photos in `shared/fixtures/reddit/photos`), the review with the line Vision was unsure of,
    /// that line fixed, Save; then a photo too blurred to read, which opens the editor to finish
    /// by hand.
    func test28_readThePhoto() throws {
        let bundle = Bundle(for: WalkthroughUITests.self)
        let fixture = try XCTUnwrap(bundle.url(forResource: "old-recipes-card-untranscribed", withExtension: "json", subdirectory: "reddit"))
        func photo(_ name: String) throws -> String {
            try XCTUnwrap(bundle.url(forResource: name, withExtension: "jpg", subdirectory: "reddit/photos")).absoluteString
        }
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "mealPlan,reddit,photoText"]
        app.launchEnvironment["RC_UITEST_REDDIT_LISTING"] = try String(contentsOf: fixture, encoding: .utf8)
        app.launchEnvironment["RC_UITEST_PHOTO_VISION"] = "1"
        app.launchEnvironment["RC_UITEST_PHOTO_IMAGES"] =
            "1f7a2bc=\(try photo("card-front")) \(try photo("card-back"))\n1f7a3cd=\(try photo("card-blurred"))"
        app.launch()
        self.app = app
        require(app.textFields["Recipe URL"], "Home")
        mark("START")
        pause()

        func readPhoto(of link: String) {
            let field = require(app.textFields["Recipe URL"], "Home")
            field.tap()
            field.typeText(link)
            pause(0.8)
            app.scrollViews.firstMatch.buttons["Go"].tap()
            require(textContaining("No recipe text found"), "the post's no-transcription note")
            pause(2.5)
            require(app.buttons["recipe.readPhoto"], "Read the photo").tap()
        }

        readPhoto(of: "https://www.reddit.com/r/Old_Recipes/comments/1f7a2bc/aunt_junes_oatmeal_cookies_front_and_back_of_the_card/")
        require(text("Check the recipe"), "the review")
        require(textContaining("Read from the photo"), "how the reading went", within: 30)
        pause(2.5)
        scrollTo(app.descendants(matching: .any)["edit.photoCheck"], "the lines to check")
        pause(3)

        // Fix the lines flagged to check. Filled from the photo, the box has no placeholder any
        // more: find it by what it holds, select all of it and type it back corrected, so no
        // caret placement can land a fix on the wrong line.
        let box = app.descendants(matching: .any).matching(NSPredicate(
            format: "(elementType == %lu OR elementType == %lu) AND value CONTAINS %@",
            XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue, "cup butter"
        )).firstMatch
        scrollTo(box, "the ingredients")
        pause()
        let typed = box.value as? String ?? ""
        let fixed = typed.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
            if line.contains("cupraisi") || line.contains("rasins") { return "1 cup raisins" }
            return line.replacingOccurrences(of: "11/2", with: "1 1/2")
        }.joined(separator: "\n")
        // Bring the whole box on screen, then tap past its last line: the caret goes to the very
        // end, so deleting every character clears it and the corrected text replaces it.
        for _ in 0..<6 where box.frame.maxY > app.frame.maxY - 140 {
            app.swipeUp(velocity: .slow)
        }
        box.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.97)).tap()
        pause(0.8)
        box.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: typed.count + 2))
        pause(0.5)
        box.typeText(fixed)
        pause(2.5)
        require(app.buttons["edit.save"], "Save").tap()
        require(bookmark, "the saved recipe")
        require(textContaining("Clipped by you"), "the clip's credit")
        pause(3)
        app.swipeUp()
        pause(3)

        // A photo that reads nothing: the editor, to finish by hand.
        back()
        readPhoto(of: "https://www.reddit.com/r/Old_Recipes/comments/1f7a3cd/aunt_junes_oatmeal_cookies_blurry_photo/")
        require(text("Couldn't read a recipe from the photo: finish it by hand."), "the fallback note", within: 30)
        pause(4)
    }

    /// Reading other languages (#208): a German Reddit post (the `de` language fixture's, through
    /// the real parser) sorted under its German headings, doubled and in Metric; then a French
    /// recipe card read by the real Vision reader (`shared/fixtures/reddit/photos/card-fr.jpg`,
    /// drawn from the `fr` fixture's photo lines), its glued "2 c. à soupesucre" flagged, fixed
    /// and saved.
    func test29_readingOtherLanguages() throws {
        let bundle = Bundle(for: WalkthroughUITests.self)
        func listing(_ name: String) throws -> String {
            try String(contentsOf: try XCTUnwrap(bundle.url(forResource: name, withExtension: "json", subdirectory: "reddit")), encoding: .utf8)
        }
        let card = try XCTUnwrap(bundle.url(forResource: "card-fr", withExtension: "jpg", subdirectory: "reddit/photos"))
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "mealPlan,reddit,photoText"]
        app.launchEnvironment["RC_UITEST_REDDIT_LISTING_1f8b4de"] = try listing("de-self-post")
        app.launchEnvironment["RC_UITEST_REDDIT_LISTING_1f8c5fr"] = try listing("fr-card-untranscribed")
        app.launchEnvironment["RC_UITEST_PHOTO_VISION"] = "1"
        app.launchEnvironment["RC_UITEST_PHOTO_IMAGES"] = "1f8c5fr=\(card.absoluteString)"
        app.launch()
        self.app = app
        let field = require(app.textFields["Recipe URL"], "Home")
        mark("START")
        pause()

        // The German post, its headings read in German, a serving more, then Metric.
        field.tap()
        field.typeText("https://www.reddit.com/r/Kochen/comments/1f8b4de/omas_pfannkuchen/")
        pause(0.8)
        app.scrollViews.firstMatch.buttons["Go"].tap()
        require(bookmark, "the recipe screen")
        pause(2.5)
        // Doubled, 4 to 8: whole amounts, where 5 would show "312 1/2 g Mehl".
        let more = require(app.buttons["Increase servings"], "the Serves stepper")
        for _ in 0..<4 { more.tap(); pause(0.4) }
        pause(1)
        require(app.buttons["Change units"], "the units menu").tap()
        pause(1)
        require(app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Metric'")).firstMatch, "Metric").tap()
        pause(2)
        app.swipeUp()
        pause(2)

        // The French card, read from the photo.
        back()
        let link = require(app.textFields["Recipe URL"], "Home")
        link.tap()
        link.typeText("https://www.reddit.com/r/cuisine/comments/1f8c5fr/la_fiche_de_crepes_de_ma_grandmere/")
        pause(0.8)
        app.scrollViews.firstMatch.buttons["Go"].tap()
        require(textContaining("No recipe text found"), "the post's no-transcription note")
        pause(2)
        require(app.buttons["recipe.readPhoto"], "Read the photo").tap()
        require(text("Check the recipe"), "the review")
        require(textContaining("Read from the photo"), "how the reading went", within: 30)
        pause(2)
        scrollTo(app.descendants(matching: .any)["edit.photoCheck"], "the lines to check")
        pause(2.5)

        // Fix the glued line, as clip 28 does: the whole box typed back corrected.
        let box = app.descendants(matching: .any).matching(NSPredicate(
            format: "(elementType == %lu OR elementType == %lu) AND value CONTAINS %@",
            XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue, "de farine"
        )).firstMatch
        scrollTo(box, "the ingredients")
        pause()
        let typed = box.value as? String ?? ""
        let fixed = typed.replacingOccurrences(of: "soupesucre", with: "soupe de sucre")
        for _ in 0..<6 where box.frame.maxY > app.frame.maxY - 140 {
            app.swipeUp(velocity: .slow)
        }
        box.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.97)).tap()
        pause(0.8)
        box.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: typed.count + 2))
        pause(0.5)
        // Emptied, the box no longer matches its query: type into whatever has the focus (it).
        app.typeText(fixed)
        pause(2)
        require(app.buttons["edit.save"], "Save").tap()
        require(bookmark, "the saved recipe")
        require(textContaining("Clipped by you"), "the clip's credit")
        pause(2.5)
        app.swipeUp()
        pause(2.5)
    }

    /// "Scan a recipe" (#226): Recipes + → Scan a recipe → Choose from library, the fixture card's
    /// two sides (`shared/fixtures/reddit/photos/card-front.jpg`, `card-back.jpg`) picked in order
    /// in the real photo picker (the recording script adds them to the simulator's Photos just
    /// before this test: the front newest), read by the real Vision reader; "Check the recipe"
    /// with both pages on top, the name typed (none is guessed), the flagged lines fixed, Save;
    /// then Home's own "Scan a recipe".
    func test30_scanARecipe() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "mealPlan,photoText"]
        app.launchEnvironment["RC_UITEST_PHOTO_VISION"] = "1"
        app.launch()
        self.app = app
        require(app.textFields["Recipe URL"], "Home")
        mark("START")
        pause()
        openRecipes()
        pause()
        require(app.buttons["recipes.add"], "the + menu").tap()
        pause()
        require(app.buttons["Scan a recipe"], "Scan a recipe").tap()
        pause()
        require(app.buttons["Choose from library"], "the library choice").tap()
        pause(2.5)
        // The system's photo picker, newest first: the card's front, then its back, numbered 1, 2.
        let photos = app.images.matching(identifier: "PXGGridLayout-Info")
        require(photos.firstMatch, "the photos in the picker")
        photos.element(boundBy: 0).tap()
        pause()
        photos.element(boundBy: 1).tap()
        pause(1.5)
        require(app.buttons.matching(NSPredicate(format: "label IN %@", ["Add", "Done"])).firstMatch, "the picker's Add").tap()

        require(text("Check the recipe"), "the review")
        require(app.descendants(matching: .any)["Page 2 of 2"], "both pages")
        require(textContaining("Read from the photo"), "how the reading went", within: 30)
        pause(3)
        // No title is guessed: the cook names it.
        let name = require(app.textFields["Name"], "the name field")
        name.tap()
        name.typeText("Aunt June's Oatmeal Cookies")
        pause(1.5)
        scrollTo(app.descendants(matching: .any)["edit.photoCheck"], "the lines to check")
        pause(3)

        // Fix the lines flagged to check, as clip 28 does: the whole box typed back corrected.
        let box = app.descendants(matching: .any).matching(NSPredicate(
            format: "(elementType == %lu OR elementType == %lu) AND value CONTAINS %@",
            XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue, "brown sugar"
        )).firstMatch
        scrollTo(box, "the ingredients")
        pause()
        let typed = box.value as? String ?? ""
        let fixed = typed.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
            if line.contains("cupraisi") || line.contains("rasins") { return "1 cup raisins" }
            return line.replacingOccurrences(of: "11/2", with: "1 1/2").replacingOccurrences(of: "eg9s", with: "eggs")
        }.joined(separator: "\n")
        for _ in 0..<6 where box.frame.maxY > app.frame.maxY - 140 {
            app.swipeUp(velocity: .slow)
        }
        box.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.97)).tap()
        pause(0.8)
        box.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: typed.count + 2))
        pause(0.5)
        // Emptied, the box no longer matches its query: type into whatever has the focus (it).
        app.typeText(fixed)
        pause(2.5)
        require(app.buttons["edit.save"], "Save").tap()
        require(bookmark, "the saved recipe")
        require(text("Aunt June's Oatmeal Cookies"), "its name")
        pause(3)
        app.swipeUp()
        pause(2.5)

        // Home has its own "Scan a recipe", beside "+ New recipe".
        let homeScan = app.buttons["home.scanRecipe"]
        for _ in 0..<3 where !homeScan.exists {
            back()
            pause()
        }
        require(homeScan, "Home's Scan a recipe").tap()
        require(app.buttons["Choose from library"], "its two choices")
        pause(2.5)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12)).tap()
        pause()
    }
}
