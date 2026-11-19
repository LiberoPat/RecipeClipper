import XCTest
@testable import RecipeClipper

/// How a Paprika recipe becomes a library recipe (#382), pure; Android's PaprikaImportTest expects the same.
final class PaprikaImportTests: XCTestCase {

    private func recipe(
        name: String = "Soup", uid: String? = nil, sourceUrl: String? = nil,
        ingredients: [String] = ["1 onion"], directions: [String] = ["Simmer."],
        prep: String? = nil, cook: String? = nil, total: String? = nil,
        notes: String? = nil, servings: String? = nil, imageUrl: String? = nil,
        categories: [String] = ["Dinner"], photoFile: String? = nil, favorite: Bool = false
    ) -> PaprikaRecipe {
        PaprikaRecipe(
            uid: uid, name: name, ingredients: ingredients, directions: directions, notes: notes, servings: servings,
            prepTime: prep, cookTime: cook, totalTime: total, sourceUrl: sourceUrl, imageUrl: imageUrl,
            categories: categories, hasPhoto: true, photoFile: photoFile, favorite: favorite
        )
    }

    private var uids = 0
    private func map(_ recipes: [PaprikaRecipe], now: Int64 = 1_000_000, filing: Bool = false) -> PaprikaImport.Mapped {
        PaprikaImport.toBackup(recipes, now: now, filing: filing) { defer { uids += 1 }; return "uid-\(uids)" }
    }

    func testARecipeWithALinkIsKeyedByTheLinkAndKeptAsTheCooksVersion() throws {
        let r = try XCTUnwrap(map([recipe(uid: "ABC", sourceUrl: "https://www.example.com/soup?id=1")]).backup.recipes.first)
        XCTAssertEqual(r.sourceUrl, "https://www.example.com/soup?id=1")
        XCTAssertEqual(r.contentOrigin, "EDITED")
        XCTAssertEqual(r.editedAt, 1_000_000)
    }

    func testOneWithNoLinkIsTypedInKeyedByPaprikasIdLowerCase() throws {
        let r = try XCTUnwrap(map([recipe(uid: "0A9B8C7D-6E5F-4A3B")]).backup.recipes.first)
        XCTAssertEqual(r.sourceUrl, "manual:paprika-0a9b8c7d-6e5f-4a3b")
        XCTAssertEqual(r.contentOrigin, "MANUAL")
    }

    func testWithNoIdOrAnIdThatIsNotPlainItIsKeyedByAHashOfItsText() {
        let tea = recipe(name: "Mint Tea", ingredients: [], directions: ["Steep mint leaves in hot water for 5 minutes."])
        // FNV-1a over "name\ningredients...\ndirections...": pinned so both platforms agree.
        XCTAssertEqual(PaprikaImport.identity(tea), "manual:paprika-h258018455a849b2a")
        var withUrlId = tea
        withUrlId.uid = "http://evil/x"
        XCTAssertEqual(PaprikaImport.identity(withUrlId), "manual:paprika-h258018455a849b2a")
        withUrlId.uid = String(repeating: "a", count: 65)
        XCTAssertEqual(PaprikaImport.identity(withUrlId), "manual:paprika-h258018455a849b2a")
        withUrlId.uid = "Abc-123"
        XCTAssertTrue(PaprikaImport.identity(withUrlId).hasSuffix("abc-123"))
    }

    func testTheSameFileTwiceMakesTheSameKeys() {
        let file = [recipe(uid: "A"), recipe(name: "Tea", directions: ["Steep."]), recipe(sourceUrl: "https://x.example/r")]
        XCTAssertEqual(map(file).backup.recipes.map(\.sourceUrl), map(file).backup.recipes.map(\.sourceUrl))
    }

    func testARepeatInTheFileIsCountedNotWrittenAndTheFirstWins() {
        let mapped = map([
            recipe(name: "First", sourceUrl: "https://x.example/r?utm_source=a"),
            recipe(name: "Second", sourceUrl: "https://x.example/r"),
            recipe(name: "Third", uid: "U1"),
            recipe(name: "Fourth", uid: "u1"),
        ])
        XCTAssertEqual(mapped.backup.recipes.map(\.title), ["First", "Third"])
        XCTAssertEqual(mapped.repeated, 2)
    }

    func testATimeThatIsAWholePhraseRendersLikeIsoAnythingElseStaysAsWritten() throws {
        let r = try XCTUnwrap(map([recipe(prep: "20 minutes", cook: "1 hour 30 minutes", total: "Overnight")]).backup.recipes.first)
        XCTAssertEqual(r.prepTime, "20m")
        XCTAssertEqual(r.cookTime, "1h 30m")
        XCTAssertEqual(r.totalTime, "Overnight")
        let iso = try XCTUnwrap(map([recipe(prep: "PT10M", cook: "0 minutes", total: "1-2 hours")]).backup.recipes.first)
        XCTAssertEqual(iso.prepTime, "10m")
        XCTAssertNil(iso.cookTime)
        XCTAssertEqual(iso.totalTime, "1-2 hours")
    }

