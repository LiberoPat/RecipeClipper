import XCTest
@testable import RecipeClipper

/// Reading Recipe Keeper's `recipes.html` (#390; Android's RecipeKeeperParserTest): by `itemprop`
/// only, over the markup the real export has (unclosed `<meta>`, unquoted attributes,
/// `itemprop=photo0/`). Both platforms read the same pages and expect the same values.
final class RecipeKeeperParserTests: XCTestCase {

    private func page(_ blocks: String...) -> String { "<html><body>\(blocks.joined())</body></html>" }

    private func block(_ inner: String) -> String { "<div class=recipe-details>\(inner)</div>" }

    private let full = """
    <div class=recipe-details>
    <meta content=abc-123 itemprop=recipeId><meta content="" itemprop=recipeShareId>
    <meta content=True itemprop=recipeIsFavourite><meta content=3 itemprop=recipeRating>
    <h2 itemprop=name>Tarte &amp; Crème</h2>
    <div>Portate: <span itemprop=recipeCourse>Dolce</span><meta content="Primo" itemprop=recipeCourse></div>
    <div>Categorie: <span>Dolci</span><meta content=Dolci itemprop=recipeCategory></div>
    <div>Raccolte: <meta content="Nonna" itemprop=recipeCollection></div>
    <div>Fonte: <span itemprop=recipeSource>https://www.Example.com/tarte?utm_source=x&amp;id=2#c</span></div>
    <div>Porzioni: <span itemprop=recipeYield>4 persone</span></div>
    <meta content=PT15M itemprop=prepTime><meta content=PT0S itemprop=cookTime>
    <div itemprop=recipeIngredients><p><b>La base</b></p><p>200 g farina</p><p></p><p>Il ripieno:</p><p>3 uova</p><p><strong>Sugo </strong><b>speciale</b></p></div>
    <div itemprop=recipeDirections><p>1. Impastare.</p><p></p><p>2. Cuocere a 180&#176;C.</p><p>350 gradi</p></div>
    <div itemprop=recipeNotes><p>Prima nota.</p><p>Seconda nota.</p></div>
    <img src=images/abc-123_1.jpg class=recipe-photos itemprop=photo1/>
    <img src=images/abc-123_0.jpg class=recipe-photos itemprop=photo0/>
    </div>
    """

    func testARecipeIsReadByItsItempropsWhateverTheLabelsSay() throws {
        let parsed = RecipeKeeperParser.parse(page(full))
        XCTAssertEqual(parsed.blocks, 1)
        XCTAssertEqual(parsed.notRecipes, 0)
        let r = try XCTUnwrap(parsed.recipes.first)
        XCTAssertEqual(r.id, "abc-123")
        XCTAssertEqual(r.name, "Tarte & Crème")
        XCTAssertEqual(r.servings, "4 persone")
        XCTAssertEqual(r.prepTime, "PT15M")
        XCTAssertEqual(r.cookTime, "PT0S")
        XCTAssertEqual(r.notes, "Prima nota.\nSeconda nota.")
        XCTAssertEqual(r.categories, ["Dolce", "Primo", "Dolci", "Nonna"])
        XCTAssertTrue(r.favorite)
    }

    func testALinkIsKeptCleanedAnythingElseInTheSourceIsNone() throws {
        XCTAssertEqual(RecipeKeeperParser.parse(page(full)).recipes.first?.sourceUrl, "https://www.example.com/tarte?id=2")
        for text in ["Recipe Keeper", "n/a", "ftp://x.example/a", "www.example.com", ""] {
            let b = block("<h2 itemprop=name>A</h2><span itemprop=recipeSource>\(text)</span><div itemprop=recipeIngredients><p>1 egg</p></div>")
            XCTAssertNil(try XCTUnwrap(RecipeKeeperParser.parse(page(b)).recipes.first).sourceUrl, text)
        }
    }

