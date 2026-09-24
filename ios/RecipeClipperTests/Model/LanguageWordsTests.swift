import XCTest
@testable import RecipeClipper

/// The recipe's language picks its words (#14); a language with none leaves every line as
/// written. Mirrors Android's LanguageWordsTest and RecipeLanguageTest.
final class LanguageWordsTests: XCTestCase {

    func testTagsAreNormalisedAndAnythingElseIsNoTag() {
        XCTAssertEqual(LanguageWords.normalize(" en-US "), "en-us")
        XCTAssertEqual(LanguageWords.normalize("pt_BR"), "pt-br")
        XCTAssertEqual(LanguageWords.normalize("de"), "de")
        XCTAssertNil(LanguageWords.normalize("English"))
        XCTAssertNil(LanguageWords.normalize(""))
        XCTAssertNil(LanguageWords.normalize(nil))
    }

    func testWordsAreFoundByThePrimarySubtagAndOnlyForAShippedLanguage() {
        XCTAssertTrue(LanguageWords.forTag("en") === LanguageWords.english)
        XCTAssertTrue(LanguageWords.forTag("en-GB") === LanguageWords.english)
        XCTAssertNil(LanguageWords.forTag("de-de"))
        XCTAssertNil(LanguageWords.forTag(nil))
    }

    private let english = "2 cups chopped fresh basil\n1 tablespoon olive oil\nsalt to taste"
    private let german = "Rührkuchen\n500 g Mehl\n200 g Zucker\n3 Eier\n1 Prise Salz\n2 EL Öl"
    private let ambiguous = "Kuchen\n200 g Mehl\n1 cup sugar"

    func testInLanguageWinsThenThePageThenTheWordsThenEnglish() {
        XCTAssertEqual(LanguageWords.resolve(declared: "de-DE", page: "en") { self.ambiguous }, "de-de")
        XCTAssertEqual(LanguageWords.resolve(declared: nil, page: "fr") { "Gâteau" }, "fr")
        XCTAssertEqual(LanguageWords.resolve(declared: "English", page: "fr") { "Gâteau" }, "fr")
        XCTAssertEqual(LanguageWords.resolve(declared: "en-GB", page: nil) { self.english }, "en-gb")
        XCTAssertEqual(LanguageWords.resolve(declared: nil, page: nil) { self.english }, "en")
        XCTAssertEqual(LanguageWords.resolve(declared: nil, page: nil) { self.german }, "de")
        XCTAssertEqual(LanguageWords.resolve(declared: nil, page: nil) { "Mehl\nZucker" }, "en")
    }

    func testWordsThatClearlySayAnotherLanguageBeatADeclaredOneAmbiguousWordsDont() {
        XCTAssertEqual(LanguageWords.resolve(declared: nil, page: "en") { self.german }, "de")
        XCTAssertEqual(LanguageWords.resolve(declared: "en-US", page: "en") { self.german }, "de")
        XCTAssertEqual(LanguageWords.resolve(declared: "de", page: nil) { self.english }, "en")
        XCTAssertEqual(LanguageWords.resolve(declared: nil, page: "en") { self.ambiguous }, "en")
        XCTAssertEqual(LanguageWords.resolve(declared: "en-US", page: nil) { self.ambiguous }, "en-us")
    }

    func testDetectionNeedsAClearLead() {
        XCTAssertEqual(LanguageWords.detect("1 cup sugar\n2 cups flour\n1 large egg, divided"), "en")
        XCTAssertEqual(LanguageWords.detect(german), "de")
        XCTAssertEqual(LanguageWords.detect("2 tazas de harina\n1 cucharada de azúcar\n3 huevos\nsal al gusto"), "es")
        XCTAssertEqual(LanguageWords.detect("250 g de farine\n2 cuillères à soupe de sucre\n3 œufs\nsel et poivre"), "fr")
        XCTAssertEqual(LanguageWords.detect("300 g di farina\n2 cucchiai di zucchero\n3 uova\nsale q.b."), "it")
        XCTAssertEqual(LanguageWords.detect("2 xícaras de farinha\n1 colher de açúcar\n3 ovos\nsal a gosto"), "pt")
        XCTAssertNil(LanguageWords.detect("500 g Mehl\n200 g Zucker"))
        XCTAssertNil(LanguageWords.detect(ambiguous))
        XCTAssertNil(LanguageWords.detect("Pasta with pesto"))
    }