    func testTextServingsNotesAndTheWebPictureAreCarriedAsTheyAre() throws {
        let r = try XCTUnwrap(map([recipe(
            ingredients: ["For the sauce:", "2 tbsp butter"], directions: ["Melt.", "Stir."],
            notes: "Less sugar", servings: "12 brownies", imageUrl: "https://img.example/a.jpg"
        )]).backup.recipes.first)
        XCTAssertEqual(r.ingredients, ["For the sauce:", "2 tbsp butter"])
        XCTAssertEqual(r.instructions, ["Melt.", "Stir."])
        XCTAssertEqual(r.servings, "12 brownies")
        XCTAssertEqual(r.notes, "Less sugar")
        XCTAssertEqual(r.imageUrl, "https://img.example/a.jpg")
        XCTAssertEqual(r.sourceType, "BLOG")
    }

    func testTheLanguageIsTheRecipesOwnWordsNeverThePhones() throws {
        let spanish = recipe(
            name: "Sopa de lentejas", ingredients: ["1 taza de lentejas", "2 cucharadas de aceite", "sal y pimienta", "agua"],
            directions: ["Cocer."]
        )
        XCTAssertEqual(map([spanish]).backup.recipes.first?.language, "es")
        XCTAssertEqual(map([recipe(ingredients: ["1 cup rice"])]).backup.recipes.first?.language, "en")
    }

    func testWithTheSwitchOffCategoriesAreNotFiledIntoLists() {
        let backup = map([recipe(), recipe(name: "B", uid: "b", favorite: true)]).backup
        XCTAssertTrue(backup.lists.isEmpty)
        XCTAssertTrue(backup.memberships.isEmpty)
    }

    func testWithItOnACategoryIsAListCaseInsensitiveTheFirstSpellingKept() {
        let backup = map([
            recipe(name: "A", uid: "a", categories: ["Dessert", " baking "]),
            recipe(name: "B", uid: "b", categories: ["BAKING", "", "Soup"]),
        ], filing: true).backup
        XCTAssertEqual(backup.lists.map(\.name), ["Dessert", "baking", "Soup"])
        XCTAssertTrue(backup.lists.allSatisfy { !$0.isFavorites })
        let ids = Dictionary(uniqueKeysWithValues: backup.recipes.map { ($0.title, $0.id) })
        let named = Dictionary(uniqueKeysWithValues: backup.lists.map { ($0.id, $0.name) })
        func inLists(_ title: String) -> [String] {
            backup.memberships.filter { $0.recipeId == ids[title] }.map { named[$0.listId]! }
        }
        XCTAssertEqual(inLists("A"), ["Dessert", "baking"])
        XCTAssertEqual(inLists("B"), ["baking", "Soup"])
    }

    func testPaprikasFavouriteFlagFilesIntoFavoritesByIsFavoritesNeverByNameOneListForAll() throws {
        let backup = map([
            recipe(name: "A", uid: "a", categories: [], favorite: true),
            recipe(name: "B", uid: "b", categories: ["favorites"]),
            recipe(name: "C", uid: "c", categories: [], favorite: true),
        ], filing: true).backup
        let favorites = try XCTUnwrap(backup.lists.filter(\.isFavorites).first)
        XCTAssertEqual(backup.lists.filter(\.isFavorites).count, 1)
        let titles = Set(backup.memberships.filter { $0.listId == favorites.id }.map { m in backup.recipes.first { $0.id == m.recipeId }!.title })
        XCTAssertEqual(titles, ["A", "C"])
        // A category called favorites is a list of its own, not that one.
        let own = try XCTUnwrap(backup.lists.first { !$0.isFavorites })
        XCTAssertEqual(own.name, "favorites")
        XCTAssertEqual(backup.memberships.filter { $0.listId == own.id }.map { m in backup.recipes.first { $0.id == m.recipeId }!.title }, ["B"])
    }

    func testARepeatInTheFileAddsItsCategoriesAndItsFavouriteFlagToTheFirst() {
        let backup = map([
            recipe(name: "First", uid: "x", categories: ["Dinner"]),
            recipe(name: "Second", uid: "X", categories: ["Quick"], favorite: true),
        ], filing: true).backup
        XCTAssertEqual(backup.recipes.count, 1)
        XCTAssertEqual(Set(backup.lists.map(\.name)), ["Dinner", "Quick", "Favorites"])
        XCTAssertEqual(backup.memberships.count, 3)
    }

    func testThePictureTheArchiveStoredIsTheRecipesOwnUnderThatName() {
        XCTAssertEqual(map([recipe(uid: "a", photoFile: "stored-1.jpg")]).backup.recipes.first?.photo, "stored-1.jpg")
        XCTAssertNil(map([recipe(uid: "a")]).backup.recipes.first?.photo)
    }

    func testTheFilesOrderIsKeptTheFirstBeingTheMostRecentlyViewed() {
        let recipes = map([recipe(name: "A", uid: "a"), recipe(name: "B", uid: "b"), recipe(name: "C", uid: "c")], now: 5_000).backup.recipes
        XCTAssertEqual(recipes.map(\.lastViewedAt), [5_000, 4_999, 4_998])
    }

    func testEachRecipeGetsItsOwnFreshUid() {
        let ids = map([recipe(uid: "a"), recipe(name: "B", uid: "b")]).backup.recipes.map(\.id)
        XCTAssertEqual(Set(ids).count, 2)
    }
}
