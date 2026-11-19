import XCTest
@testable import RecipeClipper

/// A schema.org Recipe as JSON in shared text, as an AI chat writes it (#350). Android's
/// `SharedTextTest` (its last tests); the corpus's `Shared(…)` rows pin the rest.
final class SharedTextJsonTests: XCTestCase {

    private let json = """
        {"@context": "https://schema.org", "@type": "Recipe", "name": "Lemon Pasta",
         "url": "https://made-up.example/lemon-pasta", "image": "https://made-up.example/p.jpg",
         "recipeYield": "4 servings", "prepTime": "PT10M", "cookTime": "PT15M", "totalTime": "PT25M",
         "recipeIngredient": ["200 g spaghetti", "1 lemon", "2 tbsp butter"],
         "recipeInstructions": [
           {"@type": "HowToStep", "text": "Boil the pasta."},
           {"@type": "HowToStep", "text": "Toss with butter and lemon."}]}
        """

    private func assertLemonPasta(_ text: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(SharedText.kind(text), .recipe, file: file, line: line)
        guard case .editor(let reading) = SharedText.route(text) else { return XCTFail("not the editor's", file: file, line: line) }
        XCTAssertTrue(reading.sorted, file: file, line: line)
        XCTAssertEqual(reading.name, "Lemon Pasta", file: file, line: line)
        XCTAssertTrue(reading.fromChat, "JSON is a chat's recipe (#355)", file: file, line: line)
        XCTAssertEqual(reading.recipe.ingredients, ["200 g spaghetti", "1 lemon", "2 tbsp butter"], file: file, line: line)
        XCTAssertEqual(reading.recipe.instructions, ["Boil the pasta.", "Toss with butter and lemon."], file: file, line: line)
        XCTAssertEqual(reading.recipe.yield, "4 servings", file: file, line: line)
        let r = reading.recipe
        XCTAssertEqual([r.prepTime, r.cookTime, r.totalTime], ["10m", "15m", "25m"], file: file, line: line)
        XCTAssertTrue(reading.leftovers.isEmpty, file: file, line: line)
    }

    func testBareJsonOpensSortedWithItsNameTimesAndYield() { assertLemonPasta(json) }

    func testJsonInAFenceWithProseAroundItIsReadTheSame() {
        assertLemonPasta("```json\n\(json)\n```")
        assertLemonPasta("Sure! Here's a Recipe for you:\n\n```json\n\(json)\n```\n\nEnjoy [with a salad]! {Serves 4}")
        assertLemonPasta("Here it is: \(json) Hope that helps.")
        assertLemonPasta(json.replacingOccurrences(of: "\n", with: "\r\n"))
    }

    func testAGraphAnArrayOfRecipesAndASectionsStepsAreReadAsForAPage() {
        let graph = """
            {"@context":"https://schema.org","@graph":[{"@type":"WebPage","name":"x"},
            {"@type":["Thing","Recipe"],"name":"Soup","recipeIngredient":["1 onion"],"recipeInstructions":
            [{"@type":"HowToSection","name":"Base","itemListElement":[{"@type":"HowToStep","text":"Fry."}]},"Simmer."]}]}
            """
        let soup = SharedText.read("Your recipe:\n\(graph)")
        XCTAssertTrue(soup.sorted)
        XCTAssertEqual(soup.name, "Soup")
        XCTAssertEqual(soup.recipe.ingredients, ["1 onion"])
        XCTAssertEqual(soup.recipe.instructions, ["Fry.", "Simmer."])

        let two = """
            [{"@type":"Recipe","name":"First","recipeIngredient":["1 egg"]},
            {"@type":"Recipe","name":"Second","recipeIngredient":["2 eggs"]}]
            """
        XCTAssertEqual(SharedText.read(two).name, "First")
    }

    func testAUrlInTheJsonIsNotWhatTheTextIs() {
        XCTAssertEqual(SharedText.kind("\(json)\nhttps://made-up.example/lemon-pasta"), .recipe)
        XCTAssertEqual(SharedText.kind("https://made-up.example/lemon-pasta"), .link)
    }

    func testBrokenOrNonRecipeJsonFallsThroughToTodaysRules() {
        let broken = String(json.dropLast(40))
        XCTAssertFalse(SharedText.read(broken).sorted)
        XCTAssertEqual(SharedText.read(broken).name, "")
        XCTAssertFalse(SharedText.read(broken).fromChat)
        XCTAssertEqual(SharedText.kind(#"{"@type": "Person", "name": "Ann", "note": "recipe for disaster"}"#), .text)
        XCTAssertEqual(SharedText.kind(#"{"@type":"Recipe","name":"Empty"}"#), .text)
    }

    func testJsonNestedFarTooDeepIsNoRecipeAndNeverACrash() {
        XCTAssertFalse(SharedText.read("recipe " + String(repeating: "[", count: 20_000) + String(repeating: "]", count: 20_000)).sorted)
        XCTAssertFalse(
            SharedText.read("recipe " + String(repeating: "{\"a\":", count: 5_000) + "1" + String(repeating: "}", count: 5_000)).sorted
        )
        XCTAssertFalse(SharedText.read("recipe " + String(repeating: "[", count: 30_000)).sorted)
    }
}
