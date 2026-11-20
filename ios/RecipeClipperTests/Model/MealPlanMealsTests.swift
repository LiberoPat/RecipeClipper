import XCTest
@testable import RecipeClipper

/// Android's MealPlanMealsTest (#392, #367): a plan's `"meal"`, a recipe named by its link instead of
/// written out, and the cut-off follow-up that names day and meal pairs.
final class MealPlanMealsTests: XCTestCase {

    private func day(_ d: Int) -> Int64 { PlanDays.epochDay(year: 2026, month: 11, dayOfMonth: d) }

    private func written(_ name: String, _ day: String?, _ meal: String?, extra: String = "") -> String {
        "{\"@context\": \"https://schema.org\", \"@type\": \"Recipe\", \"name\": \"\(name)\", "
            + (day.map { "\"day\": \"\($0)\", " } ?? "") + (meal.map { "\"meal\": \"\($0)\", " } ?? "")
            + "\"recipeIngredient\": [\"1 cup rice\"], \"recipeInstructions\": [{\"@type\": \"HowToStep\", \"text\": \"Cook.\"}]\(extra)}"
    }

    private func saved(_ name: String, _ link: String, _ day: String?, _ meal: String?, key: String = "isBasedOn") -> String {
        "{\"@type\": \"Recipe\", \"name\": \"\(name)\", \"\(key)\": \"\(link)\", "
            + (day.map { "\"day\": \"\($0)\", " } ?? "") + (meal.map { "\"meal\": \"\($0)\", " } ?? "") + "\"x\": 0}"
    }

    func testEachRecipeKeepsTheMealItNamesTrimmedAsWritten() throws {
        let text = "[" + written("Porridge", "2026-11-23", " Breakfast ") + "," + written("Soup", "2026-11-23", "dinner") + ","
            + written("Toast", "2026-11-24", nil) + "]"
        let plan = try XCTUnwrap(MealPlanImport.read(text))
        XCTAssertEqual(plan.items.map(\.meal), ["Breakfast", "dinner", nil])
        XCTAssertEqual(plan.items.map(\.day), [day(23), day(23), day(24)])
        XCTAssertTrue(plan.items.allSatisfy { !$0.namesSavedRecipe })
    }

    func testAWrappersMealIsCarriedToTheRecipeUnderIt() throws {
        let text = "{\"days\": [{\"day\": \"2026-11-20\", \"meal\": \"Lunch\", \"recipe\": \(written("A", nil, nil))},\n"
            + "{\"day\": \"2026-11-20\", \"recipe\": \(written("B", nil, "Dinner"))}]}"
        XCTAssertEqual(MealPlanImport.read(text)?.items.map(\.meal), ["Lunch", "Dinner"])
    }

    func testAnObjectThatNamesASavedRecipeByItsLinkIsReadAsThatWithNoRecipeOfItsOwn() throws {
        let text = "```json\n[" + saved("Chicken Adobo", "https://example.com/adobo", "2026-11-23", "Dinner") + ","
            + saved("Pancakes", "manual:abc-123", "2026-11-24", "Breakfast") + "]\n```"
        let plan = try XCTUnwrap(MealPlanImport.read(text))
        XCTAssertEqual(plan.items.map(\.recipe.name), ["Chicken Adobo", "Pancakes"])
        XCTAssertEqual(plan.items.map(\.link), ["https://example.com/adobo", "manual:abc-123"])
        XCTAssertEqual(plan.items.map(\.meal), ["Dinner", "Breakfast"])
        XCTAssertTrue(plan.items.allSatisfy { $0.namesSavedRecipe && $0.recipe.ingredients.isEmpty && $0.recipe.instructions.isEmpty })
        XCTAssertEqual(plan.items.map(\.day), [day(23), day(24)])
    }

