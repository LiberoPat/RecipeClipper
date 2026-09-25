import XCTest
@testable import RecipeClipper

/// The merge rules (#26): first against the shared fixture, with the same existing state and the
/// same expected plan as Android's BackupMergerTest, then one rule at a time.
final class BackupMergerTests: XCTestCase {

    /// The phone the fixture is imported into. Mirrored exactly in the Android test.
    private let hereRecipes = [
        ExistingRecipe(id: 1, uid: "e-soup", sourceUrl: "https://example.com/soup", hasNotes: false, isListed: false),
        ExistingRecipe(id: 2, uid: "e-bread", sourceUrl: "https://example.com/bread", hasNotes: true, isListed: true),
        ExistingRecipe(id: 3, uid: "e-older", sourceUrl: "https://example.com/older", hasNotes: false, isListed: false),
    ]
    private let hereLists = [
        ExistingList(id: 1, uid: "l-fav", name: "Favorites", isFavorites: true),
        ExistingList(id: 2, uid: "l-lunch", name: "Lunch", isFavorites: false),
        ExistingList(id: 10, uid: "l-week", name: "Weeknight", isFavorites: false),
    ]
    private let herePantry = [
        ExistingPantryItem(uid: "p-here", name: "Butter", language: "en"),
        ExistingPantryItem(uid: "x-salt", name: "Salt", language: "en"),
    ]
    private let hereGroceries: Set<String> = ["g-here"]
    private let hereMealTypes = [
        ExistingMealType(id: 1, uid: "mt-breakfast", name: "Breakfast", builtInKey: "breakfast"),
        ExistingMealType(id: 4, uid: "mt-dinner", name: "Supper", builtInKey: "dinner"),
        ExistingMealType(id: 7, uid: "mt-brunch", name: "Brunch", builtInKey: nil),
    ]
    private let herePlan: Set<String> = ["m-here"]
    private let today: Int64 = 20720

    private func plan(
        _ backup: Backup,
        recipes: [ExistingRecipe]? = nil,
        lists: [ExistingList]? = nil,
        maxSortOrder: Int = 6,
        historyLimit: Int = 2,
        pantry: [ExistingPantryItem]? = nil,
        groceries: Set<String>? = nil,
        mealTypes: [ExistingMealType]? = nil,
        maxMealTypeSortOrder: Int = 4,
        planUids: Set<String>? = nil
    ) -> ImportPlan {
        var n = 0
        return BackupMerger.plan(
            backup,
            existingRecipes: recipes ?? hereRecipes,
            existingLists: lists ?? hereLists,
            maxSortOrder: maxSortOrder,
            historyLimit: historyLimit,
            newUid: { n += 1; return "gen-\(n)" },
            existingPantry: pantry ?? herePantry,
            existingGroceryUids: groceries ?? hereGroceries,
            existingMealTypes: mealTypes ?? hereMealTypes,
            maxMealTypeSortOrder: maxMealTypeSortOrder,
            existingPlanUids: planUids ?? herePlan,
            today: today
        )
    }

    private func recipe(_ id: String, _ url: String, viewed: Int64 = 0, notes: String? = nil) -> BackupRecipe {
        BackupRecipe(
            id: id, sourceUrl: url, sourceType: "BLOG", title: id, imageUrl: nil, ingredients: [], instructions: [],
            prepTime: nil, cookTime: nil, totalTime: nil, servings: nil, lastViewedAt: viewed,
            checkedIngredients: [], notes: notes
        )
    }

    private func list(_ id: String, _ name: String, favorites: Bool = false) -> BackupList {
        BackupList(id: id, name: name, isFavorites: favorites, isBuiltIn: false, sortOrder: 0, createdAt: 0)
    }

