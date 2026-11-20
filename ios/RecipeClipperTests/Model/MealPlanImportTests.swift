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

    func testOneRecipeIsNotAPlan() {
        XCTAssertNil(MealPlanImport.read(recipe("Fried rice", "2026-11-23")))
        XCTAssertNil(MealPlanImport.read("[" + recipe("Fried rice", "2026-11-23") + "]"))
        XCTAssertNil(MealPlanImport.read("[" + recipe("Fried rice", nil) + "]"))
        XCTAssertNil(MealPlanImport.read("No recipe here, just 2026-11-23."))
    }

    // MARK: - What real chat apps produce for the prompt (Android's MealPlanImportTest)

    /// A recipe as an AI writes it for the prompt: the prompt's fields, pretty-printed, a day.
    private func pretty(_ name: String, _ day: String?, key: String = "day", indent: String = "  ") -> String {
        let dayLine = day.map { "\(indent)  \"\(key)\": \"\($0)\",\n" } ?? ""
        return "{\n\(indent)  \"@context\": \"https://schema.org\",\n\(indent)  \"@type\": \"Recipe\",\n"
            + "\(indent)  \"name\": \"\(name)\",\n\(indent)  \"description\": \"A quick dinner.\",\n\(dayLine)"
            + "\(indent)  \"recipeYield\": \"2 servings\",\n\(indent)  \"prepTime\": \"PT10M\",\n\(indent)  \"cookTime\": \"PT20M\",\n"
            + "\(indent)  \"recipeIngredient\": [\"1 cup rice\", \"2 eggs\"],\n\(indent)  \"recipeInstructions\": [\n"
            + "\(indent)    { \"@type\": \"HowToStep\", \"text\": \"Cook.\" },\n"
            + "\(indent)    { \"@type\": \"HowToStep\", \"text\": \"Serve.\" }\n\(indent)  ]\n\(indent)}"
    }

    private let names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    private func dayN(_ n: Int) -> String { String(format: "2026-11-%02d", 19 + n) }

    /// The owner's answer: one fenced array, five whole recipes, the sixth stopped inside its steps.
    private var cutOffArray: String {
        "[\n" + (0..<5).map { pretty(names[$0], dayN($0)) }.joined(separator: ",\n") + ",\n"
            + "  {\n    \"@context\": \"https://schema.org\",\n    \"@type\": \"Recipe\",\n    \"name\": \"Sat\",\n"
            + "    \"day\": \"2026-11-24\",\n    \"recipeIngredient\": [\"1 cup rice\", \"2 eggs\"],\n"
            + "    \"recipeInstructions\": [\n      { \"@type\": \"HowToStep\","
    }

    func testACutOffAnswerGivesEveryWholeRecipeAndSaysItWasCutOff() throws {
        for text in ["Here is your week:\n\n```json\n\(cutOffArray)", cutOffArray] {
            let plan = try XCTUnwrap(MealPlanImport.read(text))
            XCTAssertEqual(plan.items.map(\.recipe.name), Array(names.prefix(5)))
            XCTAssertEqual(plan.items.map(\.day), (0..<5).map { day(2026, 11, 19 + $0) })
            XCTAssertEqual(plan.items[4].recipe.instructions, ["Cook.", "Serve."])
            XCTAssertTrue(plan.cutOff)
        }
    }

    func testAWholeAnswerIsNotCutOffAndOneWholeRecipeInACutOffAnswerIsNotAPlan() throws {
        XCTAssertFalse(try XCTUnwrap(MealPlanImport.read(week)).cutOff)
        let oneThenCut = "```json\n[" + pretty("Mon", dayN(0)) + ",\n{\"@type\": \"Recipe\", \"name\": \"Tue\", \"recipeIngredient\": [\"1 egg\""
        XCTAssertNil(MealPlanImport.read(oneThenCut))
    }

    func testSevenCodeBlocksWithProseBetweenAreOnePlan() throws {
        let text = (0..<7).map {
            "**\(names[$0])**: dinner [see below] {just a note}\n\n```json\n\(pretty(names[$0], dayN($0), indent: ""))\n```"
        }.joined(separator: "\n\n") + "\n\nEnjoy! Want a shopping list?"
        let plan = try XCTUnwrap(MealPlanImport.read(text))
        XCTAssertEqual(plan.items.map(\.recipe.name), names)
        XCTAssertEqual(plan.items.map(\.day), (0..<7).map { day(2026, 11, 19 + $0) })
        XCTAssertFalse(plan.cutOff)
    }

    func testSevenBlocksWithTheLastOneCutOffGiveSix() throws {
        let blocks = (0..<6).map { "```json\n\(pretty(names[$0], dayN($0)))\n```" }.joined(separator: "\n\n")
        let last = "{\"@type\": \"Recipe\", \"name\": \"Sun\", \"day\": \"2026-11-25\", \"recipeInstructions\": [{ \"@type\": \"HowToStep\","
        let plan = try XCTUnwrap(MealPlanImport.read("\(blocks)\n\n```json\n\(last)"))
        XCTAssertEqual(plan.items.map(\.recipe.name), Array(names.prefix(6)))
        XCTAssertTrue(plan.cutOff)
    }

    func testAWeekOfSeparateBlocksIsReadAndNoMoreThanTheCeilingIsKept() {
        let many = (1...90).map { "```json\n\(recipe("R\($0)", "2026-11-23"))\n```" }.joined(separator: "\n")
        XCTAssertEqual(MealPlanImport.read(many)?.items.count, MealPlanImport.maxRecipes)
    }

    func testAWrapperObjectAroundTheRecipesIsWalked() throws {
        for key in ["mealPlan", "recipes", "plan"] {
            let text = "{\"title\": \"Your week\", \"\(key)\": [" + recipe("A", "2026-11-23") + "," + recipe("B", "2026-11-24") + "]}"
            XCTAssertEqual(MealPlanImport.read(text)?.items.map(\.recipe.name), ["A", "B"])
        }
        let nested = "{\"plan\": {\"meals\": [" + recipe("A", "2026-11-23") + "," + recipe("B", "2026-11-24") + "]}}"
        XCTAssertEqual(MealPlanImport.read(nested)?.items.count, 2)
    }

    func testADaysWrapperCarriesTheDayDownToTheRecipeInsideIt() throws {
        let text = "{\"days\": [\n"
            + "{\"day\": \"2026-11-20\", \"recipe\": \(recipe("A", nil))},\n"
            + "{\"date\": \"2026-11-21\", \"meal\": \"dinner\", \"recipe\": \(recipe("B", nil))},\n"
            + "{\"day\": \"2026-11-22\", \"recipe\": \(recipe("C", "2026-11-25"))}\n]}"
        let plan = try XCTUnwrap(MealPlanImport.read(text))
        XCTAssertEqual(plan.items.map(\.recipe.name), ["A", "B", "C"])
        XCTAssertEqual(plan.items.map(\.day), [day(2026, 11, 20), day(2026, 11, 21), day(2026, 11, 25)])
        let byKey = "{\"2026-11-21\": \(recipe("B", nil)), \"2026-11-20\": \(recipe("A", nil))}"
        XCTAssertEqual(MealPlanImport.read(byKey)?.items.map(\.day), [day(2026, 11, 20), day(2026, 11, 21)])
    }

    func testDateStandsForDayOnARecipeButOnlyWhenItIsADate() throws {
        let text = "[" + pretty("A", "2026-11-23", key: "date") + "," + pretty("B", "tomorrow", key: "date") + ","
            + pretty("C", "2026-11-25", key: "date") + "]"
        XCTAssertEqual(MealPlanImport.read(text)?.items.map(\.day), [day(2026, 11, 23), nil, day(2026, 11, 25)])
    }

    func testARecipeObjectWithoutItsTypeIsReadInsideAWrapper() {
        let bare = "{\"name\": \"A\", \"day\": \"2026-11-23\", \"recipeIngredient\": [\"1 egg\"]}"
        let text = "{\"mealPlan\": [\(bare), \(bare.replacingOccurrences(of: "\"A\"", with: "\"B\""))]}"
        XCTAssertEqual(MealPlanImport.read(text)?.items.map(\.recipe.name), ["A", "B"])
    }

    func testRecipesWithNoDayInOneArrayOrWrapperArePlanWithNoDays() throws {
        let array = "[" + recipe("A", nil) + "," + recipe("B", nil) + "," + recipe("C", nil) + "]"
        let plan = try XCTUnwrap(MealPlanImport.read("Three dinners:\n```json\n\(array)\n```"))
        XCTAssertEqual(plan.items.map(\.recipe.name), ["A", "B", "C"])
        XCTAssertEqual(plan.items.map(\.day), [nil, nil, nil])
        XCTAssertEqual(MealPlanImport.read("{\"mealPlan\": [" + recipe("A", nil) + "," + recipe("B", nil) + "]}")?.items.count, 2)
        let cut = "```json\n" + String(array.dropLast()) + ",{\"@type\": \"Recipe\", \"name\": \"D\", \"recipeInstructions\": [{"
        let cutPlan = try XCTUnwrap(MealPlanImport.read(cut))
        XCTAssertEqual(cutPlan.items.count, 3)
        XCTAssertTrue(cutPlan.cutOff)
    }

    func testRecipesWithNoDayInSeparateBlocksOrAChangedRecipeAreNotAPlan() {
        XCTAssertNil(MealPlanImport.read("```json\n" + recipe("A", nil) + "\n```\nand\n```json\n" + recipe("B", nil) + "\n```"))
        let changed = recipe("A", nil).replacingOccurrences(of: "\"name\"", with: "\"isBasedOn\": \"manual:1\", \"name\"")
        XCTAssertNil(MealPlanImport.read("[\(changed), " + recipe("B", nil) + "]"))
        XCTAssertNil(MealPlanImport.read("Original:\n\(changed)\nChanged:\n" + recipe("B", nil)))
    }

    func testAStrayBracketInTheProseDoesNotHideThePlan() throws {
        let plan = try XCTUnwrap(MealPlanImport.read("Your week [note: a draft\n```json\n\(week)\n```"))
        XCTAssertEqual(plan.items.count, 2)
        XCTAssertFalse(plan.cutOff)
    }

    // MARK: - A cut-off answer's message, and the answer to it (Android's CutOffAnswerTest)

    func testTheFollowUpNamesTheDaysThatAreMissing() {
        let words = CutOffMessage.Words(plan: { "plan:\($0)" }, planRest: "rest", recipe: "recipe")
        let five: [Int64?] = (19...23).map { day(2026, 11, $0) }
        XCTAssertEqual(CutOffMessage.forPlan(five, words), "plan:2026-11-24, 2026-11-25")
        XCTAssertEqual(CutOffMessage.forPlan([day(2026, 11, 19), day(2026, 11, 21), day(2026, 11, 22), day(2026, 11, 24), day(2026, 11, 25)], words),
                       "plan:2026-11-20, 2026-11-23")
        XCTAssertEqual(CutOffMessage.forPlan([nil, nil], words), "rest")
        XCTAssertEqual(CutOffMessage.forPlan((19...25).map { day(2026, 11, $0) }, words), "rest")
        XCTAssertEqual(CutOffMessage.forRecipe(words), "recipe")
        XCTAssertEqual(CutOffMessage.iso(day(2026, 1, 5)), "2026-01-05")
        XCTAssertNil(CutOffMessage.missingDays([]))
    }

    func testARecipeCutOffWithNoWholeRecipeBeforeItIsACutOffAnswer() {
        let cut = "```json\n[\n  {\n    \"@context\": \"https://schema.org\",\n    \"@type\": \"Recipe\",\n    \"name\": \"Mon\",\n"
            + "    \"day\": \"2026-11-19\",\n    \"recipeIngredient\": [\"1 cup rice\"],\n    \"recipeInstructions\": [\n"
            + "      { \"@type\": \"HowToStep\","
        XCTAssertTrue(SharedText.isCutOff(cut))
        XCTAssertTrue(SharedText.isCutOff(String(cut.dropFirst("```json\n".count))))
        XCTAssertTrue(SharedText.isCutOff("Here you go:\n{ \"@type\": \"Recipe\", \"name\": \"Soup\", \"recipeIngredient\": [\"1 egg\","))
        XCTAssertTrue(SharedText.isCutOff("{\"@type\": [\"Recipe\"], \"name\": \"Soup\", \"recipeInstructions\": [{"))
        XCTAssertNil(MealPlanImport.read(cut))
        XCTAssertFalse(SharedText.read(cut).fromChat)
    }

    func testTextThatIsNotJsonAWholeRecipeAndAWholePlanAreNotCutOff() {
        let whole = recipe("Soup", "2026-11-23")
        XCTAssertFalse(SharedText.isCutOff("Hi Mum, the scones recipe: 2 cups flour, bake 20 minutes. [see the card"))
        XCTAssertFalse(SharedText.isCutOff("Look at this recipe { not json at all"))
        XCTAssertFalse(SharedText.isCutOff("[\"a\", \"b\""))
        XCTAssertFalse(SharedText.isCutOff(whole))
        XCTAssertFalse(SharedText.isCutOff("```json\n[\(whole), \(whole)]\n```"))
        XCTAssertFalse(SharedText.isCutOff(""))
        XCTAssertFalse(SharedText.isCutOff("```json\n\(whole)\n```\nAnything else? [just ask"))
    }

    func testTheReplyToTheFollowUpReadsAsAPlanWithProseAndAFenceBeforeIt() throws {
        let reply = "Sure! Here are the remaining days:\n\n```json\n[\n  \(recipe("Sat", "2026-11-24")),\n  \(recipe("Sun", "2026-11-25"))\n]\n```\n\nEnjoy!"
        let plan = try XCTUnwrap(MealPlanImport.read(reply))
        XCTAssertEqual(plan.items.map(\.recipe.name), ["Sat", "Sun"])
        XCTAssertEqual(plan.items.map(\.day), [day(2026, 11, 24), day(2026, 11, 25)])
        XCTAssertFalse(plan.cutOff)
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

    func testNoMoreThanTheCeilingOfRecipesAreKept() {
        let many = "[" + (1...90).map { recipe("R\($0)", "2026-11-23") }.joined(separator: ",") + "]"
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