    func testALanguageDetectedButWithoutTablesIsShownAsWritten() {
        XCTAssertNil(LanguageWords.forTag(LanguageWords.resolve(declared: nil, page: "en") { self.german }))
    }

    func testAStoredRecipeWithNoLanguageIsDetectedFromItsWords() {
        var recipe = Recipe(
            name: "Pancakes", image: nil, ingredients: ["1 cup flour"], instructions: [],
            prepTime: nil, cookTime: nil, totalTime: nil, yield: "4", sourceUrl: "https://a.com"
        )
        XCTAssertTrue(LanguageWords.forRecipe(recipe) === LanguageWords.english)
        recipe.language = "it-it"
        XCTAssertNil(LanguageWords.forRecipe(recipe))
    }

    func testWithNoWordsEveryLineStaysAsWritten() {
        // English rules would read "2 bis 3" as a range's start and scale it to "4 bis 3".
        XCTAssertEqual(IngredientScaler.scale("2 bis 3 Eier", factor: 2.0, words: nil), "2 bis 3 Eier")
        XCTAssertEqual(UnitConverter.convert("2 cups flour", system: .metric, includeLiquids: true, words: nil), "2 cups flour")
        XCTAssertEqual(TemperatureConverter.convert("Bake at 350°F", unit: .celsius, words: nil), "Bake at 350°F")
        XCTAssertNil(StepTimers.parse("Bake for 20 minutes", words: nil))
        XCTAssertNil(Servings.parse("4 servings", words: nil))
        XCTAssertEqual(Servings.kind("Makes 12", words: nil), .serves)
        XCTAssertEqual(Servings.pickYield(["4", "4-6"], words: nil), "4-6")
    }

    func testEnglishIsTheDefaultAndReadsAsBefore() {
        let en = LanguageWords.english
        XCTAssertEqual(IngredientScaler.scale("2 to 3 eggs", factor: 2.0, words: en), "4 to 6 eggs")
        XCTAssertEqual(IngredientScaler.scale("1/2 cup plus 1 tbsp flour", factor: 2.0, words: en), "1 cup plus 2 tbsp flour")
        XCTAssertEqual(StepTimers.parse("Bake 1 hour and 30 minutes", words: en), 90 * 60)
        XCTAssertEqual(StepTimers.label(90 * 60, words: en), "1 hr 30 min")
        XCTAssertEqual(StepTimers.label(45), "45 sec")
    }

    // MARK: - The parser records the language and reads times and sections with its words

    private func page(_ lang: String?, _ recipe: String) -> String {
        let html = lang.map { "<html lang=\"\($0)\">" } ?? "<html>"
        return html + "<head><script type=\"application/ld+json\">\(recipe)</script></head><body></body></html>"
    }

    private func parse(_ html: String) throws -> Recipe {
        guard case .success(let recipe) = BlogRecipeSource.parse(html: html, url: "https://example.com/r") else {
            XCTFail("no recipe")
            throw CancellationError()
        }
        return recipe
    }

    private let sections = """
        "recipeInstructions": [
          {"@type": "HowToSection", "name": "Summary", "itemListElement": [{"@type": "HowToStep", "text": "Short version."}]},
          {"@type": "HowToSection", "name": "Method", "itemListElement": [{"@type": "HowToStep", "text": "Mix."}]}
        ]
        """

