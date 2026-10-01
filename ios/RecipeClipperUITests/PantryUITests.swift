import XCTest

/// The Pantry tab (#51): type an item, run it out (#194) and it goes on the
/// grocery list by itself, with the Groceries tab's basket tag, which takes it off again (#146).
/// No row button since 2026-09-29: the sheet, the menu and the swipes change the stock.
final class PantryUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }

    private func button(containing text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private var onListTag: XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "onList-")).firstMatch
    }

    func testRunningOutPutsAnItemOnGroceriesAndTheTagTakesItOff() {
        launch(.empty)
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        let field = require(app.textFields["Add to the pantry"], "Add to the pantry")
        field.tap()
        field.typeText("milk\n")
        require(text("Dairy & eggs"), "the dairy aisle")

        require(pantryRow("milk"), "milk").swipeLeft()
        require(app.buttons["Ran out"], "the swipe's Ran out").tap()
        require(pantryRow("milk", "Run out"), "milk, run out")
        require(text("Run out"), "the Run out section")
        let tag = require(onListTag, "the basket tag")
        XCTAssertEqual(tag.label, "On your grocery list")
        require(pantryRow("milk", "On your grocery list"), "the row, read with the tag")
        assertAbsent(app.buttons["Undo"], "a snackbar")

        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        require(button(containing: "milk"), "milk on the list")

        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(onListTag, "the basket tag").tap()
        requireGone(onListTag, "the tag, once off the list")
        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        requireGone(button(containing: "milk"), "milk, off the list")
    }

    private var lowTag: XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'low-'")).firstMatch
    }

    private func pantryRow(_ name: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "\(name), ")).firstMatch
    }

    /// The row as VoiceOver reads it, holding `state` ("Run out", "In stock", …).
    private func pantryRow(_ name: String, _ state: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@ AND label CONTAINS %@", "\(name), ", ", \(state)")).firstMatch
    }

    /// Every state is reachable from something visible: tapping the row opens its sheet, whose
    /// stock control applies at once (#194).
    func testTheEditSheetMarksItRunningLow() {
        launch(.empty)
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        let field = require(app.textFields["Add to the pantry"], "Add to the pantry")
        field.tap()
        field.typeText("garlic\n")

        require(pantryRow("garlic"), "garlic").tap()
        let control = require(app.segmentedControls["pantryEditStock"], "the sheet's stock control")
        requireState(control.buttons["In stock"], "isSelected == true", "In stock, selected")
        control.buttons["Running low"].tap()
        requireState(control.buttons["Running low"], "isSelected == true", "Running low, selected")
        require(app.buttons["pantryEditSave"], "Save").tap()
        require(lowTag, "the Low tag")
        require(onListTag, "the basket tag")
    }

    /// With no row button (owner, 2026-09-29), the row's menu offers both other states (#194):
    /// In stock → Running low, Ran out; Running low → Restock, Ran out; Run out → Restock,
    /// Running low.
    func testTheRowMenuOffersBothOtherStates() {
        launch(.empty)
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        let field = require(app.textFields["Add to the pantry"], "Add to the pantry")
        field.tap()
        field.typeText("garlic\n")

        require(pantryRow("garlic"), "garlic").press(forDuration: 1.2)
        let runningLow = require(app.buttons["Running low"], "In stock's menu: Running low")
        require(app.buttons["Ran out"], "In stock's menu: Ran out")
        assertAbsent(app.buttons["Restock"], "Restock, while in stock")
        runningLow.tap()
        require(lowTag, "the Low tag")

        require(pantryRow("garlic"), "garlic").press(forDuration: 1.2)
        require(app.buttons["Restock"], "Running low's menu: Restock")
        require(app.buttons["Ran out"], "Running low's menu: Ran out").tap()
        requireGone(lowTag, "the Low tag, once run out")
        require(pantryRow("garlic", "Run out"), "garlic, run out")

        require(pantryRow("garlic"), "garlic").press(forDuration: 1.2)
        require(app.buttons["Running low"], "Run out's menu: Running low")
        require(app.buttons["Restock"], "Run out's menu: Restock").tap()
        require(pantryRow("garlic", "In stock"), "garlic, back in stock")
    }

    // MARK: - Moves between sections (#203)

    /// The `walkthroughPantry` kitchen's aisles, as headed, and the Run out heading.
    private let headings = [
        "Fruit & vegetables", "Meat", "Dairy & eggs", "Pasta, rice & grains", "Oils, sauces & condiments", "Run out",
    ]
    private let items = [
        "chicken thighs", "white vinegar", "soy sauce", "garlic", "basmati rice", "olive oil", "onions", "milk",
    ]

    /// Moving a row between sections leaves it under the right heading, with no blank row left
    /// behind and the Run out heading shown while anything has run out (#203): Restock (garlic is
    /// run out in the seed) and Ran out from the row's menu, Running low from a swipe, and the
    /// edit sheet's control.
    func testMovesBetweenSectionsRedrawTheList() {
        launch(.walkthroughPantry)
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(pantryRow("soy sauce"), "the pantry")
        requireLaidOut(["garlic": "Run out", "milk": "Run out", "soy sauce": "Oils, sauces & condiments"])

        reveal(pantryRow("garlic"), "garlic").press(forDuration: 1.2)
        require(app.buttons["Restock"], "the menu's Restock").tap()
        require(pantryRow("garlic", "In stock"), "garlic, back in stock")
        requireLaidOut(["garlic": "Fruit & vegetables", "onions": "Fruit & vegetables", "milk": "Run out"])

        reveal(pantryRow("soy sauce"), "soy sauce").press(forDuration: 1.2)
        require(app.buttons["Ran out"], "the menu's Ran out").tap()
        require(pantryRow("soy sauce", "Run out"), "soy sauce, run out")
        requireLaidOut(["soy sauce": "Run out", "milk": "Run out", "olive oil": "Oils, sauces & condiments"])

        reveal(pantryRow("basmati rice"), "basmati rice").swipeLeft()
        require(app.buttons["Running low"], "the swipe's Running low").tap()
        require(lowTag, "the Low tag")
        requireLaidOut(["basmati rice": "Pasta, rice & grains", "soy sauce": "Run out"])

        reveal(pantryRow("soy sauce"), "soy sauce").tap()
        let control = require(app.segmentedControls["pantryEditStock"], "the sheet's stock control")
        control.buttons["In stock"].tap()
        require(app.buttons["pantryEditSave"], "Save").tap()
        require(pantryRow("soy sauce", "In stock"), "soy sauce, back in stock")
        requireLaidOut(["soy sauce": "Oils, sauces & condiments", "milk": "Run out", "garlic": "Fruit & vegetables"])
    }

    /// The walkthrough's case (#203): a swipe's action moves its own row out of its section while
    /// the swipe closes. Back and forth a few times, as the timing varies. This checks the tree,
    /// which can be right while the screen isn't (the swiped cell left drawn in its old place,
    /// #203, #216); XCUITest's screenshots showed it right too, so only a recording of the
    /// walkthrough (ios-25) shows that.
    func testSwipeMovesRedrawTheList() {
        launch(.walkthroughPantry)
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(pantryRow("soy sauce"), "the pantry")
        for _ in 0..<3 {
            reveal(pantryRow("garlic"), "garlic").swipeRight()
            require(app.buttons["Restock"], "the swipe's Restock").tap()
            requireLaidOut(["garlic": "Fruit & vegetables", "milk": "Run out"])
            reveal(pantryRow("garlic"), "garlic").swipeLeft()
            require(app.buttons["Ran out"], "the swipe's Ran out").tap()
            requireLaidOut(["garlic": "Run out", "onions": "Fruit & vegetables"])
        }
    }

    private func reveal(_ element: XCUIElement, _ what: String) -> XCUIElement {
        for _ in 0..<3 where !(element.exists && element.isHittable) { app.swipeUp() }
        for _ in 0..<4 where !(element.exists && element.isHittable) { app.swipeDown() }
        // Hittable even under the tab bar, where a swipe would land on the bar: drag until it
        // clears it.
        for _ in 0..<4 where element.exists && element.frame.maxY > tabBar.frame.minY - 8 {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6))
                .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)))
            _ = element.waitForExistence(timeout: 0.6)
        }
        return require(element, what)
    }

    /// Each named row sits under its heading (the nearest one above it), and no gap in the list
    /// is tall enough to be a blank row. Checked where the list stands, then from the top, then
    /// twice scrolled down.
    private func requireLaidOut(_ expected: [String: String], file: StaticString = #filePath, line: UInt = #line) {
        var placed = Set<String>()
        for pass in 0..<4 {
            if pass == 1 { for _ in 0..<3 { app.swipeDown() } } else if pass > 1 { app.swipeUp() }
            // Let the move's animation and the scroll settle.
            _ = app.staticTexts["#203 settle"].waitForExistence(timeout: 1.5)
            let screen = app.frame
            var visible: [(name: String, heading: Bool, frame: CGRect)] = []
            for heading in headings {
                let element = app.staticTexts[heading].firstMatch
                if element.exists, screen.contains(element.frame) { visible.append((heading, true, element.frame)) }
            }
            for item in items {
                let element = pantryRow(item)
                if element.exists, screen.contains(element.frame) { visible.append((item, false, element.frame)) }
            }
            visible.sort { $0.frame.minY < $1.frame.minY }
            for (a, b) in zip(visible, visible.dropFirst()) {
                XCTAssertLessThan(b.frame.minY - a.frame.maxY, 30, "a blank row between \(a.name) and \(b.name)", file: file, line: line)
            }
            for (index, entry) in visible.enumerated() where !entry.heading {
                guard let want = expected[entry.name], let heading = visible[..<index].last(where: \.heading) else { continue }
                XCTAssertEqual(heading.name, want, "\(entry.name)'s heading", file: file, line: line)
                placed.insert(entry.name)
            }
        }
        XCTAssertEqual(placed, Set(expected.keys), "the rows seen under a heading", file: file, line: line)
        if expected.values.contains("Run out") { reveal(text("Run out"), "the Run out heading") }
    }
}

