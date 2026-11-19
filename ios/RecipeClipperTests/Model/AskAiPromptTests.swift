import XCTest
@testable import RecipeClipper

/// Android's AskAiPromptTest (#351, #353): the same expectations, word for word.
final class AskAiPromptTests: XCTestCase {
    private let today = PlanDays.epochDay(year: 2026, month: 11, dayOfMonth: 24) // a Tuesday

    private func item(
        _ name: String, quantity: String? = nil, expires: Int64? = nil, inStock: Bool = true,
        alwaysHave: Bool = false, id: Int64 = 0
    ) -> PantryItem {
        PantryItem(
            id: id, name: name, quantity: quantity, language: "en", aisle: .other, inStock: inStock,
            alwaysHave: alwaysHave, purchasedDay: nil, expiresDay: expires
        )
    }

    private func setup(_ units: UnitSystem = .asWritten, _ temperature: TemperatureUnit = .asWritten) -> AskAiPrompt.Setup {
        AskAiPrompt.Setup(words: .english, unitSystem: units, temperatureUnit: temperature)
    }

    private let recipe = Recipe(
        name: "Chicken \"Adobo\"",
        image: "https://example.com/adobo.jpg",
        ingredients: ["2 lb chicken thighs", "For the sauce:", "1/2 cup soy sauce"],
        instructions: ["Marinate the chicken.", "Simmer\n30 minutes."],
        prepTime: "10m", cookTime: "30m", totalTime: nil, yield: "6 servings",
        sourceUrl: "https://example.com/adobo", language: "en"
    )

    func testDescribeAsksForOneRecipeAsJsonInAJsonBlockWithTheCooksOwnWords() {
        let prompt = AskAiPrompt.describe("  a quick lentil soup ", setup: setup())
        XCTAssertEqual(
            prompt,
            "I want to cook: a quick lentil soup\n\n"
                + "Reply with exactly one recipe as schema.org Recipe JSON, in a ```json code block. "
                + AskAiPrompt.Words.english.recipeFields
                + " Use the units that suit the recipe. Write it in English. "
                + "Put anything else you want to say outside the code block."
        )
    }

    func testTheUnitsAndTheOvenTemperatureFollowSettings() {
        let metric = AskAiPrompt.describe("soup", setup: setup(.metric, .celsius))
        XCTAssertTrue(metric.contains("Give amounts in metric units (g, kg, ml, l), never cups or ounces. Give oven temperatures in °C."))
        XCTAssertFalse(metric.contains("Use the units that suit the recipe."))
        let ounces = AskAiPrompt.describe("soup", setup: setup(.ounces, .fahrenheit))
        XCTAssertTrue(ounces.contains("Give weights in ounces and pounds, and volumes in cups and spoons. Give oven temperatures in °F."))
        XCTAssertFalse(AskAiPrompt.describe("soup", setup: setup()).contains("oven temperatures"))
    }

    func testTheLanguageIsTheOneTheWordsAreIn() {
        var words = AskAiPrompt.Words.english
        words.language = "Deutsch"
        words.writeIn = { "Schreibe es auf \($0)." }
        let german = AskAiPrompt.Setup(words: words, unitSystem: .asWritten, temperatureUnit: .asWritten)
        XCTAssertTrue(AskAiPrompt.describe("Suppe", setup: german).contains("Schreibe es auf Deutsch."))
    }

    func testWhatCanIMakePutsTheItemsToUseUpFirstAndTheRestAfterInStockOnly() {
        let pantry = [
            item("rice", quantity: "2 cups", id: 1),
            item("Spinach", quantity: "1 bag", expires: today + 1, id: 2),
            item("milk", expires: today, id: 3),
            item("flour", inStock: false, id: 4),
            item("yoghurt", expires: today + 5, id: 5),
            item("salt", expires: today, alwaysHave: true, id: 6),
            item("old cream", expires: today - 1, id: 7),
        ]
        let prompt = AskAiPrompt.whatCanIMake(pantry: pantry, today: today, request: "", setup: setup())
        XCTAssertTrue(prompt.contains("Use these up first, they expire soon: milk, Spinach (1 bag)."))
        // Past its date is not "soon"; a staple is never offered to use up; run out is left out.
        XCTAssertTrue(prompt.contains("Also in my pantry: old cream, rice (2 cups), salt, yoghurt."))
        XCTAssertFalse(prompt.contains("flour"))
        XCTAssertFalse(prompt.contains("Also: "))
        XCTAssertTrue(prompt.contains("```json"))
    }

    func testWhatCanIMakeWithAnEmptyPantryStillAsksWithoutTheLists() {
        let prompt = AskAiPrompt.whatCanIMake(pantry: [], today: today, request: "vegetarian", setup: setup())
        XCTAssertFalse(prompt.contains("Use these up first"))
        XCTAssertFalse(prompt.contains("Also in my pantry"))
        XCTAssertTrue(prompt.contains("\n\nAlso: vegetarian\n\n"))
    }

