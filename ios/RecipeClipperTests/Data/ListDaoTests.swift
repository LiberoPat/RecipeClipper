import XCTest
@testable import RecipeClipper

/// Port of Android's ListDaoTest: built-in ordering, derived counts, the cascade and the
/// `isFavorites = 0` delete guard, all of which live in SQL.
final class ListDaoTests: XCTestCase {
    private var db: AppDatabase!

    override func setUpWithError() throws {
        db = try AppDatabase(path: nil)
    }

    override func tearDown() {
        db = nil
    }

    private func addRecipe(_ url: String) async throws -> Int64 {
        try await db.upsert(RecipeRecord(
            sourceUrl: url, title: "Recipe \(url)", imageUrl: nil,
            ingredients: ["1 cup flour"], instructions: ["Mix."],
            prepTime: nil, cookTime: nil, totalTime: nil, servings: "4",
            sourceType: "BLOG", lastViewedAt: 1
        ), limit: 50)
    }

    private func add(_ recipeId: Int64, to listId: Int64, at addedAt: Int64) async throws {
        try await db.write {
            try ListDao(db: $0).addToList(ListMembership(recipeId: recipeId, listId: listId, addedAt: addedAt))
        }
    }

    private func create(_ name: String, recipeId: Int64 = ListDao.noRecipe, now: Int64 = 10) async throws -> Int64 {
        try await db.write { try ListDao(db: $0).create(name: name, recipeId: recipeId, now: now) }
    }

    private func deleteList(_ id: Int64) async throws {
        try await db.write { try ListDao(db: $0).delete(id: id) }
    }

    private func recipesIn(_ listId: Int64) async throws -> [RecipeSummaryRecord] {
        try await db.read { try ListDao(db: $0).recipesIn(listId: listId) }
    }

    // MARK: - Seeded built-ins

    func testFavoritesSortsFirstAmongTheBuiltIns() async throws {
        let first = try await db.allLists().first
        XCTAssertEqual(first?.isFavorites, true)
    }

    func testBuiltInsSortBeforeUserListsWhateverTheirNames() async throws {
        _ = try await create("Aaa")
        let names = try await db.allLists().map(\.name)
        XCTAssertEqual(names, ["Favorites", "Lunch", "Dinner", "Desserts", "Breakfast", "Snacks", "Aaa"])
    }

    func testNewListsKeepTheOrderTheyWereCreatedIn() async throws {
        _ = try await create("First", now: 10)
        _ = try await create("Second", now: 11)
        _ = try await create("Third", now: 12)
        let names = try await db.allLists().filter { !$0.isBuiltIn }.map(\.name)
        XCTAssertEqual(names, ["First", "Second", "Third"])
    }

    func testNewListSortOrderFollowsTheHighestExisting() async throws {
        let id = try await create("Mine")
        let order = try await db.read { try $0.queryOne("SELECT sortOrder FROM lists WHERE id = ?", id) { $0.int(0) } }
        XCTAssertEqual(order, 6, "COALESCE(MAX(sortOrder), -1) + 1 after seeded 0...5")
    }

    // MARK: - Counts and containsRecipe

    func testCountsAreDerivedFromMembership() async throws {
        let favorites = try await db.favoritesId()
        let a = try await addRecipe("https://example.com/a")
        let b = try await addRecipe("https://example.com/b")
        try await add(a, to: favorites, at: 1)
        try await add(b, to: favorites, at: 2)

        let lists = try await db.allLists()
        XCTAssertEqual(lists.first { $0.id == favorites }?.recipeCount, 2)
        XCTAssertEqual(lists.first { $0.name == "Lunch" }?.recipeCount, 0)
    }

    func testContainsRecipeIsTrueOnlyForTheRecipeAskedAbout() async throws {
        let favorites = try await db.favoritesId()
        let mine = try await addRecipe("https://example.com/mine")
        let other = try await addRecipe("https://example.com/other")
        try await add(other, to: favorites, at: 1)

        let forMine = try await db.allLists(for: mine).first { $0.id == favorites }
        XCTAssertEqual(forMine?.containsRecipe, false)
        XCTAssertEqual(forMine?.recipeCount, 1, "the other recipe still counts towards the size")

        try await add(mine, to: favorites, at: 2)
        let after = try await db.allLists(for: mine).first { $0.id == favorites }
        XCTAssertEqual(after?.containsRecipe, true)
    }