    func testTheSharedFixtureMergesIntoTheExpectedPlan() throws {
        let plan = plan(try decodeOrFail(backupFixture("backup-v1")))

        XCTAssertEqual(plan.newRecipes.map(\.id), ["r-pie", "gen-1"])
        let pie = plan.newRecipes[0]
        XCTAssertEqual(pie.sourceUrl, "https://example.com/pie")
        XCTAssertEqual(pie.title, "Apple Pie")
        XCTAssertEqual(pie.checkedIngredients, [0])
        XCTAssertEqual(pie.notes, "Use cold butter.")
        let salad = plan.newRecipes[1]
        XCTAssertEqual(salad.sourceUrl, "https://example.com/salad")
        XCTAssertEqual(salad.sourceType, "BLOG")
        XCTAssertNil(salad.notes)
        XCTAssertEqual(salad.lastViewedAt, 1789000000900)

        XCTAssertEqual(plan.noteUpdates, [NoteUpdate(recipeId: 1, notes: "Less salt.\nDouble the onion.")])

        XCTAssertEqual(plan.newLists, [
            NewList(uid: "f-fakefav", name: "Favorites", isBuiltIn: false, sortOrder: 7, createdAt: 1700000000200),
            NewList(uid: "f-party", name: "Party food", isBuiltIn: false, sortOrder: 8, createdAt: 1700000000300),
        ])

        XCTAssertEqual(plan.memberships, [
            PlannedMembership(recipe: .existing(1), list: .existing(1), addedAt: 10),
            PlannedMembership(recipe: .new("r-pie"), list: .new("f-party"), addedAt: 11),
            PlannedMembership(recipe: .new("r-pie"), list: .existing(1), addedAt: 13),
            PlannedMembership(recipe: .existing(2), list: .existing(2), addedAt: 14),
            PlannedMembership(recipe: .existing(2), list: .existing(10), addedAt: 15),
            PlannedMembership(recipe: .new("r-pie"), list: .new("f-fakefav"), addedAt: 16),
        ])

        // Pantry: p-here's uid and "salt" are here, "FLOUR" repeats "Flour": only flour comes in, trimmed.
        XCTAssertEqual(plan.newPantry.map(\.id), ["p-flour"])
        XCTAssertEqual(plan.newPantry.first?.name, "Flour")
        XCTAssertEqual(plan.newPantry.first?.quantity, "half a bag")

        // Groceries: by uid only; each keeps a recipe that is here after the import.
        XCTAssertEqual(plan.newGroceries.map(\.item.id), ["g-tomatoes", "g-apples", "g-beef", "g-milk"])
        XCTAssertEqual(plan.newGroceries.map(\.recipe), [.existing(1), .new("r-pie"), nil, nil])

        // Meal types: the seeded Dinner by key (renamed "Supper" here), " brunch " by name; a
        // user type called "Dinner" never joins the seeded one, so it and "Tea" are created.
        XCTAssertEqual(plan.newMealTypes, [
            NewMealType(uid: "t-fakedinner", name: "Dinner", sortOrder: 5, updatedAt: 0),
            NewMealType(uid: "t-tea", name: "Tea", sortOrder: 6, updatedAt: 1789000000000),
        ])

        // The plan: the soup keeps the soup here, the note goes to Dinner, the pie keeps the pie
        // being written. The stew was skipped for history and r-missing was never in the file:
        // both dropped. m-here is here already.
        XCTAssertEqual(plan.newPlanEntries.map(\.entry.id), ["m-soup", "m-note", "m-pie"])
        XCTAssertEqual(plan.newPlanEntries.map(\.mealType), [.existing(4), .existing(4), .new("t-fakedinner")])
        XCTAssertEqual(plan.newPlanEntries.map(\.recipe), [.existing(1), nil, .new("r-pie")])
        XCTAssertEqual(plan.newPlanEntries.first?.entry.servings, 6)
        XCTAssertEqual(plan.newPlanEntries[1].entry.note, "Leftovers")

        XCTAssertEqual(plan.summary, ImportSummary(
            recipesAdded: 2, listsAdded: 2, recipesAlreadyHere: 2, recipesSkipped: 1, pantryAdded: 1, groceriesAdded: 4,
            mealsAdded: 3, mealTypesAdded: 2
        ))
    }

