import XCTest

/// The Pantry tab (#51): type an item, run it out (#194) and it goes on the
/// grocery list by itself, with the Groceries tab's basket tag, which takes it off again (#146).
/// No row button since 2026-09-29: the sheet and the swipes change the stock. A touch and hold
/// starts selecting (#258).
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

    // MARK: - Counts at the top (#286)

    /// Three counts under the title, each read as one ("2, Run out"); a tap scrolls to the Run
    /// out section. While selecting they stay put, off.
    func testACountJumpsToItsRowsAndIsOffWhileSelecting() {
        launch(.walkthroughPantry)
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        let runOut = require(app.buttons["pantryCount-runOut"], "the Run out count")
        XCTAssertTrue(runOut.label.hasSuffix("Run out"), runOut.label)
        XCTAssertTrue(require(app.buttons["pantryCount-inStock"], "the In stock count").label.hasSuffix("In stock"))
        XCTAssertTrue(require(app.buttons["pantryCount-runningLow"], "the Running low count").label.hasSuffix("Running low"))
        runOut.tap()
        requireState(text("Run out"), "isHittable == true", "the Run out heading, scrolled to")
        XCTAssertLessThan(text("Run out").frame.maxY, tabBar.frame.minY, "the Run out heading, above the tab bar")
        require(pantryRow("garlic", "Run out"), "garlic, run out")

        for _ in 0..<3 { app.swipeDown() }
        reveal(pantryRow("soy sauce"), "soy sauce").press(forDuration: 0.8)
        requireState(selectedCount, "label == '1 selected'", "1 selected")
        requireState(app.buttons["pantryCount-inStock"], "isEnabled == false", "the In stock count, off while selecting")
        require(app.buttons["exitSelection"], "✕").tap()
        requireState(app.buttons["pantryCount-inStock"], "isEnabled == true", "the In stock count, on again")
    }

    // MARK: - Selecting several (#258)

    private var selectedCount: XCUIElement { app.staticTexts["selectedCount"] }

    /// A touch and hold starts selecting with that row checked; taps check two more; Remove takes
    /// all three with one Undo, which puts them all back.
    func testSelectThreeRemoveAndUndo() {
        launch(.walkthroughPantry)
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(pantryRow("soy sauce"), "the pantry")
        reveal(pantryRow("garlic"), "garlic").press(forDuration: 0.8)
        requireState(selectedCount, "label == '1 selected'", "1 selected")
        reveal(pantryRow("onions"), "onions").tap()
        reveal(pantryRow("chicken thighs"), "chicken thighs").tap()
        requireState(selectedCount, "label == '3 selected'", "3 selected")
        assertAbsent(app.segmentedControls["pantryEditStock"], "the edit sheet, while selecting")

        require(app.buttons["removeSelected"], "Remove").tap()
        requireGone(selectedCount, "the selection bar")
        require(text("3 items removed"), "the snackbar")
        assertAbsent(pantryRow("onions"), "onions, removed")
        require(app.buttons["Undo"], "Undo").tap()
        require(pantryRow("onions"), "onions, back")
        require(pantryRow("garlic"), "garlic, back")
        require(pantryRow("chicken thighs"), "chicken thighs, back")
    }

    /// A touch and hold, then a drag down without lifting, checks every row it passes (#258).
    func testATouchAndHoldThenADragChecksARun() {
        launch(.walkthroughPantry)
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        let first = reveal(pantryRow("chicken thighs"), "chicken thighs")
        let last = require(pantryRow("onions"), "onions")
        first.press(forDuration: 0.8, thenDragTo: last)
        let label = require(selectedCount, "the selection bar").label
        XCTAssertNotEqual(label, "1 selected", "the drag checked more than the row it began on")
        require(app.buttons["exitSelection"], "✕").tap()
        requireGone(selectedCount, "the selection bar")
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
    /// run out in the seed) and Run out from the edit sheet's control (the row's touch-and-hold
    /// menu did these until #258), Running low from a swipe, and the sheet again.
    func testMovesBetweenSectionsRedrawTheList() {
        launch(.walkthroughPantry)
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(pantryRow("soy sauce"), "the pantry")
        requireLaidOut(["garlic": "Run out", "milk": "Run out", "soy sauce": "Oils, sauces & condiments"])

        reveal(pantryRow("garlic"), "garlic").tap()
        require(app.segmentedControls["pantryEditStock"], "the sheet's stock control").buttons["In stock"].tap()
        require(app.buttons["pantryEditSave"], "Save").tap()
        require(pantryRow("garlic", "In stock"), "garlic, back in stock")
        requireLaidOut(["garlic": "Fruit & vegetables", "onions": "Fruit & vegetables", "milk": "Run out"])

        reveal(pantryRow("soy sauce"), "soy sauce").tap()
        require(app.segmentedControls["pantryEditStock"], "the sheet's stock control").buttons["Run out"].tap()
        require(app.buttons["pantryEditSave"], "Save").tap()
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
        // Likewise the selection bar, which lies over the top of the list while selecting
        // (#258): a tap on a row under it lands on the bar (its ✕ ended the selection once the
        // counts, #286, moved the rows).
        for _ in 0..<4 where element.exists && element.frame.minY < selectionBarBottom {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
                .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)))
            _ = element.waitForExistence(timeout: 0.6)
        }
        return require(element, what)
    }

    /// The bottom of the selection bar, or the top of the screen when not selecting.
    private var selectionBarBottom: CGFloat {
        let buttons = [app.buttons["exitSelection"], app.buttons["removeSelected"]].filter(\.exists)
        return buttons.map(\.frame.maxY).max().map { $0 + 8 } ?? 0
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
            let visible = onScreen().sorted { $0.frame.minY < $1.frame.minY }
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
        if expected.values.contains("Run out") {
            // The heading itself, not the Run out count box over the list (#286).
            let shown = { self.onScreen().contains { $0.heading && $0.name == "Run out" } }
            for _ in 0..<4 where !shown() { app.swipeUp() }
            XCTAssertTrue(shown(), "the Run out heading", file: file, line: line)
        }
    }

    /// The headings and rows fully on screen, from one snapshot of the tree. Asking each element
    /// for `exists` and `frame` costs a round trip to the app every time: about a hundred per
    /// check, which took each test above to nine minutes on CI (#296).
    private func onScreen() -> [(name: String, heading: Bool, frame: CGRect)] {
        guard let root = try? app.snapshot() else { return [] }
        var found: [(name: String, heading: Bool, frame: CGRect)] = []
        var seen = Set<String>()
        func visit(_ node: XCUIElementSnapshot) {
            // The count boxes over the list (#286) read "Run out" too: not a heading.
            if node.identifier.hasPrefix("pantryCount-") { return }
            // The first match of each, in tree order, as `firstMatch` took it.
            if node.elementType == .staticText, headings.contains(node.label), seen.insert(node.label).inserted {
                if root.frame.contains(node.frame) { found.append((node.label, true, node.frame)) }
            } else if node.elementType == .button,
                      let item = items.first(where: { node.label.hasPrefix("\($0), ") }), seen.insert(item).inserted {
                if root.frame.contains(node.frame) { found.append((item, false, node.frame)) }
            }
            node.children.forEach(visit)
        }
        visit(root)
        return found
    }
}