    func testInLanguageIsRecordedAndWinsOverThePage() throws {
        let recipe = try parse(page("en", #"{"@type": "Recipe", "name": "Kuchen", "inLanguage": "de-DE", "recipeIngredient": ["200 g Mehl"]}"#))
        XCTAssertEqual(recipe.language, "de-de")
    }

    func testASchemaOrgLanguageGivesItsAlternateName() throws {
        let recipe = try parse(page(nil, #"{"@type": "Recipe", "name": "Torta", "inLanguage": {"@type": "Language", "name": "Italian", "alternateName": "it"}, "recipeIngredient": ["200 g farina"]}"#))
        XCTAssertEqual(recipe.language, "it")
    }

    func testThePagesLangIsUsedWhenTheRecipeDeclaresNone() throws {
        let recipe = try parse(page("fr-FR", #"{"@type": "Recipe", "name": "Gâteau", "recipeIngredient": ["200 g farine"]}"#))
        XCTAssertEqual(recipe.language, "fr-fr")
    }

    func testWithNoDeclaredLanguageEnglishIsDetectedOrAssumed() throws {
        let detected = try parse(page(nil, #"{"@type": "Recipe", "name": "Pancakes", "recipeIngredient": ["1 cup flour", "1 large egg", "2 tablespoons sugar, divided"]}"#))
        XCTAssertEqual(detected.language, "en")
        let fallback = try parse(page(nil, #"{"@type": "Recipe", "name": "Kuchen", "recipeIngredient": ["200 g Mehl"]}"#))
        XCTAssertEqual(fallback.language, "en")
    }

    func testAPageDeclaringEnglishWhoseIngredientsAreClearlyGermanIsGerman() throws {
        let recipe = try parse(page("en", #"{"@type": "Recipe", "name": "Rührkuchen", "recipeIngredient": ["500 g Mehl", "200 g Zucker", "3 Eier", "1 Prise Salz", "2 EL Öl"]}"#))
        XCTAssertEqual(recipe.language, "de")
    }

    func testAPageDeclaringEnglishWithAmbiguousIngredientsStaysEnglish() throws {
        let recipe = try parse(page("en", #"{"@type": "Recipe", "name": "Kuchen", "recipeIngredient": ["200 g Mehl", "1 cup sugar"]}"#))
        XCTAssertEqual(recipe.language, "en")
    }

    func testEnglishWordsReadAnEnglishRecipesTimesAndCondensedSections() throws {
        let recipe = try parse(page("en-US", #"{"@type": "Recipe", "name": "Cake", "recipeIngredient": ["1 cup flour"], "prepTime": "1 hour 30 minutes", "cookTime": "PT20M", "# + sections + "}"))
        XCTAssertEqual(recipe.prepTime, "1h 30m")
        XCTAssertEqual(recipe.cookTime, "20m")
        XCTAssertEqual(recipe.instructions, ["Mix."])
    }

    func testALanguageWithNoWordsReadsIsoTimesOnlyAndSkipsNoSection() throws {
        let recipe = try parse(page("de", #"{"@type": "Recipe", "name": "Kuchen", "recipeIngredient": ["200 g Mehl"], "prepTime": "1 hour 30 minutes", "cookTime": "PT20M", "# + sections + "}"))
        XCTAssertEqual(recipe.prepTime, "1 hour 30 minutes")
        XCTAssertEqual(recipe.cookTime, "20m")
        XCTAssertEqual(recipe.instructions, ["Short version.", "Mix."])
    }

    func testMicrodataRecordsInLanguageToo() throws {
        let html = """
            <html lang="en"><body><div itemscope itemtype="https://schema.org/Recipe">
            <meta itemprop="inLanguage" content="es-MX"><h1 itemprop="name">Tacos</h1>
            <li itemprop="recipeIngredient">2 tortillas</li></div></body></html>
            """
        XCTAssertEqual(try parse(html).language, "es-mx")
    }

    func testThePagesLangIsReadLikeJsoup() {
        XCTAssertEqual(JsonLdRecipeParser.pageLanguage(html: #"<!doctype html><HTML class="a" LANG='pt-BR'>"#), "pt-BR")
        XCTAssertEqual(JsonLdRecipeParser.pageLanguage(html: #"<html xml:lang="de" lang=en>"#), "en")
        XCTAssertNil(JsonLdRecipeParser.pageLanguage(html: #"<html xml:lang="de">"#))
        XCTAssertNil(JsonLdRecipeParser.pageLanguage(html: "<html lang=\"\">"))
    }
}
