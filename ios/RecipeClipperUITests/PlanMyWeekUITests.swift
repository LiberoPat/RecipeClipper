import XCTest

/// Plan my week with choices (#392, #367), end to end: the Ask an AI sheet's meal chips, start day and
/// source, and the meal-plan review of an answer that has meals, a recipe named by its link and a link
/// that matches nothing. Android's `AskAiSheetTest` / `MealPlanReviewScreenTest`.
final class PlanMyWeekUITests: RecipeUITestCase {

    private let flags = ["askAi", "planMyWeekPlus", "shareText", "mealPlanImport"]

    private func openPlanSheet() {
        launch(.standard, flags: flags)
        require(app.buttons["home.askAi"], "the Ask an AI tile").tap()
        require(app.buttons["askAiKind-planMyWeek"], "Plan my week").tap()
        require(app.descendants(matching: .any)["askAiPlan"], "the plan's choices")
    }

    private func screenshot(_ name: String) {
        let url = URL(fileURLWithPath: "/private/tmp/claude-502/rc392-shots/\(name).png")
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? app.screenshot().pngRepresentation.write(to: url)
    }

    func testThePlanSheetOffersTheMealsAStartDayAndASourceWithDinnerTicked() {
        openPlanSheet()

        XCTAssertTrue(require(app.buttons["askAiMeal-dinner"], "the Dinner chip").isSelected)
        XCTAssertFalse(require(app.buttons["askAiMeal-breakfast"], "the Breakfast chip").isSelected)
        app.buttons["askAiMeal-breakfast"].tap()
        XCTAssertTrue(app.buttons["askAiMeal-breakfast"].isSelected)
        XCTAssertTrue(require(app.buttons["askAiSource-NEW"], "New recipes").isSelected)
        require(app.buttons["planDay-\(todayEpochDay())"], "today on the start-day strip")
        screenshot("ios-sheet")

        app.buttons["askAiSource-MINE"].tap()
        require(app.buttons["askAiFromAll"], "All my recipes")
        require(app.descendants(matching: .any)["askAiPlanSent"], "how many recipes are sent")
        app.buttons["askAiSource-MIX"].tap()
        require(app.descendants(matching: .any)["askAiMixCount"], "the mix's count")
        app.buttons["askAiMixMore"].tap()

        app.buttons["askAiCopy"].tap()
        require(app.buttons["Copied"], "the prompt copied")
    }

    func testUntickingEveryMealAsksForOneAndTurnsCopyOff() {
        openPlanSheet()
        app.buttons["askAiMeal-dinner"].tap()
        require(app.descendants(matching: .any)["askAiMealsNone"], "the ask for a meal")
        XCTAssertFalse(app.buttons["askAiCopy"].isEnabled)
    }

    func testTheReviewShowsEachRowsMealAnExistingRecipeAndWhatCouldNotBeFound() {
        let day = todayEpochDay()
        func iso(_ offset: Int) -> String {
            let date = Date(timeIntervalSince1970: Double(day + offset) * 86_400)
            let f = DateFormatter()
            f.dateFormat = "yyyy-MM-dd"
            f.timeZone = TimeZone(identifier: "UTC")
            return f.string(from: date)
        }
        let answer = "[{\"@type\": \"Recipe\", \"name\": \"Porridge\", \"day\": \"\(iso(0))\", \"meal\": \"Breakfast\", "
            + "\"recipeIngredient\": [\"1 cup oats\"], \"recipeInstructions\": [\"Simmer.\"]},"
            + "{\"@type\": \"Recipe\", \"name\": \"Chicken Adobo\", \"isBasedOn\": \"https://example.com/adobo\", "
            + "\"day\": \"\(iso(1))\", \"meal\": \"Dinner\"},"
            + "{\"@type\": \"Recipe\", \"name\": \"Ghost\", \"isBasedOn\": \"https://example.com/gone\", "
            + "\"day\": \"\(iso(2))\", \"meal\": \"Lunch\"}]"
        launch(.standard, flags: flags, extraArguments: ["-uiTestPasteboard", answer])
        require(app.buttons["home.askAi"], "the Ask an AI tile").tap()
        require(app.buttons["askAiPaste"], "Paste the answer").tap()

        require(app.descendants(matching: .any)["mealplan.count"], "the meal-plan review")
        require(text("Porridge"), "the new recipe")
        require(app.descendants(matching: .any)["mealplan.already.1"], "Already in your recipes")
        require(app.descendants(matching: .any)["mealplan.notfound"], "the not-found note")
        require(app.buttons["mealplan.meal.0"], "the meal button of the first row")
        XCTAssertTrue(app.buttons["mealplan.meal.0"].label.contains("Breakfast"))
        screenshot("ios-review")
        assertAbsent(text("Ghost"), "a recipe made up for a link that matches nothing")
    }

    /// Today as an epoch day, the way the app counts it (the phone's calendar).
    private func todayEpochDay() -> Int64 {
        let now = Date()
        let offset = TimeZone.current.secondsFromGMT(for: now)
        return Int64((now.timeIntervalSince1970 + Double(offset)) / 86_400)
    }
}
