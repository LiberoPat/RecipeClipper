import XCTest

/// Reusable weekly menus on the Week tab (#52), behind the tab flag: save this week as a menu,
/// add it to next week, then rename and delete it from the menus sheet.
final class WeekMenusUITests: RecipeUITestCase {

    private var today: Int64 {
        let now = Date()
        let offset = TimeInterval(TimeZone.current.secondsFromGMT(for: now))
        return Int64(((now.timeIntervalSince1970 + offset) / 86_400).rounded(.down))
    }

    private var meals: XCUIElementQuery {
        app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'meal-'"))
    }

    private func weekMenu(_ item: String) {
        require(app.buttons["More options"], "the Week menu").tap()
        require(app.buttons[item], item).tap()
    }

    /// Answers the alert showing (titled `alert`), replacing its field's text with `text` first.
    private func answer(_ alert: String, typing text: String? = nil, button: String) {
        let prompt = require(app.alerts.firstMatch, alert)
        if let text {
            let field = prompt.textFields.firstMatch
            field.tap()
            let old = (field.value as? String)?.count ?? 0
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old) + text)
        }
        prompt.buttons[button].tap()
        requireGone(prompt, alert)
    }

    func testSaveApplyRenameAndDeleteAMenu() {
        launch(.standard, flags: ["mealPlan"])
        require(app.tabBars.firstMatch.buttons["Week"], "the Week tab").tap()
        let add = app.buttons["addToDay-\(today)"]
        for _ in 0 ..< 5 where !add.isHittable { app.swipeUp() }
        require(add, "+ Add on today").tap()
        require(app.buttons["Miso Soup"], "history in the sheet").tap()
        require(meals.firstMatch, "the planned meal")

        weekMenu("Save week as menu…")
        answer("Save week as menu", typing: "Usual", button: "Save")

        require(app.buttons["Next week"], "the next-week arrow").tap()
        requireGone(meals.firstMatch, "this week's meal")
        weekMenu("Apply a menu…")
        require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'menu-'")).firstMatch, "the menu").tap()
        XCTAssertTrue(require(meals.firstMatch, "the added meal").label.contains("Miso Soup"))

        weekMenu("Apply a menu…")
        require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'menuOptions-'")).firstMatch, "the menu's options").tap()
        require(app.buttons["Rename"], "Rename").tap()
        answer("Rename menu", typing: "Weeknights", button: "Rename")
        require(app.staticTexts["Weeknights"], "the renamed menu")

        require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'menuOptions-'")).firstMatch, "the menu's options").tap()
        require(app.buttons["Delete"], "Delete").tap()
        answer("Delete menu", button: "Delete")
        requireGone(app.staticTexts["Weeknights"], "the deleted menu")
    }
}
