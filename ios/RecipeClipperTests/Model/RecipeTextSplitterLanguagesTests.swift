import XCTest
@testable import RecipeClipper

/// Reddit posts and photo text in every language the app reads (#208): each language's own
/// headers and unit words (`shared/tables/<language>/splitter.json`), chosen by the text's words,
/// never merged. The fixtures (`shared/fixtures/languages`) and expectations are Android's
/// `RecipeTextSplitterLanguagesTest`'s.
final class RecipeTextSplitterLanguagesTests: XCTestCase {

    private let languages = ["en", "de", "es", "fr", "it", "pt", "ja"]

    private final class Anchor {}

    private func fixture(_ language: String) -> [String: Any] {
        let bundle = Bundle(for: Anchor.self)
        guard let url = bundle.url(forResource: language, withExtension: "json", subdirectory: "fixtures/languages"),
              let data = try? Data(contentsOf: url),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { fatalError("missing fixture fixtures/languages/\(language).json") }
        return json
    }

    private func part(_ language: String, _ key: String) -> [String: Any] { fixture(language)[key] as! [String: Any] }

    private func strings(_ o: [String: Any], _ key: String) -> [String] { o[key] as! [String] }

    func testAPostSplitsInItsOwnLanguage() throws {
        for language in languages {
            let post = part(language, "post")
            let split = try XCTUnwrap(
                RecipeTextSplitter.detectAndSplit(post["body"] as! String, context: post["title"] as! String), language
            )
            XCTAssertEqual(split.language, language)
            XCTAssertEqual(split.ingredients, strings(post, "ingredients"), language)
            XCTAssertEqual(split.instructions, strings(post, "instructions"), language)
            XCTAssertEqual(split.yield, post["yield"] as? String, language)
            XCTAssertEqual(split.prepTime, post["prepTime"] as? String, language)
            XCTAssertEqual(split.cookTime, post["cookTime"] as? String, language)
            XCTAssertEqual(split.totalTime, post["totalTime"] as? String, language)
        }
    }

    func testAPhotosLinesSortInTheirOwnLanguageWithGluedUnitsToCheck() {
        for language in languages {
            let photo = part(language, "photo")
            let reading = PhotoTextSorter.sort(strings(photo, "lines").map { PhotoLine(text: $0) })
            XCTAssertTrue(reading.sorted, language)
            XCTAssertEqual(reading.language, language)
            XCTAssertEqual(reading.ingredients, strings(photo, "ingredients"), language)
            XCTAssertEqual(reading.instructions, strings(photo, "instructions"), language)
            XCTAssertEqual(reading.uncertain, strings(photo, "uncertain"), language)
        }
    }