    func testUrlNamesASavedRecipeTooIsBasedOnFirstAndAnObjectWithABodyIsANewRecipe() throws {
        let byUrl = saved("Soup", "https://example.com/soup", "2026-11-23", "Lunch", key: "url")
        let withBody = "{\"@type\": \"Recipe\", \"name\": \"Stew\", \"isBasedOn\": \"https://example.com/stew\", \"day\": \"2026-11-24\", "
            + "\"meal\": \"Dinner\", \"recipeIngredient\": [\"1 carrot\"], \"recipeInstructions\": [\"Simmer.\"]}"
        let plan = try XCTUnwrap(MealPlanImport.read("[\(byUrl), \(withBody)]"))
        XCTAssertEqual(plan.items[0].link, "https://example.com/soup")
        XCTAssertNil(plan.items[1].link)
        XCTAssertEqual(plan.items[1].recipe.ingredients, ["1 carrot"])
        let thing = "{\"@type\": \"Recipe\", \"name\": \"Pie\", \"isBasedOn\": {\"@id\": \"https://example.com/pie\"}, \"day\": \"2026-11-25\"}"
        XCTAssertEqual(MealPlanImport.read("[\(byUrl), \(thing)]")?.items[1].link, "https://example.com/pie")
    }

    func testANamelessOrLinklessObjectWithNothingInItIsNoRecipe() {
        let nameless = "{\"@type\": \"Recipe\", \"isBasedOn\": \"https://example.com/x\", \"day\": \"2026-11-23\"}"
        let linkless = "{\"@type\": \"Recipe\", \"name\": \"Ghost\", \"day\": \"2026-11-24\"}"
        let text = "[" + written("A", "2026-11-23", nil) + "," + written("B", "2026-11-24", nil) + ",\(nameless),\(linkless)]"
        XCTAssertEqual(MealPlanImport.read(text)?.items.map(\.recipe.name), ["A", "B"])
    }

    func testAPlanOfSavedRecipesWithAMealAndNoDayIsStillAPlanNotAChangedRecipe() {
        let text = "[" + saved("A", "https://example.com/a", nil, "Dinner") + "," + saved("B", "https://example.com/b", nil, "Lunch") + "]"
        XCTAssertEqual(MealPlanImport.read(text)?.items.count, 2)
        // With no day and no meal, a link is a changed recipe's (#354), not a week.
        let bare = "[" + saved("A", "https://example.com/a", nil, nil) + "," + saved("B", "https://example.com/b", nil, nil) + "]"
        XCTAssertNil(MealPlanImport.read(bare))
    }

    func testOneWrittenRecipeAndOneSavedRecipeArePlan() {
        let text = "[" + written("New", "2026-11-23", "Dinner") + "," + saved("Old", "https://example.com/old", "2026-11-24", "Dinner") + "]"
        XCTAssertEqual(MealPlanImport.read(text)?.items.map(\.namesSavedRecipe), [false, true])
    }

    func testAWeekOfThreeMealsFitsAndTheCeilingIs70() {
        let meals = ["Breakfast", "Lunch", "Dinner"]
        let week = "[" + (0..<21).map { written("R\($0)", String(format: "2026-11-%02d", 19 + $0 / 3), meals[$0 % 3]) }.joined(separator: ",") + "]"
        XCTAssertEqual(MealPlanImport.read(week)?.items.count, 21)
        XCTAssertEqual(MealPlanImport.maxRecipes, 70)
    }

    // MARK: - Mapping a meal to a meal type

    private let types = [
        MealType(id: 1, name: "Breakfast", builtInKey: "breakfast", sortOrder: 0),
        MealType(id: 2, name: "Lunch", builtInKey: "lunch", sortOrder: 1),
        MealType(id: 3, name: "Abendessen", builtInKey: "dinner", sortOrder: 2),
        MealType(id: 4, name: "Snack", builtInKey: "snack", sortOrder: 3),
        MealType(id: 5, name: "Brunch", builtInKey: nil, sortOrder: 4, uid: "u-5"),
    ]