    func testAPantryNameInAnotherLanguageIsADifferentItem() {
        let backup = Backup(
            exportedAt: 0, recipes: [], lists: [], memberships: [],
            pantry: [BackupPantryItem(id: "p", name: "Butter", quantity: nil, language: "de", aisle: "dairy", inStock: true,
                                      alwaysHave: false, purchasedDay: nil, expiresDay: nil, updatedAt: 0)]
        )
        XCTAssertEqual(plan(backup, recipes: []).newPantry.map(\.id), ["p"])
    }

    func testImportingTheSameFileTwiceAddsNothingTheSecondTime() throws {
        let backup = try decodeOrFail(backupFixture("backup-v1"))
        let first = plan(backup, historyLimit: 50)
        var nextId: Int64 = 100
        var recipes = hereRecipes.map { r -> ExistingRecipe in
            var r = r
            if r.id == 1 { r.isListed = true; r.hasNotes = true }
            return r
        }
        for r in first.newRecipes {
            recipes.append(ExistingRecipe(id: nextId, uid: r.id, sourceUrl: r.sourceUrl, hasNotes: r.notes != nil, isListed: true))
            nextId += 1
        }
        var lists = hereLists
        for l in first.newLists {
            lists.append(ExistingList(id: nextId, uid: l.uid, name: l.name, isFavorites: false))
            nextId += 1
        }

        let pantry = herePantry + first.newPantry.map { ExistingPantryItem(uid: $0.id, name: $0.name, language: $0.language) }
        let groceries = hereGroceries.union(first.newGroceries.map(\.item.id))

        var mealTypes = hereMealTypes
        for t in first.newMealTypes {
            mealTypes.append(ExistingMealType(id: nextId, uid: t.uid, name: t.name, builtInKey: nil))
            nextId += 1
        }
        let planUids = herePlan.union(first.newPlanEntries.map(\.entry.id))

        let second = plan(
            backup, recipes: recipes, lists: lists, maxSortOrder: 8, historyLimit: 50, pantry: pantry, groceries: groceries,
            mealTypes: mealTypes, maxMealTypeSortOrder: 6, planUids: planUids
        )
        XCTAssertTrue(second.newPantry.isEmpty)
        XCTAssertTrue(second.newGroceries.isEmpty)
        XCTAssertTrue(second.newMealTypes.isEmpty)
        XCTAssertTrue(second.newPlanEntries.isEmpty)

        XCTAssertTrue(second.newRecipes.isEmpty)
        XCTAssertTrue(second.newLists.isEmpty)
        XCTAssertTrue(second.noteUpdates.isEmpty)
        XCTAssertEqual(second.summary.recipesAdded, 0)
        XCTAssertEqual(second.summary.listsAdded, 0)
    }

    func testARecipePlannedForTodayOrLaterComesInLikeAListedOne() {
        func entry(_ id: String, _ day: Int64, _ recipeId: String) -> BackupPlanEntry {
            BackupPlanEntry(id: id, day: day, mealTypeId: nil, recipeId: recipeId, servings: nil, note: nil, sortOrder: 0, updatedAt: 0)
        }
        let backup = Backup(
            exportedAt: 0,
            recipes: [recipe("r-today", "https://example.com/r-today", viewed: 5), recipe("r-past", "https://example.com/r-past", viewed: 5)],
            lists: [], memberships: [],
            mealPlan: [entry("m-today", today, "r-today"), entry("m-past", today - 1, "r-past")]
        )
        // No free place in history: the recipe planned for today still comes in; the past one doesn't.
        let plan = plan(backup, recipes: [], historyLimit: 0)
        XCTAssertEqual(plan.newRecipes.map(\.id), ["r-today"])
        XCTAssertEqual(plan.summary.recipesSkipped, 1)
        XCTAssertEqual(plan.newPlanEntries.map(\.entry.id), ["m-today"])
        XCTAssertEqual(plan.newPlanEntries.map(\.mealType), [.existing(4)])
        XCTAssertEqual(plan.newPlanEntries.map(\.recipe), [.new("r-today")])
    }

