import XCTest

/// Walkthroughs 01–05: the tab bar, Week, Groceries, Pantry, menus and expiry reminders.
extension WalkthroughUITests {

    func test01_tabsAndWeek() {
        start(flags: ["mealPlan"])
        open("Chicken Adobo")
        recipeMenu("Add to plan")
        require(app.buttons["plan.confirm"], "the sheet's button").tap()
        pause()
        back()
        pause()
        tab("Week")
        planToday("Weeknight Chili")
        app.swipeUp()
        pause()
        require(app.buttons["toggleMonth"], "the Month switch").tap()
        pause(2.5)
        require(app.buttons["monthDay-\(today)"], "today in the grid").tap()
        pause(2)
        tab("Groceries")
        tab("Pantry")
        tab("Recipes")
    }

    func test02_groceries() {
        start(flags: ["mealPlan"])
        for title in ["Chicken Adobo", "Weeknight Chili"] {
            open(title)
            recipeMenu("Add to groceries")
            app.swipeUp()
            pause()
            require(app.buttons["addToGroceriesButton"], "the sheet's button").tap()
            pause()
            back()
            pause()
        }
        tab("Groceries")
        pause()
        type("milk\n", into: app.textFields["Add an item"])
        for item in ["chicken thighs", "soy sauce"] {
            require(line(item), item).tap()
            pause(2)
        }
        app.swipeUp()
        pause(2)
    }

    func test03_pantryAndWhatINeed() {
        start(flags: ["mealPlan"])
        tab("Pantry")
        for item in ["soy sauce", "garlic", "bay leaves", "white vinegar"] {
            type("\(item)\n", into: app.textFields["Add to the pantry"])
        }
        pause()
        tab("Week")
        planToday("Chicken Adobo")
        recipeMenu("What I need")
        pause(2)
        app.swipeUp()
        pause(2)
        require(app.buttons["Add to groceries"], "Add to groceries").tap()
        pause(2)
    }

    func test04_weeklyMenus() {
        start(flags: ["mealPlan"])
        tab("Week")
        planToday("Chicken Adobo")
        planToday("Spaghetti Carbonara")
        recipeMenu("Save week as menu…")
        let prompt = require(app.alerts.firstMatch, "Save week as menu")
        prompt.textFields.firstMatch.tap()
        prompt.textFields.firstMatch.typeText("Weeknights")
        pause()
        prompt.buttons["Save"].tap()
        pause()
        require(app.buttons["Next week"], "the next-week arrow").tap()
        pause()
        recipeMenu("Apply a menu…")
        require(app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'menu-'")).firstMatch, "the menu").tap()
        pause(2.5)
    }

    func test05_expiryReminders() {
        start(flags: ["mealPlan"])
        require(app.buttons["Settings"]).tap()
        pause()
        flipSwitch("Expiry reminders")
        pause()
    }
}
