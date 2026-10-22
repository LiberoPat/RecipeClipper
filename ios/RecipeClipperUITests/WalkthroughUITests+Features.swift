import XCTest

// Walkthroughs 24–34: tooltips (#190), the pantry's three states (#194), singular and plural
// names in What I need (#191), a Reddit post imported (#11) from a fixture listing, its photo
// read (#198), other languages (#208), a recipe card scanned (#226), a live Reddit post and a
// live page clipped by hand (#213, #230, #37), selecting several pantry items (#258), and a
// card's fraction glyphs (#245).

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
        startFirstRun()
        gotIt("home_link", "Home's first tooltip")
        require(row("Tomato and White Bean Soup"), "the sample recipe").tap()
        gotIt("recipe_units", "the recipe's first tooltip", hold: 4) // a longer text
        back()
        require(row("Tomato and White Bean Soup"), "the sample recipe").tap()
        gotIt("recipe_start_cooking", "the recipe's next tooltip, on a later visit")
        back()
        require(app.buttons["Settings"]).tap()
        pause()
        require(settingsRow("help"), "Settings' Help row").tap() // the index (#286): Help's own page
        let settingsTip = app.buttons["tooltip.settings_show_tips"]
        if settingsTip.waitForExistence(timeout: 4) { gotIt("settings_show_tips", "Settings' tooltip, on Help's page") }
        pause()
        app.buttons["settings.showTips"].tap()
        pause(1.5)
        back() // to the index
        back() // to Home
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
    /// a row out, the edit sheet's stock control, and a swipe each way (a touch and hold selects
    /// since #258). Run out items sit in their own section at the foot of the Pantry.
    func test25_pantryStates() {
        start(scenario: .walkthroughPantry)
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
        // Running low, the swipes offer both other states: Restock (a touch and hold starts
        // selecting since #258).
        reveal(pantryRow("olive oil"), "olive oil").swipeRight()
        pause(1.5)
        require(app.buttons["Restock"], "the swipe's Restock").tap()
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

    /// Selecting several (#258): a touch and hold that goes on moving checks every row it passes,
    /// Mark run out moves them to Run out with one Undo; then a touch and hold, Select all and
    /// Remove empty the Pantry, and one Undo puts every item back.
    func test33_pantryMultiSelect() {
        start(scenario: .walkthroughPantry)
        tab("Pantry")
        pause()
        let rows = pantryRowsOnScreen()
        rows[0].press(forDuration: 1.0, thenDragTo: rows[2], withVelocity: .slow, thenHoldForDuration: 0.3)
        let count = require(app.staticTexts["selectedCount"], "the selection bar")
        print("WALKTHROUGH-SELECTED \(count.label)")
        pause(2.5)
        require(app.buttons["markRunOutSelected"], "Mark run out").tap()
        pause(3)
        require(app.buttons["Undo"], "Undo").tap()
        pause(3)
        pantryRowsOnScreen()[0].press(forDuration: 1.0)
        requireState(app.staticTexts["selectedCount"], "label == '1 selected'", "1 selected")
        pause(2)
        require(app.buttons["selectAll"], "Select all").tap()
        pause(2.5)
        require(app.buttons["removeSelected"], "Remove").tap()
        pause(3)
        require(app.buttons["Undo"], "Undo").tap()
        pause(3)
    }

    /// The Pantry's item rows on screen, top to bottom.
    private func pantryRowsOnScreen() -> [XCUIElement] {
        let query = app.buttons.matching(NSPredicate(format: "identifier MATCHES 'pantry-[0-9]+'"))
        require(query.firstMatch, "the pantry's rows")
        return query.allElementsBoundByIndex
            .filter { $0.isHittable && $0.frame.maxY < tabBar.frame.minY }
            .sorted { $0.frame.minY < $1.frame.minY }
    }

    /// "onions" in the pantry covers the Adobo's "1 onion, sliced" (#191); the Guacamole's red
    /// onion is another thing, so it stays To buy.
    func test26_onionPlurals() {
        start()
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
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "reddit"]
        app.launchEnvironment["RC_UITEST_REDDIT_LISTING"] = try String(contentsOf: fixture, encoding: .utf8)
        app.launch()
        self.app = app
        require(homePasteLink, "Home")
        mark("START")
        pause()
        pasteLink("https://www.reddit.com/r/recipes/comments/1f3k9xq/sticky_honey_garlic_chicken_thighs/") { pause() }
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
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "reddit,photoText"]
        app.launchEnvironment["RC_UITEST_REDDIT_LISTING"] = try String(contentsOf: fixture, encoding: .utf8)
        app.launchEnvironment["RC_UITEST_PHOTO_VISION"] = "1"
        app.launchEnvironment["RC_UITEST_PHOTO_IMAGES"] =
            "1f7a2bc=\(try photo("card-front")) \(try photo("card-back"))\n1f7a3cd=\(try photo("card-blurred"))"
        app.launch()
        self.app = app
        require(homePasteLink, "Home")
        mark("START")
        pause()

        func readPhoto(of link: String) {
            pasteLink(link) { pause(0.8) }
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
            format: "(elementType == %lu OR elementType == %lu) AND value CONTAINS %@ AND NOT (identifier BEGINSWITH 'edit.checkLine.')",
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
        // Emptied, the box no longer matches its "cup butter" query: type into whatever has the
        // keyboard, which is still that box.
        app.typeText(fixed)
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
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "reddit,photoText"]
        app.launchEnvironment["RC_UITEST_REDDIT_LISTING_1f8b4de"] = try listing("de-self-post")
        app.launchEnvironment["RC_UITEST_REDDIT_LISTING_1f8c5fr"] = try listing("fr-card-untranscribed")
        app.launchEnvironment["RC_UITEST_PHOTO_VISION"] = "1"
        app.launchEnvironment["RC_UITEST_PHOTO_IMAGES"] = "1f8c5fr=\(card.absoluteString)"
        app.launch()
        self.app = app
        require(homePasteLink, "Home")
        mark("START")
        pause()

        // The German post, its headings read in German, a serving more, then Metric.
        pasteLink("https://www.reddit.com/r/Kochen/comments/1f8b4de/omas_pfannkuchen/") { pause(0.8) }
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
        pasteLink("https://www.reddit.com/r/cuisine/comments/1f8c5fr/la_fiche_de_crepes_de_ma_grandmere/") { pause(0.8) }
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
            format: "(elementType == %lu OR elementType == %lu) AND value CONTAINS %@ AND NOT (identifier BEGINSWITH 'edit.checkLine.')",
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
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "photoText"]
        app.launchEnvironment["RC_UITEST_PHOTO_VISION"] = "1"
        app.launch()
        self.app = app
        require(homePasteLink, "Home")
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
        // The pages sit in a row over the lines to check (#286): the first opened large, then closed.
        let first = require(app.buttons["Page 1 of 2"], "the first page's thumbnail")
        first.tap()
        pause(2.5)
        first.tap()
        pause(1.5)
        // No title is guessed: the cook names it.
        let name = require(app.textFields["Name"], "the name field")
        name.tap()
        name.typeText("Aunt June's Oatmeal Cookies")
        pause(1.5)
        scrollTo(app.descendants(matching: .any)["edit.photoCheck"], "the lines to check")
        pause(3)

        // Fix the lines flagged to check, as clip 28 does: the whole box typed back corrected.
        let box = app.descendants(matching: .any).matching(NSPredicate(
            format: "(elementType == %lu OR elementType == %lu) AND value CONTAINS %@ AND NOT (identifier BEGINSWITH 'edit.checkLine.')",
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

        // Home has its own "Scan a recipe" tile (#286).
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

    /// Fraction glyphs read from a photo (#245, #276): the handwritten fraction card
    /// (`card-fractions-hand.jpg`, which the recording script adds to the simulator's Photos just
    /// before this test) picked in the real photo picker and read by the real Vision reader, which
    /// gives its ⅓ as "½⅓" (glyphs run together) and may give others as look-alikes. "Check these
    /// lines" flags them with the fraction chips: each chip is found by its label and tapped, and
    /// tapped again if its line didn't change. Then the slips no chip fixes ("34 tsp salt", flagged
    /// since #249, with "This amount looks large: check it" under it since #281) are typed right, as clip 28 does, so the saved recipe is the card's; Save. It fails, rather than simulating,
    /// if Vision's reading offers no chip at all.
    func test34_fractionChips() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "photoText"]
        app.launchEnvironment["RC_UITEST_PHOTO_VISION"] = "1"
        app.launch()
        self.app = app
        require(homePasteLink, "Home")
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
        // The system's photo picker, newest first: the fraction card.
        let photos = app.images.matching(identifier: "PXGGridLayout-Info")
        require(photos.firstMatch, "the photos in the picker")
        photos.element(boundBy: 0).tap()
        pause(1.5)
        require(app.buttons.matching(NSPredicate(format: "label IN %@", ["Add", "Done"])).firstMatch, "the picker's Add").tap()

        require(text("Check the recipe"), "the review")
        require(textContaining("Read from the photo"), "how the reading went", within: 30)
        pause(3)
        let name = require(app.textFields["Name"], "the name field") // none is guessed
        name.tap()
        name.typeText("Oat Cookies\n") // Return puts the keyboard away
        pause(1.5)
        scrollTo(app.descendants(matching: .any)["edit.photoCheck"], "the lines to check")
        pause(3.5)

        // Every line with chips has a ½ chip, "Use ½ in <line>".
        let halves = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH 'edit.fraction.' AND label BEGINSWITH %@", "Use ½ in "
        ))
        let chipLines = halves.allElementsBoundByIndex.map { String($0.label.dropFirst("Use ½ in ".count)) }
        print("FRACTION-CHIPS \(chipLines.joined(separator: " | "))")
        XCTAssertFalse(chipLines.isEmpty, "Vision's reading of the card offered no chip")
        let box = app.descendants(matching: .any).matching(NSPredicate(
            format: "(elementType == %lu OR elementType == %lu) AND value CONTAINS %@ AND NOT (identifier BEGINSWITH 'edit.checkLine.')",
            XCUIElement.ElementType.textField.rawValue, XCUIElement.ElementType.textView.rawValue, "chocolate"
        )).firstMatch
        func boxLines() -> [String] {
            (box.value as? String ?? "").split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }
        }
        for line in chipLines {
            guard let fraction = Self.fractionCard.first(where: { line.contains($0.key) })?.fraction,
                  !fraction.isEmpty else { continue }
            for attempt in 0..<2 where boxLines().contains(line) {
                if attempt > 0 { print("FRACTION-CHIPS the \(fraction) chip for \"\(line)\" missed; tapping again") }
                let chip = app.buttons.matching(NSPredicate(
                    format: "identifier BEGINSWITH 'edit.fraction.' AND label == %@", "Use \(fraction) in \(line)"
                )).firstMatch
                guard chip.waitForExistence(timeout: 2) else { break }
                bringIntoView(chip)
                pause(1.2)
                chip.tap()
                pause(2.5)
            }
            XCTAssertFalse(boxLines().contains(line), "the \(fraction) chip didn't change \"\(line)\"")
        }

        // The slips no chip fixes, typed right: each ingredient line the card's, by what it holds.
        scrollTo(box, "the ingredients")
        pause()
        let typed = box.value as? String ?? ""
        let fixed = typed.split(separator: "\n", omittingEmptySubsequences: false).map { line -> String in
            // A line right but for a space ("1¼ cups flour") is left as read.
            guard let card = Self.fractionCard.first(where: { line.contains($0.key) })?.line,
                  card.replacingOccurrences(of: " ", with: "") != line.replacingOccurrences(of: " ", with: "")
            else { return String(line) }
            return card
        }.joined(separator: "\n")
        print("FRACTION-TYPED \(typed.split(separator: "\n").filter { !fixed.split(separator: "\n").contains($0) }.joined(separator: " | "))")
        if fixed != typed {
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
        }
        require(app.buttons["edit.save"], "Save").tap()
        require(bookmark, "the saved recipe")
        require(text("Oat Cookies"), "its name")
        pause(3)
        app.swipeUp()
        pause(2)
    }

    /// Short drags either way until `element` sits clear of the bars.
    private func bringIntoView(_ element: XCUIElement) {
        for _ in 0..<6 {
            let frame = element.frame
            if frame.minY < 140 {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4))
                    .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)))
            } else if frame.maxY > app.frame.maxY - 140 {
                app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6))
                    .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.4)))
            } else {
                return
            }
            pause(0.6)
        }
    }

    /// The fraction card's ingredient lines, by a piece of each that survives the reading ("mi"
    /// of "mik"): its fraction, then the line as the card has it (Android's `FRACTION_CARD`).
    static let fractionCard: [(key: String, fraction: String, line: String)] = [
        ("sug", "½", "½ cup sugar"), ("flo", "¼", "1 ¼ cups flour"), ("salt", "¾", "¾ tsp salt"),
        ("oat", "⅔", "2 ⅔ cups oats"), ("nut", "⅛", "⅛ tsp nutmeg"), ("choc", "", "2 oz 70% chocolate"),
        ("mi", "⅓", "⅓ cup milk"),
    ]

    /// "Clip it yourself" on a Reddit post Reddit won't let the app read (#213, #230), LIVE: real
    /// r/recipes posts through the real source (`RC_UITEST_LIVE_REDDIT`), whose `.json` read
    /// reddit.com refuses, so each opens in the clip view with the note and no Try again. The
    /// whole body shows (no "Read more" fade, no app or sign-in prompt) and scrolls with the
    /// finger. XCUITest can't drag a selection across a web page, so unseen spots the test lays on
    /// it (`RC_UITEST_CLIP_SCRIPT`, `clipSpots`) select by script when tapped, as ClipUITests'
    /// fixture buttons do. It opens on the guided steps (#251): the name is skipped, the
    /// ingredients and the steps are each selected and added with the step's confirm, which arms
    /// the next step, and the photo is skipped. Done with no name opens Review, which asks for it;
    /// typed, Save opens the recipe. Then a second post in the Text view, line by line (#286):
    /// its name and ingredients ticked (a real tap on the first line, then the last, ticks the
    /// run) and added the same way, and its steps (in the poster's comment) selected first: Steps
    /// disarmed, the lines ticked, then Steps' button. Saved.
    func test31_redditClipYourself() {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue, "-uiTestFlags", "reddit"]
        app.launchEnvironment["RC_UITEST_LIVE_REDDIT"] = "1"
        app.launchEnvironment["RC_UITEST_CLIP_SCRIPT"] = Self.clipSpots
        app.launch()
        self.app = app
        require(homePasteLink, "Home")
        mark("START")
        pause()

        let page = openBlockedPost(
            "https://www.reddit.com/r/recipes/comments/1wpafnm/braised_chicken_with_white_beans_and_zucchini/"
        )
        require(text("Step 1 of 4 · Tap the recipe's name"), "step 1")
        pause(2.5)
        // The whole post body, scrolled with the finger to its end.
        for _ in 0..<5 {
            page.swipeUp(velocity: .slow)
            pause(1.2)
        }
        pause(1.5)
        require(app.buttons["Skip"].firstMatch, "Skip").tap()
        require(text("Step 2 of 4 · Tap the ingredients"), "step 2")
        pause(2)
        selectAndConfirm(in: page, "ingredients")
        require(text("Step 3 of 4 · Tap the steps"), "step 3")
        pause(2)
        selectAndConfirm(in: page, "steps")
        require(text("Step 4 of 4 · Tap the recipe's photo"), "step 4")
        pause(2)
        app.buttons["Skip"].firstMatch.tap()
        pause(2.5)
        // No name yet: Done opens Review, which asks for it.
        require(app.navigationBars.buttons["Done"], "Done").tap()
        require(textContaining("Type the recipe"), "Review asking for the name")
        pause(2)
        let name = require(app.textFields["Name"], "the name field")
        name.tap()
        name.typeText("Braised Chicken with White Beans and Zucchini")
        pause(2)
        require(app.navigationBars.buttons["Save"], "Save").tap()
        require(bookmark, "the saved recipe", within: 20)
        pause(3)
        app.swipeUp()
        pause(2)
        back()

        // The Text view, line by line (#286): the post and its loaded comments as rows to tick.
        openBlockedPost("https://www.reddit.com/r/recipes/comments/1wixh7a/beef_bourguignon/")
        require(app.navigationBars.buttons["Text"], "Text").tap()
        require(app.buttons["clip.line.0"], "the Text view", within: 10)
        pause(2.5)
        require(text("Step 1 of 4 · Tap the recipe's name"), "step 1")
        pause(1.5)
        tickAndConfirm("Beef Bourguignon", nil)
        require(text("Step 2 of 4 · Tap the ingredients"), "step 2")
        pause(2)
        tickAndConfirm("800g beef chuck", "to serve")
        require(text("Step 3 of 4 · Tap the steps"), "step 3")
        pause(2)
        tickFirst("Marinate the beef overnight", "with parsley on top.", "STEPS")
        require(app.navigationBars.buttons["Done"], "Done").tap()
        pause(3)
        require(app.navigationBars.buttons["Save"], "Save").tap()
        require(bookmark, "the saved recipe", within: 20)
        require(textContaining("Clipped by you"), "the clip's credit")
        pause(3)
        app.swipeUp()
        // The fling settles slowly on a long recipe: long enough for the last screen to hold.
        pause(4.5)
    }

    /// "Clip it yourself" on a blog page (#37, guided since #251), LIVE: a real based.cooking page
    /// with no recipe data, read by the real blog source (`RC_UITEST_LIVE_PAGE`), so the app says
    /// it found none and offers Clip it yourself. It opens on step 1, Name armed, and each confirm
    /// arms the next step. Every pick is a real tap on the page's text or picture, and the page
    /// scrolls with the finger: the title; the ingredients list; the steps list; then Steps' button
    /// to go back, the paragraph under the list, and Steps' button again to add it (selected
    /// first); one Undo, and that paragraph again with its confirm; then the photo. Done, Review,
    /// Save.
    func test32_clipItYourself() {
        let link = "https://based.cooking/stracciatella-soup/"
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestSeed", Scenario.walkthrough.rawValue]
        app.launchEnvironment["RC_UITEST_LIVE_PAGE"] = link
        app.launch()
        self.app = app
        require(homePasteLink, "Home")
        mark("START")
        pause()
        pasteLink(link) { pause(0.8) }
        require(app.buttons["Clip it yourself"], "Clip it yourself", within: 60)
        pause(3)
        app.buttons["Clip it yourself"].tap()
        let page = app.webViews["clip.page"]
        let photo = require(page.images["Stracciatella soup"], "the page, loaded", within: 60)
        pause(3)

        func tapOnPage(_ element: XCUIElement, _ what: String) {
            require(element, what, within: 60)
            for _ in 0..<5 where !element.isHittable || element.frame.midY > app.frame.height * 0.7 {
                page.swipeUp(velocity: .slow)
                pause(1)
            }
            element.tap()
            pause(1.5)
        }
        func confirm(_ label: String) {
            require(app.buttons[label], label)
            pause(2)
            app.buttons[label].tap()
            pause(3)
        }

        require(text("Step 1 of 4 · Tap the recipe's name"), "step 1")
        pause(1)
        tapOnPage(page.staticTexts["Stracciatella soup"], "the title")
        confirm("Use as the name")

        require(text("Step 2 of 4 · Tap the ingredients"), "step 2")
        pause(1.5)
        tapOnPage(page.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "500 ml broth")).firstMatch, "an ingredient")
        confirm("Add 3 lines to Ingredients")

        require(text("Step 3 of 4 · Tap the steps"), "step 3")
        pause(1.5)
        tapOnPage(page.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Heat broth to boiling")).firstMatch, "a step")
        confirm("Add 6 lines to Steps")
        require(text("Step 4 of 4 · Tap the recipe's photo"), "step 4")
        pause(1)
        // A second block adds to the first: back to Steps, the line under the list, selected
        // first, then Steps' button adds it straight away.
        app.buttons["clip.field.STEPS"].tap()
        pause(2)
        let after = page.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "toasted bread")).firstMatch
        tapOnPage(after, "the line after the steps")
        require(app.buttons["Add 1 line to Steps"], "the selection")
        pause(1.5)
        app.buttons["clip.field.STEPS"].tap()
        pause(3)
        // Undo takes that add back (Steps again); the line is added again, with its confirm.
        require(app.buttons["Undo"], "Undo").tap()
        pause(3)
        tapOnPage(after, "the line after the steps")
        confirm("Add 1 line to Steps")
        require(text("Step 4 of 4 · Tap the recipe's photo"), "step 4 again")
        for _ in 0..<5 where !photo.isHittable || photo.frame.minY < app.frame.height * 0.12 {
            page.swipeDown(velocity: .slow)
            pause(1)
        }
        photo.tap()
        require(textContaining("Photo added"), "the photo added")
        pause(3.5)

        require(app.navigationBars.buttons["Done"], "Done").tap()
        pause(2.5)
        app.swipeUp()
        pause(2)
        require(app.navigationBars.buttons["Save"], "Save").tap()
        require(bookmark, "the saved recipe", within: 20)
        require(textContaining("Clipped by you"), "the clip's credit")
        pause(3)
        app.swipeUp()
        pause(4)
    }

    /// Types `link` into Home's field; Reddit refuses the read, and the post opens in the clip
    /// view with the note (after Reddit's own check in the page). Returns the page.
    private func openBlockedPost(_ link: String) -> XCUIElement {
        pasteLink(link) { pause(0.8) }
        require(text("Reddit didn't let the app read this post, so it's open here: select the recipe."), "the note", within: 60)
        XCTAssertFalse(app.buttons["Try again"].exists, "no Try again")
        let page = app.webViews["clip.page"]
        require(page.buttons["rc-walk-steps"], "the post, loaded", within: 90)
        pause(3.5)
        return page
    }

    /// Guided (#251): taps the unseen spot that selects `spot` (the text scrolls into view, then
    /// is selected), then the armed step's confirm ("Add 22 lines to Ingredients"): it is marked
    /// on the page, and the next step arms. The spots hold no text, so a tap on one reaches it
    /// whatever step is armed.
    private func selectAndConfirm(in page: XCUIElement, _ spot: String) {
        require(page.buttons["rc-walk-\(spot)"], "the \(spot) spot", within: 60).tap()
        require(app.buttons["clip.confirm"], "the \(spot) selected", within: 15)
        pause(2.5)
        app.buttons["clip.confirm"].tap()
        pause(3)
    }

    /// The Text view (#286): ticks `from`'s line, then `to`'s (the run between), then the armed
    /// step's confirm, and the next step arms.
    private func tickAndConfirm(_ from: String, _ to: String?) {
        tickLine(from)
        if let to { tickLine(to) }
        require(app.buttons["clip.confirm"], "\(from) ticked", within: 15)
        pause(2.5)
        app.buttons["clip.confirm"].tap()
        pause(3)
    }

    /// The Text view, selected first (#251): `field`'s button disarms its step, the lines are
    /// ticked ("N lines selected · tap the field they go in"), and the button adds them at once.
    private func tickFirst(_ from: String, _ to: String?, _ field: String) {
        require(app.buttons["clip.field.\(field)"], field).tap()
        pause(2)
        tickLine(from)
        if let to { tickLine(to) }
        require(textContaining("tap the field they go in"), "\(from) ticked", within: 15)
        pause(3)
        app.buttons["clip.field.\(field)"].tap()
        pause(3)
    }

    /// A real tap on the Text view's line holding `text`, swiped up into view first.
    private func tickLine(_ text: String) {
        let line = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH 'clip.line.' AND label CONTAINS %@", text)
        ).firstMatch
        let list = app.scrollViews["clip.text"]
        var swipes = 0
        while !(line.exists && line.isHittable) && swipes < 40 {
            (list.exists ? list : app as XCUIElement).swipeUp(velocity: .slow)
            swipes += 1
        }
        require(line, "the line \(text)").tap()
        pause(1.5)
    }

    /// Spots down the left edge, clear and unseen, each selecting from its first text to its
    /// last as a finger's drag would, after scrolling it into view below Reddit's header (the
    /// first post's lines; the second post is ticked in the Text view, #286). They wait for the
    /// post (not Reddit's check page).
    static let clipSpots = """
    (function () {
      var spots = {
        ingredients: [['2 pounds bone-in', 'Black pepper, to taste']],
        steps: [['Prepare the chicken:', 'remaining vinaigrette on the side.']]
      };
      function select(from, to) {
        to = to || from;
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT), node, start = null, offset = 0;
        while ((node = walker.nextNode())) {
          if (!start) {
            var i = node.data.indexOf(from);
            if (i < 0) continue;
            start = node; offset = i;
          }
          var j = node.data.indexOf(to, node === start ? offset : 0);
          if (j < 0) continue;
          var range = document.createRange();
          range.setStart(start, offset);
          range.setEnd(node, j + to.length);
          var top = range.getBoundingClientRect().top + window.scrollY - 140;
          window.scrollTo({ top: Math.max(0, top), behavior: 'smooth' });
          setTimeout(function () {
            var selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);
          }, 900);
          return true;
        }
        return false;
      }
      function lay() {
        if (/reddit\\.com$/.test(location.hostname) && !document.querySelector('shreddit-post')) {
          return setTimeout(lay, 500);
        }
        Object.keys(spots).forEach(function (name, n) {
          var spot = document.createElement('div');
          spot.setAttribute('role', 'button');
          spot.setAttribute('aria-label', 'rc-walk-' + name);
          spot.style.cssText = 'position:fixed;left:0;top:' + (30 + n * 10) + '%;width:44px;height:44px;' +
            'z-index:2147483647;background:transparent';
          spot.addEventListener('click', function (event) {
            event.preventDefault();
            event.stopPropagation();
            spots[name].some(function (pair) { return select(pair[0], pair[1]); });
          });
          document.documentElement.appendChild(spot);
        });
      }
      lay();
    })();
    """
}