    func testFavoritesMapsByTheFlagWhateverEitherListIsCalled() {
        let backup = Backup(exportedAt: 0, recipes: [], lists: [list("x", "Mes favoris", favorites: true)], memberships: [])
        let plan = plan(backup, recipes: [], lists: [ExistingList(id: 5, uid: "l-fav", name: "Keepers", isFavorites: true)])
        XCTAssertTrue(plan.newLists.isEmpty)
    }

    func testNoListEverJoinsFavoritesByNameOrUid() {
        let backup = Backup(exportedAt: 0, recipes: [], lists: [list("l-fav", "favorites")], memberships: [])
        let plan = plan(backup, recipes: [])
        XCTAssertEqual(plan.newLists.count, 1)
        XCTAssertEqual(plan.newLists.first?.uid, "gen-1")
        XCTAssertEqual(plan.newLists.first?.name, "favorites")
    }

    func testListNamesMatchTrimmedAndCaseInsensitively() {
        let backup = Backup(exportedAt: 0, recipes: [], lists: [list("new", "  WEEKNIGHT ")], memberships: [])
        XCTAssertTrue(plan(backup, recipes: []).newLists.isEmpty)
    }

    func testUnlistedRecipesOnlyFillFreePlacesAndNeverPushOneOut() {
        let here = (1...48).map { ExistingRecipe(id: Int64($0), uid: "u\($0)", sourceUrl: "https://h.example/\($0)", hasNotes: false, isListed: false) }
        let incoming = (1...5).map { recipe("f\($0)", "https://f.example/\($0)", viewed: Int64($0) * 10) }
        let plan = plan(Backup(exportedAt: 0, recipes: incoming, lists: [], memberships: []), recipes: here, lists: [], historyLimit: 50)
        XCTAssertEqual(plan.newRecipes.map(\.id).sorted(), ["f4", "f5"])
        XCTAssertEqual(plan.summary.recipesSkipped, 3)
    }

    func testRecipesInAListAlwaysComeInEvenWhenHistoryIsFull() {
        let here = (1...50).map { ExistingRecipe(id: Int64($0), uid: "u\($0)", sourceUrl: "https://h.example/\($0)", hasNotes: false, isListed: false) }
        let backup = Backup(
            exportedAt: 0, recipes: [recipe("f1", "https://f.example/1")], lists: [list("L", "Dinner")],
            memberships: [BackupMembership(recipeId: "f1", listId: "L", addedAt: 5)]
        )
        let plan = plan(backup, recipes: here, lists: [], historyLimit: 50)
        XCTAssertEqual(plan.newRecipes.map(\.id), ["f1"])
        XCTAssertEqual(plan.summary.recipesSkipped, 0)
    }

    func testARecipeHereThatTheFilePutsInAListFreesItsPlaceInHistory() {
        let here = (1...50).map { ExistingRecipe(id: Int64($0), uid: "u\($0)", sourceUrl: "https://h.example/\($0)", hasNotes: false, isListed: false) }
        let backup = Backup(
            exportedAt: 0,
            recipes: [recipe("a", "https://h.example/1"), recipe("b", "https://f.example/new")],
            lists: [list("L", "Dinner")],
            memberships: [BackupMembership(recipeId: "a", listId: "L", addedAt: 5)]
        )
        let plan = plan(backup, recipes: here, lists: [], historyLimit: 50)
        XCTAssertEqual(plan.newRecipes.map(\.id), ["b"])
    }

    func testAnExistingNoteIsNeverReplaced() {
        let backup = Backup(exportedAt: 0, recipes: [recipe("a", "https://example.com/bread", notes: "theirs")], lists: [], memberships: [])
        let plan = plan(backup)
        XCTAssertTrue(plan.noteUpdates.isEmpty)
        XCTAssertEqual(plan.summary.recipesAlreadyHere, 1)
    }
}
