import XCTest

// Walkthroughs 35–37: the pantry's amounts beside the week's (#302), Use it up (#305) and text
// shared in (#306). Each flag is turned on as Developer settings would (a UI-test launch turns
// every flag off but the ones it names).

extension WalkthroughUITests {

    /// An amount typed with the name (#302): "3 onions", "2 cans tomatoes" and "3 lb chicken
    /// thighs" into Add to the pantry, each shown on its row at once; then the Adobo planned twice
    /// and What I need's "You have 3 lb · this week's recipes use 4 lb" under In your pantry.
    func test35_pantryAmounts() {
        startAsShipped()
        tab("Pantry")
        for item in ["3 onions", "2 cans tomatoes", "3 lb chicken thighs"] {
            type("\(item)\n", into: app.textFields["Add to the pantry"])
            pause(1.2)
        }
        pause(2.5)
        tab("Week")
        planToday("Chicken Adobo")
        plan(day: today + 1, "Chicken Adobo")
        recipeMenu("What I need")
        pause(2)
        scrollTo(textContaining("this week's recipes use"), "the pantry's amount beside the week's")
        pause(4)
    }

    /// Week's "+ Add" on `day`, then a recipe from the sheet's history.
    func plan(day: Int64, _ title: String) {
        let add = app.buttons["addToDay-\(day)"]
        for _ in 0..<5 where !add.isHittable { app.swipeUp(); pause(0.6) }
        require(add, "+ Add on day \(day)").tap()
        pause()
        require(app.buttons[title], "\(title) in the sheet").tap()
        pause()
    }

    /// Use it up (#305): the soy sauce, due tomorrow, says "2 saved recipes use it"; the sheet
    /// ("Soy sauce · use by tomorrow", its recipes A–Z) plans one for tomorrow's dinner, and
    /// the Week shows it.
    func test36_useItUp() {
        start(flags: ["useItUp"], scenario: .walkthroughUseItUp)
        tab("Pantry")
        let line = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'use-it-up-'")).firstMatch
        scrollTo(line, "the Use it up line")
        // Clear of the tab bar (hittable under it, but hidden from the viewer): a short drag up.
        for _ in 0..<4 where line.frame.maxY > tabBar.frame.minY - 80 {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.65))
                .press(forDuration: 0.1, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.35)))
            pause(0.8)
        }
        pause(3)
        line.tap()
        require(text("Soy sauce · use by tomorrow"), "the sheet's heading")
        pause(3)
        require(app.buttons["Plan tomorrow"].firstMatch, "Plan tomorrow").tap()
        require(app.buttons["Planned tomorrow"], "planned")
        pause(2.5)
        // Closes the sheet: dragged down by its top edge (a swipe inside only scrolls its list).
        let sheet = app.descendants(matching: .any)["useItUpSheet"].firstMatch
        let top = sheet.exists ? sheet.frame.minY + 24 : app.frame.midY
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: app.frame.midX, dy: top))
            .press(forDuration: 0.2, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.98)))
        pause(1.5)
        tab("Week")
        let meal = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'meal-'")).firstMatch
        scrollTo(meal, "tomorrow's dinner on the Week")
        pause(3.5)
    }
}
