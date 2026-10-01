import XCTest

/// The Week tab (#49): plan a recipe on today from "+ Add", open it, and
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
        launch(.standard)
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
        launch(.standard)
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

    /// What I need (#51) draws every row of both sections (#185). The walkthrough pantry has
    /// chicken thighs, soy sauce and white vinegar in stock and garlic out, so a planned Adobo
    /// has at least three rows to buy and three in the pantry. Both sections sit in one
    /// LazyVStack; with ids repeated across them, the first pantry rows left a blank gap.
    func testWhatINeedDrawsEveryRowOfBothSections() {
        launch(.walkthroughPantry)
        require(tabBar.buttons["Week"], "the Week tab").tap()
        planToday("Chicken Adobo")
        require(app.buttons["More options"], "the Week menu").tap()
        require(app.buttons["What I need"], "What I need").tap()
        require(app.staticTexts["garlic"], "the first row to buy")

        // Top to bottom: To buy, then In your pantry.
        for name in ["garlic", "bay leaves", "black peppercorns", "chicken thighs", "soy sauce", "white vinegar"] {
            let row = app.staticTexts[name]
            for _ in 0 ..< 4 where !(row.exists && row.isHittable) { app.swipeUp() }
            require(row, name)
            XCTAssertTrue(row.isHittable, "\(name) is drawn")
        }
    }

    /// "Add this week's ingredients" (#50) lists every planned recipe's lines, not only the
    /// first recipe's (#185: each recipe's lines were keyed by offset alone).
    func testTheWeeksGrocerySheetListsEveryRecipesLines() {
        openWeek()
        planToday("Chicken Adobo")
        planToday("Spaghetti Carbonara")
        require(app.buttons["More options"], "the Week menu").tap()
        require(app.buttons["Add this week's ingredients"], "Add this week's ingredients").tap()

        for text in ["2 lb chicken thighs", "1/2 cup soy sauce", "400 g spaghetti", "4 egg yolks"] {
            let line = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'sheetLine-' AND label CONTAINS %@", text)).firstMatch
            for _ in 0 ..< 3 where !(line.exists && line.isHittable) { app.swipeUp() }
            require(line, text)
            XCTAssertTrue(line.isHittable, "\(text) is drawn")
        }
    }

    /// "+ Add" on today, then `title` from the sheet's history, and waits for it on the Week.
    private func planToday(_ title: String) {
        let add = app.buttons["addToDay-\(today)"]
        for _ in 0 ..< 5 where !add.isHittable { app.swipeUp() }
        require(add, "+ Add on today").tap()
        require(app.buttons[title], "\(title) in the sheet").tap()
        require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'meal-' AND label CONTAINS %@", title)).firstMatch, "\(title) planned")
    }

    /// Rolling weeks (#232): the days open with today at the top, › snaps to the next seven
    /// days from today, ‹ back, and "Today" (only away from this week) returns.
    func testTheDaysOpenOnTodayAndTheArrowsSnapBetweenWeeks() {
        openWeek()
        let range = app.staticTexts["weekRange"]
        let thisWeek = range.label
        XCTAssertTrue(require(app.buttons["addToDay-\(today)"], "today's + Add").isHittable)
        XCTAssertFalse(app.buttons["todayButton"].exists)

        app.buttons["Next week"].tap()
        require(app.buttons["todayButton"], "Today, away from this week")
        let nextWeek = range.label
        XCTAssertNotEqual(nextWeek, thisWeek)
        XCTAssertTrue(require(app.buttons["addToDay-\(today + 7)"], "the next week's first day").isHittable)

        app.buttons["Previous week"].tap()
        requireGone(app.buttons["todayButton"], "Today, back on this week")
        XCTAssertEqual(range.label, thisWeek)

        app.buttons["Next week"].tap()
        app.buttons["Next week"].tap()
        require(app.buttons["todayButton"], "Today").tap()
        requireGone(app.buttons["todayButton"], "Today, back on this week")
        XCTAssertEqual(range.label, thisWeek)
        XCTAssertTrue(app.buttons["addToDay-\(today)"].isHittable)
    }

    /// The month view (#52): Week ↔ Month, and a tapped day opens its week. The grid's first
    /// row draws (#185: it shared its ids with the weekday headings); the 1st is always in it.
    func testTheMonthViewOpensTheWeekOfATappedDay() {
        openWeek()

        require(app.buttons["toggleMonth"], "the Month switch").tap()
        require(app.staticTexts["monthTitle"], "the month")
        let first = today - Int64(Calendar.current.component(.day, from: Date()) - 1)
        require(app.buttons["monthDay-\(first)"], "the 1st, in the first row")
        require(app.buttons["monthDay-\(today)"], "today in the grid").tap()
        require(app.staticTexts["weekRange"], "the week again")
        XCTAssertEqual(app.buttons["toggleMonth"].label, "Month")
    }
}