    func testNoRecipeSentinelNeverMatchesAnything() async throws {
        let favorites = try await db.favoritesId()
        let id = try await addRecipe("https://example.com/a")
        try await add(id, to: favorites, at: 1)

        let lists = try await db.allLists()
        XCTAssertFalse(lists.contains { $0.containsRecipe })
    }

    // MARK: - Adding and removing

    func testAddingTwiceIsANoOpAndKeepsTheOriginalAddedAt() async throws {
        let favorites = try await db.favoritesId()
        let id = try await addRecipe("https://example.com/a")
        try await add(id, to: favorites, at: 100)
        try await add(id, to: favorites, at: 999)

        let count = try await db.allLists().first { $0.id == favorites }?.recipeCount
        XCTAssertEqual(count, 1)
        // addedAt orders the list, so an IGNORE that silently became a REPLACE would reorder it.
        let refs = try await db.crossRefs(id)
        XCTAssertEqual(refs.map(\.addedAt), [100])
    }

    func testRemovingTakesOutOnlyThatOnePairing() async throws {
        let favorites = try await db.favoritesId()
        let lunch = try await db.listId(named: "Lunch")
        let id = try await addRecipe("https://example.com/a")
        try await add(id, to: favorites, at: 1)
        try await add(id, to: lunch, at: 2)

        try await db.write { try ListDao(db: $0).removeFromList(recipeId: id, listId: favorites) }

        let refs = try await db.crossRefs(id)
        XCTAssertEqual(refs.map(\.listId), [lunch])
    }

    /// CLAUDE.md's settled question: out of its last list is back to history, not deleted.
    func testRemovingFromTheLastListLeavesTheRecipeInHistory() async throws {
        let favorites = try await db.favoritesId()
        let id = try await addRecipe("https://example.com/a")
        try await add(id, to: favorites, at: 1)

        try await db.write { try ListDao(db: $0).removeFromList(recipeId: id, listId: favorites) }

        let row = try await db.get(id)
        XCTAssertNotNil(row)
        let summary = try await db.history().first { $0.id == id }
        XCTAssertEqual(summary?.isSaved, false, "no longer saved, but still there")
    }

    func testRecipesInAListComeBackNewestAddedFirst() async throws {
        let favorites = try await db.favoritesId()
        let a = try await addRecipe("https://example.com/a")
        let b = try await addRecipe("https://example.com/b")
        let c = try await addRecipe("https://example.com/c")
        try await add(a, to: favorites, at: 10)
        try await add(b, to: favorites, at: 30)
        try await add(c, to: favorites, at: 20)

        let ids = try await recipesIn(favorites).map(\.id)
        XCTAssertEqual(ids, [b, c, a])
    }

    func testRecipesInAListAreAlwaysMarkedSaved() async throws {
        let favorites = try await db.favoritesId()
        let id = try await addRecipe("https://example.com/a")
        try await add(id, to: favorites, at: 1)

        let rows = try await recipesIn(favorites)
        XCTAssertEqual(rows.map(\.isSaved), [true])
    }

    func testAnEmptyListReturnsNoRecipesRatherThanFailing() async throws {
        let rows = try await recipesIn(try await db.favoritesId())
        XCTAssertTrue(rows.isEmpty)
    }

    // MARK: - Creating

    func testCreatingWithARecipePutsThatRecipeStraightIn() async throws {
        let id = try await addRecipe("https://example.com/a")
        let listId = try await create("Weeknights", recipeId: id, now: 50)

        let created = try await db.allLists().first { $0.id == listId }
        XCTAssertEqual(created?.recipeCount, 1)
        XCTAssertEqual(created?.isBuiltIn, false)
        XCTAssertEqual(created?.isFavorites, false)
        let refs = try await db.crossRefs(id)
        XCTAssertEqual(refs, [ListMembership(recipeId: id, listId: listId, addedAt: 50)])
    }

    func testCreatingWithoutARecipeLeavesTheListEmpty() async throws {
        let listId = try await create("Weeknights", now: 50)
        let count = try await db.allLists().first { $0.id == listId }?.recipeCount
        XCTAssertEqual(count, 0)
    }

