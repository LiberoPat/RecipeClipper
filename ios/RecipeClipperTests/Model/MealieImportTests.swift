import XCTest
@testable import RecipeClipper

/// How a Mealie recipe becomes a library recipe (#391), pure; Android's MealieImportTest expects the same.
final class MealieImportTests: XCTestCase {

    private func recipe(
        name: String = "Soup", slug: String = "soup", id: String? = nil, sourceUrl: String? = nil,
        ingredients: [String] = ["1 onion"], instructions: [String] = ["Simmer."],
        prep: String? = nil, cook: String? = nil, total: String? = nil,
        notes: String? = nil, servings: String? = nil,
        categories: [String] = ["Dinner"], photoFile: String? = nil
    ) -> MealieRecipe {
        MealieRecipe(
            slug: slug, id: id, name: name, ingredients: ingredients, instructions: instructions, notes: notes,
            servings: servings, prepTime: prep, cookTime: cook, totalTime: total, sourceUrl: sourceUrl,
            categories: categories, photoFile: photoFile
        )
    }

    private var uids = 0
    private func map(_ recipes: [MealieRecipe], now: Int64 = 1_000_000, filing: Bool = false) -> MealieImport.Mapped {
        MealieImport.toBackup(recipes, now: now, filing: filing) { defer { uids += 1 }; return "uid-\(uids)" }
    }

    func testARecipeWithALinkIsKeyedByTheLinkAndKeptAsTheCooksVersion() throws {
        let r = try XCTUnwrap(map([recipe(slug: "abc", sourceUrl: "https://www.example.com/soup?id=1")]).backup.recipes.first)
        XCTAssertEqual(r.sourceUrl, "https://www.example.com/soup?id=1")
        XCTAssertEqual(r.contentOrigin, "EDITED")
        XCTAssertEqual(r.editedAt, 1_000_000)
        XCTAssertNil(r.imageUrl)
    }

    func testOneWithNoLinkIsTypedInKeyedByItsSlugLowerCase() throws {
        let r = try XCTUnwrap(map([recipe(slug: "Pasta-e-Ceci")]).backup.recipes.first)
        XCTAssertEqual(r.sourceUrl, "manual:mealie-pasta-e-ceci")
        XCTAssertEqual(r.contentOrigin, "MANUAL")
    }

    func testASlugThatIsNotPlainFallsBackToTheIdThenToAHashOfTheText() {
        var tea = recipe(name: "Mint Tea", slug: "../évil/x", ingredients: [], instructions: ["Steep mint leaves in hot water for 5 minutes."])
        // FNV-1a over "name\ningredients...\nsteps...": pinned so both platforms agree (the Paprika import's hash of the same text).
        XCTAssertEqual(MealieImport.identity(tea), "manual:mealie-h258018455a849b2a")
        tea.id = "http://evil/x"
        XCTAssertEqual(MealieImport.identity(tea), "manual:mealie-h258018455a849b2a")
        tea.id = "8A6B-01"
        XCTAssertEqual(MealieImport.identity(tea), "manual:mealie-8a6b-01")
        tea.slug = String(repeating: "a", count: 101)
        tea.id = nil
        XCTAssertEqual(MealieImport.identity(tea), "manual:mealie-h258018455a849b2a")
    }

    func testARepeatInTheFileIsCountedNotWrittenAndTheFirstWins() {
        let mapped = map([
            recipe(name: "First", sourceUrl: "https://x.example/r?utm_source=a"),
            recipe(name: "Second", slug: "second", sourceUrl: "https://x.example/r"),
            recipe(name: "Third", slug: "third"),
            recipe(name: "Fourth", slug: "THIRD"),
        ])
        XCTAssertEqual(mapped.backup.recipes.map(\.title), ["First", "Third"])
        XCTAssertEqual(mapped.repeated, 2)
    }

    func testTimesGoThroughTheTimeRules() throws {
        let r = try XCTUnwrap(map([recipe(prep: "20 minutes", cook: "1 hour 30 minutes", total: "Overnight")]).backup.recipes.first)
        XCTAssertEqual(r.prepTime, "20m")
        XCTAssertEqual(r.cookTime, "1h 30m")
        XCTAssertEqual(r.totalTime, "Overnight")
        let iso = try XCTUnwrap(map([recipe(prep: "PT10M", cook: "0 minutes", total: "PT5400S")]).backup.recipes.first)
        XCTAssertEqual(iso.prepTime, "10m")
        XCTAssertNil(iso.cookTime)
        XCTAssertEqual(iso.totalTime, "1h 30m")
    }

