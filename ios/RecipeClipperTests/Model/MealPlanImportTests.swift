import XCTest
@testable import RecipeClipper

/// A week of AI recipes in shared text (#352): the "Plan my week" answer, read for the review.
/// Android's `MealPlanImportTest`; the corpus's `PlanText(…)` rows pin the rest.
final class MealPlanImportTests: XCTestCase {

    private func recipe(_ name: String, _ day: String?) -> String {
        let dayField = day.map { "\"day\": \"\($0)\", " } ?? ""
        return "{\"@context\": \"https://schema.org\", \"@type\": \"Recipe\", \"name\": \"\(name)\", \(dayField)"
            + "\"recipeYield\": \"2 servings\", \"recipeIngredient\": [\"1 cup rice\", \"2 eggs\"], "
            + "\"recipeInstructions\": [{\"@type\": \"HowToStep\", \"text\": \"Cook.\"}]}"
    }

    private var week: String { "[" + recipe("Fried rice", "2026-11-23") + ",\n" + recipe("Omelette", "2026-11-24") + "]" }

    private func day(_ y: Int, _ m: Int, _ d: Int) -> Int64 { PlanDays.epochDay(year: y, month: m, dayOfMonth: d) }

    func testAnArrayOfRecipesWithDaysIsAPlanInOrderEachOnItsDay() throws {
        let plan = try XCTUnwrap(MealPlanImport.read(week))
        XCTAssertEqual(plan.items.map(\.recipe.name), ["Fried rice", "Omelette"])
        XCTAssertEqual(plan.items.map(\.day), [day(2026, 11, 23), day(2026, 11, 24)])
        XCTAssertEqual(plan.items[0].recipe.ingredients, ["1 cup rice", "2 eggs"])
        XCTAssertEqual(plan.items[1].recipe.instructions, ["Cook."])
        XCTAssertEqual(plan.items[0].recipe.yield, "2 servings")
    }

    func testAFenceAndProseAroundItDoNotMatter() {
        let text = "Here is your week! [Enjoy]\n\n```json\n\(week)\n```\n\nShall I swap a day? {just ask}"
        XCTAssertEqual(MealPlanImport.read(text)?.items.count, 2)
        XCTAssertEqual(MealPlanImport.read(week.replacingOccurrences(of: "\n", with: "\r\n"))?.items.count, 2)
    }

    func testOneRecipeOrRecipesWithNoDayAreNotAPlan() {
        XCTAssertNil(MealPlanImport.read(recipe("Fried rice", "2026-11-23")))
        XCTAssertNil(MealPlanImport.read("[" + recipe("Fried rice", "2026-11-23") + "]"))
        XCTAssertNil(MealPlanImport.read("[" + recipe("A", nil) + "," + recipe("B", nil) + "]"))
        XCTAssertNil(MealPlanImport.read("No recipe here, just 2026-11-23."))
    }

    func testAPlanMayLeaveSomeRecipesWithoutAUsableDay() throws {
        let plan = try XCTUnwrap(MealPlanImport.read(
            "[" + recipe("A", "2026-11-23") + "," + recipe("B", nil) + "," + recipe("C", "Tuesday") + ","
                + recipe("D", "2026-02-30") + "]"
        ))
        XCTAssertEqual(plan.items.map(\.day), [day(2026, 11, 23), nil, nil, nil])
    }

    func testAnItemListAndAGraphAreReadAsArrays() throws {
        let list = "{\"@type\": \"ItemList\", \"itemListElement\": ["
            + "{\"@type\": \"ListItem\", \"position\": 1, \"day\": \"2026-11-23\", \"item\": \(recipe("A", nil))},"
            + "{\"@type\": \"ListItem\", \"position\": 2, \"item\": \(recipe("B", "2026-11-24"))}]}"
        let plan = try XCTUnwrap(MealPlanImport.read(list))
        XCTAssertEqual(plan.items.map(\.recipe.name), ["A", "B"])
        XCTAssertEqual(plan.items.map(\.day), [day(2026, 11, 23), day(2026, 11, 24)])
        let graph = "{\"@context\": \"https://schema.org\", \"@graph\": [\(recipe("A", "2026-11-23")), \(recipe("B", "2026-11-24"))]}"
        XCTAssertEqual(MealPlanImport.read(graph)?.items.count, 2)
    }

    func testAThingThatIsNotARecipeInTheArrayIsSkipped() {
        let text = "[" + recipe("A", "2026-11-23") + ", {\"@type\": \"Thing\", \"name\": \"x\"}, 7, "
            + recipe("B", "2026-11-24") + "]"
        XCTAssertEqual(MealPlanImport.read(text)?.items.map(\.recipe.name), ["A", "B"])
    }

    func testNoMoreThanAMonthOfRecipesAreKept() {
        let many = "[" + (1...40).map { recipe("R\($0)", "2026-11-23") }.joined(separator: ",") + "]"
        XCTAssertEqual(MealPlanImport.read(many)?.items.count, MealPlanImport.maxRecipes)
    }

    func testARecipesUrlAndImageAreNeverUsed() {
        let text = week.replacingOccurrences(
            of: "\"name\": \"Fried rice\"",
            with: "\"name\": \"Fried rice\", \"url\": \"https://made-up.example/r\", \"image\": \"https://made-up.example/i.jpg\""
        )
        XCTAssertEqual(MealPlanImport.read(text)?.items[0].recipe.sourceUrl, "")
    }

    func testBrokenOrTooDeepJsonIsNotAPlan() {
        XCTAssertNil(MealPlanImport.read(String(week.dropLast(30))))
        XCTAssertNil(MealPlanImport.read(String(repeating: "[", count: 5000) + "Recipe" + String(repeating: "]", count: 5000)))
    }

    func testDaysAreRealDatesWrittenYearMonthDay() {
        XCTAssertEqual(MealPlanImport.parseDay("2026-11-23"), day(2026, 11, 23))
        XCTAssertEqual(MealPlanImport.parseDay(" 2026-11-23T18:00:00 "), day(2026, 11, 23))
        XCTAssertEqual(MealPlanImport.parseDay("2028-02-29"), day(2028, 2, 29))
        XCTAssertNil(MealPlanImport.parseDay("2027-02-29"))
        XCTAssertNil(MealPlanImport.parseDay("2026-13-01"))
        XCTAssertNil(MealPlanImport.parseDay("2026-00-10"))
        XCTAssertNil(MealPlanImport.parseDay("23/11/2026"))
        XCTAssertNil(MealPlanImport.parseDay("Monday"))
        XCTAssertNil(MealPlanImport.parseDay(""))
        XCTAssertNil(MealPlanImport.parseDay(nil))
        XCTAssertNotNil(MealPlanImport.parseDay("1970-01-01"))
    }
}
