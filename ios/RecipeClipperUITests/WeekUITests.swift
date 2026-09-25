import XCTest

/// The Week tab (#49), behind the tab flag: plan a recipe on today from "+ Add", open it, and
/// remove it again with Undo; plan one from the recipe screen's "Add to plan".
final class WeekUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }

    /// Today as the app counts it: whole days since 1970 on the local calendar.
    private var today: Int64 {
        let now = Date()
        let offset = TimeInterval(TimeZone.current.secondsFromGMT(for: now))
        return Int64(((now.timeIntervalSince1970 + offset) / 86_400).rounded(.down))
    }

    private func openWeek() {
        launch(.standard, flags: ["mealPlan"])
        require(tabBar.buttons["Week"], "the Week tab").tap()
        require(app.staticTexts["weekRange"], "the Week")
    }

    func testPlusOnTodayPlansARecipeThatOpensAndCanBeRemovedWithUndo() {
        openWeek()

        let add = app.buttons["addToDay-\(today)"]
        for _ in 0 ..< 5 where !add.isHittable { app.swipeUp() }
        require(add, "+ Add on today").tap()
        require(app.buttons["Miso Soup"], "history in the sheet").tap()

        let meal = require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'meal-'")).firstMatch, "the planned meal")
        XCTAssertTrue(meal.label.contains("Miso Soup"))

        // Undo is tapped as soon as the four-second snackbar shows (see GroceriesUITests).
        meal.press(forDuration: 1.0)
        require(app.buttons["Remove from plan"], "the long-press menu").tap()
        require(app.buttons["Undo"], "the snackbar").tap()
        let back = require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'meal-'")).firstMatch, "the meal, back")

        // Without Undo, the removal stands.
        back.press(forDuration: 1.0)
        require(app.buttons["Remove from plan"], "the long-press menu").tap()
        requireGone(back, "the removed meal")
    }

    func testAddToPlanFromTheRecipeMenu() {
        launch(.standard, flags: ["mealPlan"])
        require(app.staticTexts["Chicken Adobo"], "Continue cooking").tap()
        require(app.buttons["More options"], "the recipe menu").tap()
        require(app.buttons["Add to plan"], "Add to plan").tap()
        require(app.buttons["plan.confirm"], "the sheet's button").tap()
        requireGone(app.buttons["plan.confirm"], "the sheet")

        back()
        require(tabBar.buttons["Week"], "the Week tab").tap()
        let meal = require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'meal-'")).firstMatch, "the planned meal")
        XCTAssertTrue(meal.label.contains("Chicken Adobo"))
    }

    /// The month view (#52): Week ↔ Month, and a tapped day opens its week.
    func testTheMonthViewOpensTheWeekOfATappedDay() {
        openWeek()

        require(app.buttons["toggleMonth"], "the Month switch").tap()
        require(app.staticTexts["monthTitle"], "the month")
        require(app.buttons["monthDay-\(today)"], "today in the grid").tap()
        require(app.staticTexts["weekRange"], "the week again")
        XCTAssertEqual(app.buttons["toggleMonth"].label, "Month")
    }
}