    func testThePantryInAPromptIsCapped() {
        let pantry = (1 ... 100).map { item(String(format: "item%03d", $0), id: Int64($0)) }
        let prompt = AskAiPrompt.whatCanIMake(pantry: pantry, today: today, request: "", setup: setup())
        XCTAssertTrue(prompt.contains("item060."))
        XCTAssertFalse(prompt.contains("item061"))
    }

    func testPlanMyWeekListsTheDaysTheFavouritesAndWhatWasCookedLately() {
        let prompt = AskAiPrompt.planMyWeek(
            days: (0 ..< 3).map { today + Int64($0) }, favourites: ["Lasagne", " Pad Thai "], cooked: ["Chili"],
            request: "no fish", setup: setup(.metric, .celsius)
        )
        XCTAssertTrue(prompt.hasPrefix(
            "Plan one dinner recipe for each of these days:\n\n"
                + "- 2026-11-24 (Tuesday)\n- 2026-11-25 (Wednesday)\n- 2026-11-26 (Thursday)\n\n"
                + "Recipes I like, for ideas: Lasagne, Pad Thai.\n\n"
                + "I cooked these lately, so please don't repeat them: Chili.\n\n"
                + "Also: no fish\n\n"
                + "Reply with a JSON array in one ```json code block: one schema.org Recipe object for each day listed, "
                + "in the same order. Give each object one extra property, \"day\", holding that day's date exactly as "
                + "listed (YYYY-MM-DD). "
        ))
        XCTAssertTrue(prompt.contains("metric units"))
    }

    func testPlanMyWeekWithoutFavouritesOrCookedRecipesLeavesThoseLinesOut() {
        let prompt = AskAiPrompt.planMyWeek(days: [today], favourites: [], cooked: [" "], request: "", setup: setup())
        XCTAssertFalse(prompt.contains("Recipes I like"))
        XCTAssertFalse(prompt.contains("lately"))
    }

    func testFavouritesAndCookedTitlesAreCapped() {
        let titles = (1 ... 40).map { String(format: "Dish%02d", $0) }
        let prompt = AskAiPrompt.planMyWeek(days: [today], favourites: titles, cooked: titles, request: "", setup: setup())
        XCTAssertTrue(prompt.contains("Dish15."))
        XCTAssertFalse(prompt.contains("Dish16"))
    }

    func testTheRecipeGoesOutAsSchemaOrgJsonWithIsBasedOnTheStableId() {
        let expected = """
        {
          "@context": "https://schema.org",
          "@type": "Recipe",
          "name": "Chicken \\"Adobo\\"",
          "isBasedOn": "https://example.com/adobo",
          "inLanguage": "en",
          "recipeYield": "6 servings",
          "prepTime": "10m",
          "cookTime": "30m",
          "recipeIngredient": [
            "2 lb chicken thighs",
            "For the sauce:",
            "1/2 cup soy sauce"
          ],
          "recipeInstructions": [
            {"@type": "HowToStep", "text": "Marinate the chicken."},
            {"@type": "HowToStep", "text": "Simmer\\n30 minutes."}
          ]
        }
        """
        XCTAssertEqual(AskAiPrompt.recipeJson(recipe), expected)
    }

    func testTheRecipeJsonIsValidJsonAParserReadsBack() throws {
        var typed = recipe
        typed.sourceUrl = "manual:0b1c"
        typed.name = "Back\\slash \u{01} é 日本"
        typed.ingredients = []
        let data = Data(AskAiPrompt.recipeJson(typed).utf8)
        let json = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["isBasedOn"] as? String, "manual:0b1c")
        XCTAssertEqual(json["name"] as? String, "Back\\slash \u{01} é 日本")
        XCTAssertEqual((json["recipeIngredient"] as? [Any])?.count, 0)
        let steps = try XCTUnwrap(json["recipeInstructions"] as? [[String: Any]])
        XCTAssertEqual(steps[1]["text"] as? String, "Simmer\n30 minutes.")
    }

    func testChangeThisRecipeSendsTheRequestTheRecipeAndTheReplyFormat() {
        let prompt = AskAiPrompt.changeRecipe(recipe, request: " make it vegetarian ", setup: setup(.metric, .asWritten))
        XCTAssertTrue(prompt.hasPrefix(
            "Please change this recipe: make it vegetarian\n\nThe recipe, as schema.org Recipe JSON:\n\n```json\n{"
        ))
        XCTAssertTrue(prompt.contains(
            "\n}\n```\n\nReply with the changed recipe as schema.org Recipe JSON, in a ```json code block, "
                + "keeping \"isBasedOn\" exactly as it is. "
        ))
        XCTAssertTrue(prompt.contains("\"isBasedOn\": \"https://example.com/adobo\""))
        XCTAssertTrue(prompt.contains("metric units"))
    }

    func testIsoDatesArePadded() {
        XCTAssertEqual(AskAiPrompt.isoDate(PlanDays.epochDay(year: 2026, month: 1, dayOfMonth: 5)), "2026-01-05")
        XCTAssertEqual(AskAiPrompt.isoDate(0), "1970-01-01")
    }
}