    func testAMealMapsToTheTypeOfThatNameCaseAndSpacesIgnoredElseDinner() {
        XCTAssertEqual(MealType.idForMeal("breakfast", types), 1)
        XCTAssertEqual(MealType.idForMeal("  LUNCH ", types), 2)
        XCTAssertEqual(MealType.idForMeal("Abendessen", types), 3) // the cook's own name for Dinner
        XCTAssertEqual(MealType.idForMeal("brunch", types), 5)
        XCTAssertEqual(MealType.idForMeal("Dinner", types), 3) // not a current name: Dinner by default
        XCTAssertEqual(MealType.idForMeal("supper", types), 3)
        XCTAssertEqual(MealType.idForMeal(nil, types), 3)
        XCTAssertEqual(MealType.idForMeal("  ", types), 3)
        XCTAssertNil(MealType.idForMeal("Lunch", []))
    }

    func testTheTickedMealsArePickedOnesThatStillExistElseDinnerAndNoneWhenAllAreUnticked() {
        XCTAssertEqual(MealType.chosenKeys(types, nil), ["dinner"])
        XCTAssertEqual(MealType.chosenKeys(types, ["u-5", "lunch", "gone"]), ["lunch", "u-5"])
        XCTAssertEqual(MealType.chosenKeys(types, ["gone"]), ["dinner"])
        XCTAssertEqual(MealType.chosenKeys(types, []), [])
        XCTAssertEqual(MealType.chosenKeys([types[0]], nil), ["breakfast"]) // no Dinner: the first
        XCTAssertEqual(MealType.chosenKeys([], nil), [])
    }

    func testATypeIsNamedByItsBuiltInKeyElseItsUidNeverItsName() {
        XCTAssertEqual(types[2].key, "dinner")
        XCTAssertEqual(types[4].key, "u-5")
    }

    // MARK: - The cut-off follow-up

    private let words = CutOffMessage.Words(plan: { "days:\($0)" }, planRest: "rest", recipe: "recipe", planMeals: { "pairs:\($0)" })

    func testACutOffWeekOfMealsNamesTheMissingDayAndMealPairs() throws {
        let entries: [(day: Int64?, meal: String?)] = [
            (day(19), "Breakfast"), (day(19), "Dinner"), (day(20), "Breakfast"), (day(20), "dinner "),
        ]
        let missing = try XCTUnwrap(CutOffMessage.missingPairs(entries))
        XCTAssertEqual(missing.count, 14 - 4)
        XCTAssertEqual(missing[0].day, day(21))
        XCTAssertEqual(missing[0].meal, "Breakfast")
        XCTAssertEqual(missing[1].day, day(21))
        XCTAssertEqual(missing[1].meal, "Dinner")
        XCTAssertTrue(
            CutOffMessage.forPlanMeals(entries, words).hasPrefix("pairs:2026-11-21 (Breakfast), 2026-11-21 (Dinner), 2026-11-22 (Breakfast)")
        )
    }

    func testAHoleInsideTheBlockIsNamedAndAWholeBlockAsksForTheRemainingDays() {
        var entries: [(day: Int64?, meal: String?)] = []
        for d in 19...25 { entries.append((day(d), "Lunch")); entries.append((day(d), "Dinner")) }
        entries.removeAll { $0.day == day(21) && $0.meal == "Lunch" }
        XCTAssertEqual(CutOffMessage.forPlanMeals(entries, words), "pairs:2026-11-21 (Lunch)")
        entries.append((day(21), "Lunch"))
        XCTAssertEqual(CutOffMessage.forPlanMeals(entries, words), "rest")
    }

    func testWithNoMealReadTheFollowUpIsTheDaysOneAndWithNoDayTheRemainingDays() {
        let days: [(day: Int64?, meal: String?)] = [(day(19), nil), (day(20), nil)]
        XCTAssertEqual(CutOffMessage.forPlanMeals(days, words), CutOffMessage.forPlan([day(19), day(20)], words))
        XCTAssertEqual(CutOffMessage.forPlanMeals([(nil, "Dinner"), (nil, "Lunch")], words), "rest")
        XCTAssertNil(CutOffMessage.missingPairs([]))
        XCTAssertFalse(CutOffMessage.forPlanMeals(days, words).hasPrefix("pairs:"))
        XCTAssertNotNil(CutOffMessage.missingPairs([(day(19), "Dinner")]))
    }
}