    func testCreatingWithAMissingRecipeCreatesNothing() async throws {
        do {
            _ = try await create("Orphan", recipeId: 424242)
            XCTFail("the membership insert must fail the foreign key")
        } catch {}
        let names = try await db.allLists().map(\.name)
        XCTAssertFalse(names.contains("Orphan"), "one transaction: no list without its recipe")
    }

    // MARK: - Renaming

    func testRenamingAUserListWorks() async throws {
        let listId = try await create("Weeknights")
        try await db.write { try ListDao(db: $0).rename(id: listId, name: "Midweek") }
        let name = try await db.allLists().first { $0.id == listId }?.name
        XCTAssertEqual(name, "Midweek")
    }

    func testRenamingABuiltInWorksAndItStaysBuiltIn() async throws {
        let favorites = try await db.favoritesId()
        try await db.write { try ListDao(db: $0).rename(id: favorites, name: "Best of") }

        let renamed = try await db.allLists().first { $0.id == favorites }
        XCTAssertEqual(renamed?.name, "Best of")
        // isFavorites is a column, never a name match — this is what that protects.
        XCTAssertEqual(renamed?.isFavorites, true)
        XCTAssertEqual(renamed?.isBuiltIn, true)
        let stillFavorites = try await db.favoritesId()
        XCTAssertEqual(stillFavorites, favorites)
    }

    // MARK: - Deleting

    func testDeletingAUserListRemovesItAndItsMembership() async throws {
        let id = try await addRecipe("https://example.com/a")
        let listId = try await create("Weeknights", recipeId: id, now: 50)

        try await deleteList(listId)

        let lists = try await db.allLists()
        XCTAssertFalse(lists.contains { $0.id == listId })
        let refs = try await db.crossRefs(id)
        XCTAssertTrue(refs.isEmpty, "cascaded")
    }

    func testDeletingAListNeverDeletesItsRecipes() async throws {
        let id = try await addRecipe("https://example.com/a")
        let listId = try await create("Weeknights", recipeId: id, now: 50)

        try await deleteList(listId)

        let row = try await db.get(id)
        XCTAssertNotNil(row)
    }

    /// Favorites is the one list that cannot go: "saved" is built around it.
    func testDeletingFavoritesIsRefused() async throws {
        let favorites = try await db.favoritesId()
        let id = try await addRecipe("https://example.com/a")
        try await add(id, to: favorites, at: 1)

        try await deleteList(favorites)

        let fav = try await db.allLists().first { $0.id == favorites }
        XCTAssertNotNil(fav)
        XCTAssertEqual(fav?.recipeCount, 1)
    }

    /// What would break if the guard ever went back to `isBuiltIn = 0`.
    func testDeletingASeededListThatIsNotFavoritesWorks() async throws {
        let lunch = try await db.allLists().first { $0.name == "Lunch" }!
        XCTAssertTrue(lunch.isBuiltIn)
        let id = try await addRecipe("https://example.com/a")
        try await add(id, to: lunch.id, at: 1)

        try await deleteList(lunch.id)

        let lists = try await db.allLists()
        XCTAssertFalse(lists.contains { $0.name == "Lunch" })
        let refs = try await db.crossRefs(id)
        XCTAssertTrue(refs.isEmpty, "cascaded")
        let row = try await db.get(id)
        XCTAssertNotNil(row, "the recipe itself survives")
    }

    func testEverySeededListExceptFavoritesCanBeDeleted() async throws {
        for list in try await db.allLists() where !list.isFavorites {
            try await deleteList(list.id)
        }
        let left = try await db.allLists()
        XCTAssertEqual(left.count, 1)
        XCTAssertEqual(left.first?.isFavorites, true)
    }

    func testDeletingARecipeCascadesOutOfItsLists() async throws {
        let favorites = try await db.favoritesId()
        let id = try await addRecipe("https://example.com/a")
        try await add(id, to: favorites, at: 1)

        try await db.write { try RecipeDao(db: $0).delete(id) }

        let count = try await db.allLists().first { $0.id == favorites }?.recipeCount
        XCTAssertEqual(count, 0)
        let rows = try await recipesIn(favorites)
        XCTAssertTrue(rows.isEmpty)
    }
}
