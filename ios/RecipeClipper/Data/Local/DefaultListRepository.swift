import Combine
import Foundation

/// The real, SQLite-backed ListRepository (Android's DefaultListRepository). Database failures
/// are logged; the contract's methods don't throw.
final class DefaultListRepository: ListRepository {
    private let db: AppDatabase
    private let clock: Clock
    /// The `reorderLists` flag (#381). Off, lists are in the order from before it: seeded first.
    private let reorderOn: () -> Bool

    init(db: AppDatabase, clock: Clock, reorderOn: @escaping () -> Bool = { true }) {
        self.db = db
        self.clock = clock
        self.reorderOn = reorderOn
    }

    func observeLists() -> AnyPublisher<[RecipeList], Never> {
        observeLists(recipeId: ListDao.noRecipe)
    }

    func observeListsFor(recipeId: Int64) -> AnyPublisher<[RecipeList], Never> {
        observeLists(recipeId: recipeId)
    }

    private func observeLists(recipeId: Int64) -> AnyPublisher<[RecipeList], Never> {
        db.observe { [reorderOn] conn in
            let lists = try ListDao(db: conn).lists(recipeId: recipeId).map { $0.toDomain() }
            // The stored order (#381), or with the flag off the order from before it: seeded
            // lists first, each group in its stored order. The two agree until someone moves a list.
            return reorderOn() ? lists : lists.filter(\.isBuiltIn) + lists.filter { !$0.isBuiltIn }
        }
    }

    func observeRecipesIn(listId: Int64) -> AnyPublisher<[RecipeSummary], Never> {
        db.observe { conn in try ListDao(db: conn).recipesIn(listId: listId).map { $0.toDomain() } }
    }

    func observeCoverPhotos() -> AnyPublisher<[Int64: [String]], Never> {
        db.observe { conn in
            // The rows come ordered by list, newest first: grouping keeps that order.
            try ListDao(db: conn).coverPhotos().reduce(into: [Int64: [String]]()) { covers, row in
                covers[row.listId, default: []].append(row.imageUrl)
            }
        }
    }

    func observeSavedRecipes() -> AnyPublisher<[SavedRecipe], Never> {
        db.observe { conn in try ListDao(db: conn).savedRecipes() }
    }

    func setMembership(recipeId: Int64, listId: Int64, inList: Bool) async {
        let now = clock.now()
        await perform("setMembership") { dao in
            if inList {
                try dao.addToList(ListMembership(recipeId: recipeId, listId: listId, addedAt: now))
            } else {
                try dao.removeFromList(recipeId: recipeId, listId: listId)
            }
        }
    }

    func setMembershipAll(recipeIds: [Int64], listId: Int64, inList: Bool) async {
        let now = clock.now()
        await perform("setMembershipAll") { dao in
            if inList {
                try dao.addAllToList(recipeIds: recipeIds, listId: listId, addedAt: now)
            } else {
                try dao.removeAllFromList(recipeIds: recipeIds, listId: listId)
            }
        }
    }

    func observeMembershipCounts(recipeIds: [Int64]) -> AnyPublisher<[Int64: Int], Never> {
        db.observe { conn in try ListDao(db: conn).membershipCounts(recipeIds: recipeIds) }
    }

    @discardableResult
    func createList(name: String, addRecipeIds: [Int64]) async -> Int64 {
        let now = clock.now()
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            return try await db.write { conn in
                let dao = ListDao(db: conn)
                let id = try dao.create(name: trimmed, recipeId: ListDao.noRecipe, now: now)
                try dao.addAllToList(recipeIds: addRecipeIds, listId: id, addedAt: now)
                return id
            }
        } catch {
            dataLog.error("createList failed: \(String(describing: error), privacy: .public)")
            return 0
        }
    }

    @discardableResult
    func createList(name: String, addRecipeId: Int64?) async -> Int64 {
        let now = clock.now()
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            return try await db.write { conn in
                try ListDao(db: conn).create(name: trimmed, recipeId: addRecipeId ?? ListDao.noRecipe, now: now)
            }
        } catch {
            dataLog.error("createList failed: \(String(describing: error), privacy: .public)")
            return 0
        }
    }

    func reorderLists(_ orderedIds: [Int64]) async {
        await perform("reorderLists") { dao in try dao.reorder(orderedIds) }
    }

    func rename(listId: Int64, name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        await perform("rename") { dao in try dao.rename(id: listId, name: trimmed) }
    }

    func deleteList(listId: Int64) async {
        await perform("deleteList") { dao in try dao.delete(id: listId) }
    }

    private func perform(_ what: String, _ body: @escaping (ListDao) throws -> Void) async {
        do {
            try await db.write { conn in try body(ListDao(db: conn)) }
        } catch {
            dataLog.error("\(what, privacy: .public) failed: \(String(describing: error), privacy: .public)")
        }
    }
}
