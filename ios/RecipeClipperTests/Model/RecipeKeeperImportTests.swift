import XCTest
@testable import RecipeClipper

/// How a Recipe Keeper recipe becomes a library recipe (#390; Android's RecipeKeeperImportTest): the
/// Paprika mapping with the source that names a recipe's own key `manual:recipekeeper-…`. Pure.
final class RecipeKeeperImportTests: XCTestCase {

    private func recipe(
        name: String = "Soup", uid: String? = nil, sourceUrl: String? = nil,
        ingredients: [String] = ["For the broth:", "1 onion"], directions: [String] = ["Simmer."],
        prep: String? = nil, cook: String? = nil, servings: String? = nil,
        categories: [String] = [], favorite: Bool = false, photoFile: String? = nil
    ) -> PaprikaRecipe {
        PaprikaRecipe(
            uid: uid, name: name, ingredients: ingredients, directions: directions, notes: nil, servings: servings,
            prepTime: prep, cookTime: cook, totalTime: nil, sourceUrl: sourceUrl, imageUrl: nil,
            categories: categories, hasPhoto: photoFile != nil, photoFile: photoFile, favorite: favorite
        )
    }

    private var uids = 0
    private func map(_ recipes: [PaprikaRecipe], filing: Bool = false) -> PaprikaImport.Mapped {
        PaprikaImport.toBackup(recipes, now: 1_000_000, filing: filing, source: .recipeKeeper) { defer { uids += 1 }; return "uid-\(uids)" }
    }

    func testARecipeWithNoLinkIsKeyedByRecipeKeepersIdLowerCaseAndIsTypedIn() throws {
        let r = try XCTUnwrap(map([recipe(uid: "A2489B23-050F-5C8A")]).backup.recipes.first)
        XCTAssertEqual(r.sourceUrl, "manual:recipekeeper-a2489b23-050f-5c8a")
        XCTAssertEqual(r.contentOrigin, "MANUAL")
    }

    func testOneWithALinkIsKeyedByTheLinkAndKeptAsTheCooksVersionNeverRefetched() throws {
        let r = try XCTUnwrap(map([recipe(uid: "X", sourceUrl: "https://www.example.com/soup?id=1")]).backup.recipes.first)
        XCTAssertEqual(r.sourceUrl, "https://www.example.com/soup?id=1")
        XCTAssertEqual(r.contentOrigin, "EDITED")
    }

    func testWithNoUsableIdItIsKeyedByAHashOfItsTextUnderItsOwnPrefix() {
        let tea = recipe(name: "Mint Tea", ingredients: [], directions: ["Steep mint leaves in hot water for 5 minutes."])
        XCTAssertEqual(PaprikaImport.identity(tea, source: .recipeKeeper), "manual:recipekeeper-h258018455a849b2a")
        var odd = tea
        odd.uid = "../x"
        XCTAssertEqual(PaprikaImport.identity(odd, source: .recipeKeeper), "manual:recipekeeper-h258018455a849b2a")
        // The same recipe from the other app is a different key: the two imports never meet.
        XCTAssertEqual(PaprikaImport.identity(tea), "manual:paprika-h258018455a849b2a")
    }

    func testAnIngredientHeadingStaysALineEndingInAColonAndTheLinesStayAsWritten() throws {
        let r = try XCTUnwrap(map([recipe(ingredients: ["For the broth:", "1 onion", "2 l water"])]).backup.recipes.first)
        XCTAssertEqual(r.ingredients, ["For the broth:", "1 onion", "2 l water"])
    }

    func testIsoTimesGoThroughTheTimeRulesAZeroIsNoneServingsStayAsWritten() throws {
        let r = try XCTUnwrap(map([recipe(prep: "PT10M", cook: "PT0S", servings: "Serves 6-8")]).backup.recipes.first)
        XCTAssertEqual(r.prepTime, "10m")
        XCTAssertNil(r.cookTime)
        XCTAssertNil(r.totalTime)
        XCTAssertEqual(r.servings, "Serves 6-8")
        XCTAssertEqual(try XCTUnwrap(map([recipe(cook: "PT1H30M")]).backup.recipes.first).cookTime, "1h 30m")
    }

    func testTheStoredPictureBecomesTheRecipesOwn() throws {
        XCTAssertEqual(try XCTUnwrap(map([recipe(photoFile: "stored-1.jpg")]).backup.recipes.first).photo, "stored-1.jpg")
    }

    func testARepeatOfARecipeIdInTheFileIsCountedAndAddsItsCategoriesToTheFirsts() {
        let mapped = map([
            recipe(name: "First", uid: "U1", categories: ["Dessert"]),
            recipe(name: "Second", uid: "u1", categories: ["dessert", "Party"]),
        ], filing: true)
        XCTAssertEqual(mapped.backup.recipes.map(\.title), ["First"])
        XCTAssertEqual(mapped.repeated, 1)
        XCTAssertEqual(mapped.backup.lists.map(\.name), ["Dessert", "Party"])
        XCTAssertEqual(mapped.backup.memberships.count, 2)
    }

    func testWithTheSwitchOnTheFavouriteFlagFilesTheRecipeInFavoritesByFlagNeverByName() throws {
        let mapped = map([recipe(categories: ["Favorites"], favorite: true)], filing: true)
        let favorites = try XCTUnwrap(mapped.backup.lists.first(where: \.isFavorites))
        let plain = try XCTUnwrap(mapped.backup.lists.first { !$0.isFavorites })
        XCTAssertEqual(plain.name, "Favorites")
        XCTAssertTrue(mapped.backup.memberships.contains { $0.listId == favorites.id })
        XCTAssertTrue(mapped.backup.memberships.contains { $0.listId == plain.id })
    }

    func testWithTheSwitchOffNoListIsMade() {
        let mapped = map([recipe(categories: ["Dessert"], favorite: true)])
        XCTAssertTrue(mapped.backup.lists.isEmpty)
        XCTAssertTrue(mapped.backup.memberships.isEmpty)
    }
}