    func testABoldLineIsAnIngredientHeadingWrittenWithAColonAndBlanksAreDropped() throws {
        let lines = try XCTUnwrap(RecipeKeeperParser.parse(page(full)).recipes.first).ingredients
        XCTAssertEqual(lines, ["La base:", "200 g farina", "Il ripieno:", "3 uova", "Sugo speciale:"])
    }

    func testABoldWordInsideALineIsNoHeading() throws {
        let b = block("<h2 itemprop=name>A</h2><div itemprop=recipeIngredients><p>2 <b>large</b> eggs</p><p><b>Salt</b>, to taste</p></div>")
        XCTAssertEqual(try XCTUnwrap(RecipeKeeperParser.parse(page(b)).recipes.first).ingredients, ["2 large eggs", "Salt, to taste"])
    }

    func testADirectionLosesALeadingNumberAndItsDotAndOnlyThat() throws {
        XCTAssertEqual(
            try XCTUnwrap(RecipeKeeperParser.parse(page(full)).recipes.first).directions,
            ["Impastare.", "Cuocere a 180°C.", "350 gradi"]
        )
        let b = block("<h2 itemprop=name>A</h2><div itemprop=recipeDirections><p>12. Serve.</p><p>2.5 cups later</p><p>1.Stir</p></div>")
        XCTAssertEqual(
            try XCTUnwrap(RecipeKeeperParser.parse(page(b)).recipes.first).directions, ["Serve.", "2.5 cups later", "1.Stir"]
        )
    }

    func testPhotosComeInTheirOwnOrderFromAnUnquotedItempropWithASlash() throws {
        XCTAssertEqual(
            try XCTUnwrap(RecipeKeeperParser.parse(page(full)).recipes.first).photos, ["images/abc-123_0.jpg", "images/abc-123_1.jpg"]
        )
    }

    func testABlockWithNoNameOrWithNeitherIngredientsNorStepsIsNotARecipe() {
        let noName = block("<h2 itemprop=name></h2><div itemprop=recipeIngredients><p>1 egg</p></div>")
        let noText = block("<h2 itemprop=name>Title</h2><div itemprop=recipeIngredients><p></p></div>")
        let parsed = RecipeKeeperParser.parse(page(noName, noText, full))
        XCTAssertEqual(parsed.blocks, 3)
        XCTAssertEqual(parsed.notRecipes, 2)
        XCTAssertEqual(parsed.recipes.map(\.name), ["Tarte & Crème"])
    }

    func testAFavouriteIsOnlyTheWordTrueAMissingIdIsNone() throws {
        let b = block("<meta content=False itemprop=recipeIsFavourite><h2 itemprop=name>A</h2><div itemprop=recipeIngredients><p>1 egg</p></div>")
        let r = try XCTUnwrap(RecipeKeeperParser.parse(page(b)).recipes.first)
        XCTAssertFalse(r.favorite)
        XCTAssertNil(r.id)
    }

    func testAPageWithoutRecipeBlocksHasNoneABOMIsStrippedAndAnEmptyTextIsNothing() {
        XCTAssertEqual(RecipeKeeperParser.parse("<html><body><h1>Hi</h1></body></html>").blocks, 0)
        XCTAssertEqual(RecipeKeeperParser.parse("\u{FEFF}" + page(full)).blocks, 1)
        XCTAssertEqual(RecipeKeeperParser.parse("").blocks, 0)
    }

    func testTextWithNoParagraphsIsOneLineNotesAndAll() throws {
        let b = block("<h2 itemprop=name>A</h2><div itemprop=recipeIngredients>1 egg and 2 cups flour</div><div itemprop=recipeNotes>Plain note</div>")
        let r = try XCTUnwrap(RecipeKeeperParser.parse(page(b)).recipes.first)
        XCTAssertEqual(r.ingredients, ["1 egg and 2 cups flour"])
        XCTAssertEqual(r.notes, "Plain note")
    }
}