    func testTextServingsAndNotesAreCarriedAsTheyAre() throws {
        let r = try XCTUnwrap(map([recipe(
            ingredients: ["For the sauce:", "2 tbsp butter"], instructions: ["Melt.", "Stir."], notes: "Less sugar", servings: "12 brownies"
        )]).backup.recipes.first)
        XCTAssertEqual(r.ingredients, ["For the sauce:", "2 tbsp butter"])
        XCTAssertEqual(r.instructions, ["Melt.", "Stir."])
        XCTAssertEqual(r.servings, "12 brownies")
        XCTAssertEqual(r.notes, "Less sugar")
        XCTAssertEqual(r.sourceType, "BLOG")
    }

    func testTheLanguageIsTheRecipesOwnWordsNeverThePhones() throws {
        let spanish = recipe(
            name: "Sopa de lentejas", slug: "s", ingredients: ["1 taza de lentejas", "2 cucharadas de aceite", "sal y pimienta", "agua"],
            instructions: ["Cocer."]
        )
        XCTAssertEqual(try XCTUnwrap(map([spanish]).backup.recipes.first).language, "es")
        XCTAssertEqual(try XCTUnwrap(map([recipe(ingredients: ["1 cup rice"])]).backup.recipes.first).language, "en")
    }

    func testWithTheSwitchOffCategoriesAndTagsAreNotFiledIntoLists() {
        let backup = map([recipe(), recipe(name: "B", slug: "b")]).backup
        XCTAssertTrue(backup.lists.isEmpty)
        XCTAssertTrue(backup.memberships.isEmpty)
    }

    func testWithItOnACategoryOrTagIsAListCaseInsensitiveTheFirstSpellingKeptAndNothingIsAFavourite() {
        let backup = map([
            recipe(name: "A", slug: "a", categories: ["Dessert", " baking "]),
            recipe(name: "B", slug: "b", categories: ["BAKING", "", "Soup"]),
        ], filing: true).backup
        XCTAssertEqual(backup.lists.map(\.name), ["Dessert", "baking", "Soup"])
        XCTAssertTrue(backup.lists.allSatisfy { !$0.isFavorites })
        let ids = Dictionary(uniqueKeysWithValues: backup.recipes.map { ($0.title, $0.id) })
        let named = Dictionary(uniqueKeysWithValues: backup.lists.map { ($0.id, $0.name) })
        func inLists(_ title: String) -> [String] { backup.memberships.filter { $0.recipeId == ids[title] }.map { named[$0.listId]! } }
        XCTAssertEqual(inLists("A"), ["Dessert", "baking"])
        XCTAssertEqual(inLists("B"), ["baking", "Soup"])
    }

    func testARepeatInTheFileAddsItsCategoriesToTheFirst() {
        let backup = map([
            recipe(name: "First", slug: "x", categories: ["Dinner"]),
            recipe(name: "Second", slug: "X", categories: ["Quick"]),
        ], filing: true).backup
        XCTAssertEqual(backup.recipes.count, 1)
        XCTAssertEqual(Set(backup.lists.map(\.name)), ["Dinner", "Quick"])
        XCTAssertEqual(backup.memberships.count, 2)
    }

    func testThePictureTheReaderStoredIsTheRecipesOwnUnderThatName() throws {
        XCTAssertEqual(try XCTUnwrap(map([recipe(photoFile: "stored-1.jpg")]).backup.recipes.first).photo, "stored-1.jpg")
        XCTAssertNil(try XCTUnwrap(map([recipe()]).backup.recipes.first).photo)
    }

    func testTheFilesOrderIsKeptTheFirstBeingTheMostRecentlyViewed() {
        let rows = map([recipe(name: "A", slug: "a"), recipe(name: "B", slug: "b"), recipe(name: "C", slug: "c")], now: 5_000).backup.recipes
        XCTAssertEqual(rows.map(\.lastViewedAt), [5_000, 4_999, 4_998])
    }

    func testEachRecipeGetsItsOwnFreshUid() {
        let ids = map([recipe(slug: "a"), recipe(name: "B", slug: "b")]).backup.recipes.map(\.id)
        XCTAssertEqual(Set(ids).count, 2)
    }
}
