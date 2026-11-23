import XCTest

/// Fresh food (#396), end to end: chicken thighs planned five days out are Buy later in Shop for this
/// week, with the day they are for and what they keep, and the Week says so under the meal with one
/// swap; the swap moves them to the first day, so they are Buy now. Nothing moves until the tap.
final class FreshFoodUITests: RecipeUITestCase {

    private var tabBar: XCUIElement { app.tabBars.firstMatch }

    /// Today as the app counts it: whole days since 1970 on the local calendar.
    private var today: Int64 {
        let now = Date()
        let offset = TimeInterval(TimeZone.current.secondsFromGMT(for: now))
        return Int64(((now.timeIntervalSince1970 + offset) / 86_400).rounded(.down))
    }

    private func plan(_ title: String, on day: Int64) {
        // The day strip's button brings the day to the top. A swipe moves about 660 pt and the list shows
        // about 500, so swiping to a button below the fold can skip past it (it landed 3 pt under the
        // header on one machine and past the window on CI's).
        if day != today { require(app.buttons["stripDay-\(day)"], "the strip's day").tap() }
        let add = app.buttons["addToDay-\(day)"]
        for _ in 0 ..< 8 where !add.isHittable { app.swipeUp() }
        require(add, "+ Add on the day").tap()
        require(app.buttons[title], "\(title) in the sheet").tap()
        require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'meal-' AND label CONTAINS %@", title)).firstMatch, "\(title) planned")
    }

    private func openShopForThisWeek() {
        require(app.buttons["More options"], "the Week menu").tap()
        require(menuItem("Shop for this week"), "Shop for this week").tap()
        require(app.staticTexts["Shop for this week"], "the screen's title")
    }

    func testPerishablesFiveDaysOutAreBuyLaterAndTheSwapBringsThemBackToNow() {
        launch(.standard, flags: ["shopForWeek", "freshFood", "proUnlock"])
        require(tabBar.buttons["Plan"], "the Plan tab").tap()
        require(app.staticTexts["weekRange"], "the Week")
        plan("Spaghetti Carbonara", on: today)
        plan("Chicken Adobo", on: today + 5)

        // The note under the late meal, and nothing moved by it: the day's strip button brings it to the top.
        require(app.buttons["stripDay-\(today + 5)"], "the strip's day").tap()
        let note = app.staticTexts["Chicken thighs: keeps about 1-2 days once bought. Cook it earlier?"]
        require(note, "the note under Chicken Adobo")
        require(app.buttons["freshSwap"], "the swap")
        XCTAssertTrue(app.buttons["freshSwap"].label.hasPrefix("Swap with"))

        // Shop for this week: the chicken waits for a second trip; the spaghetti doesn't.
        openShopForThisWeek()
        require(app.staticTexts["Buy now"], "Buy now")
        require(app.staticTexts["400 g spaghetti"], "the spaghetti, to buy now")
        for _ in 0 ..< 4 where !app.buttons["addLaterToGroceries"].exists { app.swipeUp() }
        require(app.buttons["addLaterToGroceries"], "the later group's own button")
        let laterFor = app.staticTexts.matching(NSPredicate(format: "identifier == 'laterFor'")).firstMatch
        require(laterFor, "the day the chicken is for")
        XCTAssertTrue(laterFor.label.hasPrefix("For "), laterFor.label)
        require(app.staticTexts["Keeps about 1-2 days once bought"], "what the group keeps")
        require(app.staticTexts["Buy later in the week"], "Buy later in the week")
        back()

        // One tap swaps the two meals' days, so the chicken is for today: Buy now, one list again.
        require(app.buttons["freshSwap"], "the swap").tap()
        requireGone(note, "the note after the swap")
        openShopForThisWeek()
        require(app.staticTexts["To buy"], "one To buy again")
        XCTAssertFalse(app.staticTexts["Buy later in the week"].exists)
    }
}