    func testProseAndRequestsNeverSplitInAnyLanguage() {
        let sentence = JRegex(#"([.!?。！？])\s*"#)
        for language in languages {
            for text in fixture(language)["negatives"] as! [String] {
                XCTAssertNil(RecipeTextSplitter.detectAndSplit(text), "\(language): \(text)")
                let lines = sentence.replace(text) { $0[1] + "\n" }.components(separatedBy: "\n").filter { !$0.kIsBlank }.map { PhotoLine(text: $0) }
                XCTAssertFalse(PhotoTextSorter.sort(lines).sorted, "\(language): \(text)")
            }
        }
    }

    func testLanguagesAreNeverMerged() {
        // Another language's words know none of the headers: what's left is only the structure.
        for language in languages where language != "en" {
            let post = part(language, "post")
            let english = RecipeTextSplitter.split(post["body"] as! String, words: .english)
            XCTAssertTrue(english == nil || english!.ingredients != strings(post, "ingredients"), language)
        }
        let post = part("en", "post")
        for language in languages where language != "en" {
            let other = RecipeTextSplitter.split(post["body"] as! String, words: LanguageWords.forTag(language)!)
            XCTAssertTrue(other == nil || other!.ingredients != strings(post, "ingredients"), language)
        }
    }

    func testTextWhoseWordsSayNothingClearIsReadInEnglish() {
        XCTAssertNil(RecipeTextSplitter.languageOf("Zutaten\n2 Eier"))
        XCTAssertEqual(RecipeTextSplitter.wordsFor(nil), .english)
    }

    private func section(_ raw: String, _ language: String) -> RecipeTextSplitter.Section? {
        let words = LanguageWords.forTag(language)!
        return RecipeTextSplitter.header(RecipeTextSplitter.line(raw, words: words), words: words)?.section
    }

    private func label(_ raw: String, _ language: String) -> String? {
        let words = LanguageWords.forTag(language)!
        return RecipeTextSplitter.header(RecipeTextSplitter.line(raw, words: words), words: words)?.label
    }

    func testEachLanguagesHeadersWithTheFuzzyRules() {
        // Whole lines, with what may surround them.
        XCTAssertEqual(section("Zutaten:", "de"), .ingredients)
        XCTAssertEqual(section("## Anleitung", "de"), .instructions)
        XCTAssertEqual(section("Ingredientes (para 4)", "es"), .ingredients)
        XCTAssertEqual(section("Elaboración:", "es"), .instructions)
        XCTAssertEqual(section("Étapes :", "fr"), .instructions)
        XCTAssertEqual(section("Instructions", "fr"), .instructions)
        XCTAssertEqual(section("PROCEDIMENTO", "it"), .instructions)
        XCTAssertEqual(section("Modo de preparo:", "pt"), .instructions)
        XCTAssertEqual(section("Preparo", "pt"), .instructions)
        XCTAssertEqual(section("■材料", "ja"), .ingredients)
        XCTAssertEqual(section("手順", "ja"), .instructions)
        XCTAssertEqual(section("Tipps:", "de"), .end)
        XCTAssertEqual(section("Astuces", "fr"), .end)
        XCTAssertEqual(section("Edit: Tippfehler", "de"), .end)
        // A servings phrase needs no colon; any other "for" header does.
        XCTAssertEqual(section("Ingredienti per 4 persone", "it"), .ingredients)
        XCTAssertEqual(section("Zutaten für 4 Personen", "de"), .ingredients)
        XCTAssertEqual(section("Ingrédients pour la pâte :", "fr"), .ingredients)
        XCTAssertNil(section("Ingredientes para esta receta son baratos", "es"))
        // Words beside the keyword: before it in German, after it in the Romance languages.
        XCTAssertEqual(label("**Trockene Zutaten**", "de"), "Trockene Zutaten:")
        XCTAssertEqual(label("**Ingredientes secos**", "es"), "Ingredientes secos:")
        XCTAssertEqual(label("**Ingrédients secs**", "fr"), "Ingrédients secs:")
        XCTAssertEqual(label("**Ingredienti per la crema**", "it"), "Ingredienti per la crema:")
        XCTAssertEqual(label("**Ingredientes da cobertura**", "pt"), "Ingredientes da cobertura:")
        XCTAssertEqual(section("**Die Zutaten**", "de"), .ingredients)
        XCTAssertNil(label("**Die Zutaten**", "de"))
        XCTAssertNil(label("**Lista de ingredientes**", "es"))
        // A step is never a header.
        XCTAssertNil(section("**Mélanger les ingrédients**", "fr"))
        XCTAssertNil(section("**Mezclar los ingredientes**", "es"))
        XCTAssertNil(section("**Alle Zutaten verrühren**", "de"))
        // Typos two letters off, in a header set apart as one.
        XCTAssertEqual(section("ZUBEREITNUG", "de"), .instructions)
        XCTAssertEqual(section("**Ingedientes**", "es"), .ingredients)
        XCTAssertEqual(section("**Ingrédiens**", "fr"), .ingredients)
        XCTAssertEqual(section("**Procedimeto**", "it"), .instructions)
        XCTAssertEqual(section("**Ingredintes**", "pt"), .ingredients)
        XCTAssertNil(section("Ingedientes", "es"))
        // Numbered steps by the language's own label.
        let schritt = RecipeTextSplitter.line("Schritt 2: Rühren.", words: LanguageWords.forTag("de")!)
        XCTAssertEqual(schritt.number, 2)
        XCTAssertEqual(schritt.text, "Rühren.")
        XCTAssertEqual(RecipeTextSplitter.line("Paso 1. Mezclar.", words: LanguageWords.forTag("es")!).number, 1)
        XCTAssertEqual(RecipeTextSplitter.line("Passo 3 - Assar.", words: LanguageWords.forTag("pt")!).number, 3)
    }

    func testGluedUnitsInEachLanguageAndOnlyThere() {
        func suspect(_ line: String, _ language: String) -> Bool {
            PhotoTextSorter.suspect(line, words: LanguageWords.forTag(language)!)
        }
        XCTAssertTrue(suspect("2 ELZucker", "de"))
        XCTAssertTrue(suspect("1 TLSalz", "de"))
        XCTAssertTrue(suspect("2 Esslöffelzucker", "de"))
        XCTAssertTrue(suspect("1 tazaharina", "es"))
        XCTAssertTrue(suspect("2 cucharadasazúcar", "es"))
        XCTAssertTrue(suspect("1 c. à soupesucre", "fr"))
        XCTAssertTrue(suspect("2 cuillères à soupesucre", "fr"))
        XCTAssertTrue(suspect("2 cucchiaizucchero", "it"))
        XCTAssertTrue(suspect("1 xícaraleite", "pt"))
        XCTAssertTrue(suspect("2 colheresaçúcar", "pt"))
        // Units the scaler reads, ordinary words, and short units left out.
        XCTAssertFalse(suspect("2 EL Zucker", "de"))
        XCTAssertFalse(suspect("2 Eier", "de"))
        XCTAssertFalse(suspect("2 Elche", "de"))
        XCTAssertFalse(suspect("1 taza de harina", "es"))
        XCTAssertFalse(suspect("2 cucharaditas de sal", "es"))
        XCTAssertFalse(suspect("2 cuillères de sucre", "fr"))
        XCTAssertFalse(suspect("100 g cassonade", "fr"))
        XCTAssertFalse(suspect("2 cassonade", "fr"))
        XCTAssertFalse(suspect("1 cucchiaino di sale", "it"))
        XCTAssertFalse(suspect("1 litro di latte", "it"))
        XCTAssertFalse(suspect("2 colheres de sopa de açúcar", "pt"))
        XCTAssertFalse(suspect("2 gramas de sal", "pt"))
        // Japanese has no spaces, so no glued-unit check; the other shapes still apply.
        XCTAssertFalse(suspect("砂糖 大さじ2", "ja"))
        XCTAssertTrue(suspect("11/2 カップ", "ja"))
        // Languages never merged.
        XCTAssertFalse(suspect("1 cupraisins", "de"))
        XCTAssertFalse(suspect("2 ELZucker", "en"))
    }

    func testAGermanPostKeepsGermanAsTheRecipesLanguage() throws {
        let post = part("de", "post")
        let json = listing([
            thing("t3", ["title": post["title"]!, "selftext": post["body"]!]),
        ], comments: [])
        guard case .success(let recipe) = RedditRecipeParser.parse(json, sourceUrl: "https://www.reddit.com/r/Kochen/comments/abc/x/")
        else { return XCTFail("no recipe") }
        XCTAssertEqual(recipe.language, "de")
        XCTAssertEqual(recipe.ingredients, strings(post, "ingredients"))
    }

    func testAFrenchRecipeInThePostersCommentIsReadInFrench() throws {
        let post = part("fr", "post")
        let json = listing([
            thing("t3", ["title": post["title"]!, "selftext": ""]),
        ], comments: [thing("t1", ["body": post["body"]!, "is_submitter": true, "author": "op"])])
        guard case .success(let recipe) = RedditRecipeParser.parse(json, sourceUrl: "https://www.reddit.com/r/cuisine/comments/abc/x/")
        else { return XCTFail("no recipe") }
        XCTAssertEqual(recipe.language, "fr")
        XCTAssertEqual(recipe.instructions, strings(post, "instructions"))
    }

    private func thing(_ kind: String, _ data: [String: Any]) -> [String: Any] { ["kind": kind, "data": data] }

    /// A Reddit listing pair: the post, then its comments.
    private func listing(_ posts: [[String: Any]], comments: [[String: Any]]) -> String {
        let pair: [Any] = [
            ["kind": "Listing", "data": ["children": posts]],
            ["kind": "Listing", "data": ["children": comments]],
        ]
        let data = try! JSONSerialization.data(withJSONObject: pair)
        return String(data: data, encoding: .utf8)!
    }
}
