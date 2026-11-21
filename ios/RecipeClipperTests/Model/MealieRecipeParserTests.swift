import XCTest
@testable import RecipeClipper

/// One Mealie recipe's JSON (#391; Android's MealieRecipeParserTest): what is detected, and each field's rule.
final class MealieRecipeParserTests: XCTestCase {

    private func recipe(_ json: String, file: StaticString = #filePath, line: UInt = #line) throws -> MealieRecipe {
        guard case .recipe(let recipe) = MealieRecipeParser.parse(json) else {
            XCTFail("not a recipe: \(json)", file: file, line: line)
            throw CancellationError()
        }
        return recipe
    }

    private func ingredient(_ fields: String) -> String { #"{"reference_id": "r", "# + fields + "}" }

    private func withIngredients(_ ingredients: String..., extra: String = "") -> String {
        #"{"slug": "s", "name": "Soup", "recipe_ingredient": ["# + ingredients.joined(separator: ",") + #"], "recipe_instructions": []"# + extra + "}"
    }

    private let snake = """
        {"id": "8a6b", "slug": "pasta-e-ceci", "name": "  Pasta e Ceci ", "description": "Not kept.", "recipe_servings": 4.0,
         "recipe_yield": "4 servings", "prep_time": "10 minutes", "perform_time": "35 minutes", "total_time": "45 minutes",
         "org_url": "http://www.Example.com/pasta?utm_source=mealie&id=9#top",
         "recipe_category": [{"name": "Dinner"}, {"name": " "}, {"name": "Soup"}], "tags": [{"name": "Vegetarian"}],
         "recipe_ingredient": [
           {"title": "The base", "original_text": "2 cups chickpeas", "display": "ignored", "reference_id": "a"},
           {"original_text": "1 tbsp oil", "reference_id": "b"}],
         "recipe_instructions": [{"title": "", "text": "Heat the oil."}, {"title": null, "text": "Simmer."}],
         "notes": [{"title": "Storage", "text": "Keeps 3 days."}, {"title": "", "text": "Thickens."}],
         "rating": 5, "unknown": {"deep": [1, [2]]}}
        """

    func testASnakeCaseRecipeIsReadFieldByField() throws {
        let r = try recipe(snake)
        XCTAssertEqual(r.slug, "pasta-e-ceci")
        XCTAssertEqual(r.id, "8a6b")
        XCTAssertEqual(r.name, "Pasta e Ceci")
        XCTAssertEqual(r.ingredients, ["The base:", "2 cups chickpeas", "1 tbsp oil"])
        XCTAssertEqual(r.instructions, ["Heat the oil.", "Simmer."])
        XCTAssertEqual(r.servings, "4 servings")
        XCTAssertEqual(r.prepTime, "10 minutes")
        XCTAssertEqual(r.cookTime, "35 minutes")
        XCTAssertEqual(r.totalTime, "45 minutes")
        XCTAssertEqual(r.sourceUrl, "https://www.example.com/pasta?id=9")
        XCTAssertEqual(r.categories, ["Dinner", "Soup", "Vegetarian"])
        XCTAssertEqual(r.notes, "Storage\nKeeps 3 days.\n\nThickens.")
    }

    func testACamelCaseRecipeReadsTheSame() throws {
        let camel = """
            {"id": "x", "slug": "tea", "name": "Tea", "recipeServings": 2, "recipeYield": "", "performTime": "5 minutes",
             "orgURL": "https://example.com/tea", "recipeCategory": [{"name": "Drinks"}], "tags": [{"name": "Quick"}],
             "recipeIngredient": [{"originalText": "1 handful mint", "referenceId": "a"}],
             "recipeInstructions": [{"text": "Steep."}], "notes": [{"title": "T", "text": "N"}]}
            """
        let r = try recipe(camel)
        XCTAssertEqual(r.ingredients, ["1 handful mint"])
        XCTAssertEqual(r.instructions, ["Steep."])
        // No yield text, so the number of servings stands in, whole.
        XCTAssertEqual(r.servings, "2")
        XCTAssertEqual(r.cookTime, "5 minutes")
        XCTAssertEqual(r.sourceUrl, "https://example.com/tea")
        XCTAssertEqual(r.categories, ["Drinks", "Quick"])
        XCTAssertEqual(r.notes, "T\nN")
    }

    func testAnIsoOrSecondsTimeIsKeptForTheTimeRules() throws {
        let one = ingredient(#""original_text": "1 egg""#)
        let r = try recipe(withIngredients(one, extra: #", "prep_time": "PT20M", "cookTime": "1 hour 30 minutes", "total_time_seconds": 5400"#))
        XCTAssertEqual(r.prepTime, "PT20M")
        XCTAssertEqual(r.cookTime, "1 hour 30 minutes")
        XCTAssertEqual(r.totalTime, "PT5400S")
        // The time text wins over a seconds figure.
        let both = try recipe(withIngredients(one, extra: #", "prepTime": "5 min", "prepTimeSeconds": 600"#))
        XCTAssertEqual(both.prepTime, "5 min")
        let zero = try recipe(withIngredients(one, extra: #", "prep_time_seconds": 0, "total_time": null"#))
        XCTAssertNil(zero.prepTime)
        XCTAssertNil(zero.totalTime)
    }

    func testServingsTextWinsANumberIsAFallbackAndZeroOrFractionsAreHandled() throws {
        let one = ingredient(#""original_text": "1 egg""#)
        func servings(_ extra: String) throws -> String? { try recipe(withIngredients(one, extra: extra)).servings }
        XCTAssertEqual(try servings(#", "recipe_yield": "Serves 6", "recipe_servings": 4"#), "Serves 6")
        XCTAssertEqual(try servings(#", "recipe_servings": 4.0"#), "4")
        XCTAssertEqual(try servings(#", "recipe_servings": 2.5"#), "2.5")
        XCTAssertNil(try servings(#", "recipe_servings": 0"#))
        XCTAssertNil(try servings(#", "recipe_servings": "lots""#))
        XCTAssertNil(try servings(#", "recipe_servings": true"#))
    }

    func testAnIngredientLineIsItsOriginalTextThenMealiesDisplay() throws {
        let r = try recipe(withIngredients(
            ingredient(#""original_text": " 2 cups flour ", "display": "x", "quantity": 7, "unit": {"name": "cup"}, "food": {"name": "flour"}"#),
            ingredient(#""original_text": "", "display": "3 eggs", "quantity": 3, "food": {"name": "egg"}"#),
            ingredient(#""display": "1 pinch salt", "food": {"name": "salt"}, "unit": {"name": "pinch"}"#)
        ))
        XCTAssertEqual(r.ingredients, ["2 cups flour", "3 eggs", "1 pinch salt"])
    }

    func testAPre31LineStoredWithQuantity1IsItsNoteNeverARebuiltNumber() throws {
        let r = try recipe(withIngredients(
            // Mealie before 3.1: quantity 1, no unit, no food, the text in the note, and a display with the 1 in front.
            ingredient(#""quantity": 1.0, "unit": null, "food": null, "note": "2 cups red lentils", "display": "1 2 cups red lentils", "original_text": null"#),
            ingredient(#""quantity": 1.0, "note": "salt to taste", "display": "1 salt to taste", "disable_amount": true"#),
            // A line with nothing but a quantity has no text: it is dropped, not shown as "1".
            ingredient(#""quantity": 1.0, "unit": null, "food": null, "note": "", "display": "1", "original_text": null"#)
        ))
        XCTAssertEqual(r.ingredients, ["2 cups red lentils", "salt to taste"])
    }

    func testWithNoDisplayEitherTheFoodAndTheNoteAreTheTextWithoutAnAmount() throws {
        let r = try recipe(withIngredients(
            ingredient(#""quantity": 2.0, "unit": {"name": "cup"}, "food": {"name": "flour"}, "note": "sifted""#),
            ingredient(#""quantity": 5.0, "food": "pepper""#),
            ingredient(#""quantity": 9.0, "unit": {"name": "cup"}"#)
        ))
        XCTAssertEqual(r.ingredients, ["flour, sifted", "pepper"])
    }

    func testATitlePutsAHeadingBeforeItsLineAndATitleAlreadyEndingInAColonIsNotDoubled() throws {
        let r = try recipe(withIngredients(
            ingredient(#""title": "For the sauce", "original_text": "1 cup cream""#),
            ingredient(#""title": "To serve:", "original_text": "parsley""#),
            ingredient(#""title": "Empty section", "original_text": null"#)
        ))
        XCTAssertEqual(r.ingredients, ["For the sauce:", "1 cup cream", "To serve:", "parsley", "Empty section:"])
    }

    func testAStepsTitleGoesInFrontOfItsTextAndAStepOfOnlyATitleInFrontOfTheNext() throws {
        let r = try recipe("""
            {"slug": "s", "name": "Soup", "recipe_ingredient": [{"original_text": "1 egg", "reference_id": "a"}],
             "recipe_instructions": [{"title": "Sauce", "text": "Whisk."}, {"title": "Bake:", "text": "Bake 20 minutes."},
                                     {"title": "To finish", "text": ""}, {"title": "", "text": "Serve."}, {"title": "Dangling", "text": ""}]}
            """)
        XCTAssertEqual(r.instructions, ["Sauce: Whisk.", "Bake: Bake 20 minutes.", "To finish: Serve."])
    }

    func testALinkThatIsntAWebLinkIsNone() throws {
        for url in ["", "n/a", "Grandma", "ftp://x.example.com/r", "file:///etc/passwd", "javascript:alert(1)"] {
            let r = try recipe(withIngredients(ingredient(#""original_text": "1 egg""#), extra: #", "orgURL": "\#(url)""#))
            XCTAssertNil(r.sourceUrl, url)
        }
    }

    func testAFileWithNoNameOrNeitherIngredientsNorStepsIsMealieShapedButNotARecipe() {
        let one = ingredient(#""original_text": "1 egg""#)
        XCTAssertEqual(MealieRecipeParser.parse(withIngredients(one).replacingOccurrences(of: "\"Soup\"", with: "\"\"")), .notARecipe)
        XCTAssertEqual(MealieRecipeParser.parse(#"{"slug": "s", "name": "Only", "recipe_ingredient": [], "recipe_instructions": []}"#), .notARecipe)
        // Headings alone are no ingredients.
        XCTAssertEqual(MealieRecipeParser.parse(withIngredients(ingredient(#""title": "Group", "original_text": null"#))), .notARecipe)
        // Steps alone are enough, and an empty ingredient list is told by having steps.
        if case .recipe = MealieRecipeParser.parse(#"{"slug": "s", "name": "Toast", "recipe_ingredient": [], "recipe_instructions": [{"text": "Toast it."}]}"#) {} else {
            XCTFail("steps alone should be a recipe")
        }
    }

    func testWhatIsNotMealiesIsToldApart() {
        // schema.org: a recipe page's data, which the link import reads.
        let schema = #"{"@context": "https://schema.org", "@type": "Recipe", "name": "A", "slug": "a", "recipeIngredient": [{"referenceId": "x"}]}"#
        XCTAssertEqual(MealieRecipeParser.parse(schema), .notMealie)
        XCTAssertEqual(MealieRecipeParser.parse(#"{"name": "A", "recipe_ingredient": [{"reference_id": "x"}]}"#), .notMealie)
        XCTAssertEqual(MealieRecipeParser.parse(#"{"slug": "  ", "name": "A", "recipe_ingredient": [{"reference_id": "x"}]}"#), .notMealie)
        // Strings in the ingredients, or objects with no referenceId: another app's shape.
        XCTAssertEqual(MealieRecipeParser.parse(#"{"slug": "a", "name": "A", "recipe_ingredient": ["1 egg"]}"#), .notMealie)
        XCTAssertEqual(MealieRecipeParser.parse(#"{"slug": "a", "name": "A", "recipe_ingredient": [{"original_text": "1 egg"}]}"#), .notMealie)
        XCTAssertEqual(MealieRecipeParser.parse(#"{"slug": "a", "name": "A"}"#), .notMealie)
        XCTAssertEqual(MealieRecipeParser.parse(#"{"slug": "a", "name": "A", "recipe_ingredient": []}"#), .notMealie)
    }

    func testTextThatIsNotAnObjectIsUnreadable() {
        for text in ["", "this is not json", "[1, 2]", "\"a string\"", "{\"slug\": ", "null"] {
            XCTAssertEqual(MealieRecipeParser.parse(text), .unreadable, text)
        }
        XCTAssertEqual(MealieRecipeParser.parse(String(repeating: "[", count: 100_000)), .unreadable)
    }

    func testAByteOrderMarkIsIgnored() {
        if case .recipe = MealieRecipeParser.parse("\u{FEFF}" + snake) {} else { XCTFail("a BOM should be ignored") }
    }

    func testJSONNullsAreAbsentNeverTheTextNull() throws {
        let r = try recipe(
            #"""
            {"slug": "s", "name": "Soup", "id": null, "recipe_yield": null, "prep_time": null, "org_url": null, "notes": null, "tags": null,
             "recipe_ingredient": [{"original_text": null, "display": null, "note": null, "food": null, "title": null, "reference_id": null},
                                   {"original_text": "1 egg", "reference_id": "a"}], "recipe_instructions": null}
            """#
        )
        XCTAssertEqual(r.ingredients, ["1 egg"])
        XCTAssertNil(r.id)
        XCTAssertNil(r.servings)
        XCTAssertNil(r.prepTime)
        XCTAssertNil(r.sourceUrl)
        XCTAssertNil(r.notes)
        XCTAssertTrue(r.categories.isEmpty)
    }
}
