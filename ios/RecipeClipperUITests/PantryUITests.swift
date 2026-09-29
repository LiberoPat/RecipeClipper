import XCTest

/// The Pantry tab (#51), behind the tab flag: type an item, tap Ran out (#194) and it goes on the
/// grocery list by itself, with an "On list" tag that takes it off again (#146).
final class PantryUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }

    private func button(containing text: String) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private var onListTag: XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@ OR label == %@", "onList-", "On list")).firstMatch
    }

    func testRunningOutPutsAnItemOnGroceriesAndTheTagTakesItOff() {
        launch(.empty, flags: ["mealPlan"])
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        let field = require(app.textFields["Add to the pantry"], "Add to the pantry")
        field.tap()
        field.typeText("milk\n")
        require(text("Dairy & eggs"), "the dairy aisle")

        require(app.buttons["Ran out: milk"], "Ran out").tap()
        require(app.buttons["Restock: milk"], "Restock, once it has run out")
        require(text("Run out"), "the Run out section")
        require(onListTag, "the On list tag")
        assertAbsent(app.buttons["Undo"], "a snackbar")

        require(tabBar.buttons["Groceries"], "the Groceries tab").tap()
        require(button(containing: "milk"), "milk on the list")

        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(onListTag, "the On list tag").tap()
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

    /// Every state is reachable from something visible: tapping the row opens its sheet, whose
    /// stock control applies at once (#194).
    func testTheEditSheetMarksItRunningLow() {
        launch(.empty, flags: ["mealPlan"])
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
        require(onListTag, "the On list tag")
    }

    /// The row's menu offers only the state its button doesn't (#194): In stock → Running low,
    /// Running low → Restock, Run out → Running low.
    func testTheRowMenuNeverRepeatsTheButton() {
        launch(.empty, flags: ["mealPlan"])
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        let field = require(app.textFields["Add to the pantry"], "Add to the pantry")
        field.tap()
        field.typeText("garlic\n")

        require(pantryRow("garlic"), "garlic").press(forDuration: 1.2)
        let runningLow = require(app.buttons["Running low"], "In stock's menu: Running low")
        assertAbsent(app.buttons["Ran out"], "Ran out in the menu, beside the button")
        assertAbsent(app.buttons["Restock"], "Restock, while in stock")
        runningLow.tap()
        require(lowTag, "the Low tag")

        require(pantryRow("garlic"), "garlic").press(forDuration: 1.2)
        require(app.buttons["Restock"], "Running low's menu: Restock").tap()
        requireGone(lowTag, "the Low tag, once restocked")

        require(app.buttons["Ran out: garlic"], "Ran out").tap()
        require(app.buttons["Restock: garlic"], "Restock, once it has run out")
        require(pantryRow("garlic"), "garlic").press(forDuration: 1.2)
        require(app.buttons["Running low"], "Run out's menu: Running low")
        assertAbsent(app.buttons["Restock"], "Restock in the menu, beside the button")
        assertAbsent(app.buttons["Ran out"], "Ran out, once out")
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
    /// run out in the seed), Ran out, Running low from a swipe, and the edit sheet's control.
    func testMovesBetweenSectionsRedrawTheList() {
        launch(.walkthroughPantry, flags: ["mealPlan"])
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(app.buttons["Ran out: soy sauce"], "the pantry")
        requireLaidOut(["garlic": "Run out", "milk": "Run out", "soy sauce": "Oils, sauces & condiments"])

        reveal(app.buttons["Restock: garlic"], "garlic's Restock").tap()
        require(app.buttons["Ran out: garlic"], "garlic, back in stock")
        requireLaidOut(["garlic": "Fruit & vegetables", "onions": "Fruit & vegetables", "milk": "Run out"])

        reveal(app.buttons["Ran out: soy sauce"], "soy sauce's Ran out").tap()
        require(app.buttons["Restock: soy sauce"], "soy sauce, run out")
        requireLaidOut(["soy sauce": "Run out", "milk": "Run out", "olive oil": "Oils, sauces & condiments"])

        reveal(pantryRow("basmati rice"), "basmati rice").swipeLeft()
        require(app.buttons["Running low"], "the swipe's Running low").tap()
        require(lowTag, "the Low tag")
        requireLaidOut(["basmati rice": "Pasta, rice & grains", "soy sauce": "Run out"])

        reveal(pantryRow("soy sauce"), "soy sauce").tap()
        let control = require(app.segmentedControls["pantryEditStock"], "the sheet's stock control")
        control.buttons["In stock"].tap()
        require(app.buttons["pantryEditSave"], "Save").tap()
        require(app.buttons["Ran out: soy sauce"], "soy sauce, back in stock")
        requireLaidOut(["soy sauce": "Oils, sauces & condiments", "milk": "Run out", "garlic": "Fruit & vegetables"])
    }

    /// The walkthrough's case (#203): a swipe's action moves its own row out of its section while
    /// the swipe closes. Back and forth a few times, as the timing varies. The tree can be right
    /// while the screen isn't (the swiped cell left drawn over the Run out heading), so the
    /// heading's pixels are checked too, against how it looked before any move.
    func testSwipeMovesRedrawTheList() {
        launch(.walkthroughPantry, flags: ["mealPlan"])
        require(tabBar.buttons["Pantry"], "the Pantry tab").tap()
        require(app.buttons["Ran out: soy sauce"], "the pantry")
        let runOut = text("Run out")
        let drawn = pixels(reveal(runOut, "the Run out heading"))
        do { // TEMP sensitivity probe
            let other = pixels(pantryRow("garlic"))
            let d = Double(zip(other, drawn).map { abs(Int($0) - Int($1)) }.reduce(0, +)) / Double(drawn.count) / 255
            print("PROBE garlic-vs-heading \(d)")
            let again = pixels(runOut)
            let d2 = Double(zip(again, drawn).map { abs(Int($0) - Int($1)) }.reduce(0, +)) / Double(drawn.count) / 255
            print("PROBE heading-vs-heading \(d2) frame \(runOut.frame)")
            try? runOut.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: "/private/tmp/claude-502/-Users-oli-Downloads-RecipeClipper/16fed9c5-8eca-43ea-a868-c9bccb27aa0e/scratchpad/sw203/el-0.png"))
        }
        for _ in 0..<3 {
            reveal(pantryRow("garlic"), "garlic").swipeRight()
            require(app.buttons["Restock"], "the swipe's Restock").tap()
            requireLaidOut(["garlic": "Fruit & vegetables", "milk": "Run out"])
            requireDrawn(runOut, like: drawn, "the Run out heading, after Restock")
            reveal(pantryRow("garlic"), "garlic").swipeLeft()
            require(app.buttons["Ran out"], "the swipe's Ran out").tap()
            requireLaidOut(["garlic": "Run out", "onions": "Fruit & vegetables"])
            requireDrawn(runOut, like: drawn, "the Run out heading, after Ran out")
        }
    }

    /// An element as drawn on screen, shrunk to a small grey grid so a point's rounding doesn't
    /// count: what's under its frame, not what the accessibility tree says is there.
    private func pixels(_ element: XCUIElement) -> [UInt8] {
        let width = 48, height = 12
        var grid = [UInt8](repeating: 0, count: width * height)
        guard let image = element.screenshot().image.cgImage else { return grid }
        grid.withUnsafeMutableBytes { buffer in
            let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: width, space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
            )
            context?.interpolationQuality = .medium
            context?.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return grid
    }

    /// The element, revealed, looks as it did: a cell drawn over it changes its pixels.
    private func requireDrawn(_ element: XCUIElement, like before: [UInt8], _ what: String, file: StaticString = #filePath, line: UInt = #line) {
        let now = pixels(reveal(element, what))
        do { // TEMP
            let dir = "/private/tmp/claude-502/-Users-oli-Downloads-RecipeClipper/16fed9c5-8eca-43ea-a868-c9bccb27aa0e/scratchpad/sw203/"
            let n = Int(Date().timeIntervalSince1970)
            try? element.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "el-\(n).png"))
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "scr-\(n).png"))
            print("PROBE frame \(element.frame) at \(n)")
        }
        let difference = zip(now, before).map { abs(Int($0) - Int($1)) }.reduce(0, +)
        let mean = Double(difference) / Double(before.count) / 255
        XCTAssertLessThan(mean, 0.04, "\(what): drawn differently (mean difference \(mean))", file: file, line: line)
    }

    @discardableResult
    private func reveal(_ element: XCUIElement, _ what: String) -> XCUIElement {
        for _ in 0..<3 where !(element.exists && element.isHittable) { app.swipeUp() }
        for _ in 0..<4 where !(element.exists && element.isHittable) { app.swipeDown() }
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

