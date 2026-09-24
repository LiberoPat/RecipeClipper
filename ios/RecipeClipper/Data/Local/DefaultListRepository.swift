import Combine
import Foundation

/// The real, SQLite-backed ListRepository (Android's DefaultListRepository). Database failures
/// are logged; the contract's methods don't throw.
final class DefaultListRepository: ListRepository {
    private let db: AppDatabase
    private let clock: Clock

    init(db: AppDatabase, clock: Clock) {
        self.db = db
        self.clock = clock
    }

    func observeLists() -> AnyPublisher<[RecipeList], Never> {
        observeLists(recipeId: ListDao.noRecipe)
    }

    func observeListsFor(recipeId: Int64) -> AnyPublisher<[RecipeList], Never> {
        observeLists(recipeId: recipeId)
    }

    private func observeLists(recipeId: Int64) -> AnyPublisher<[RecipeList], Never> {
        db.observe { conn in try ListDao(db: conn).lists(recipeId: recipeId).map { $0.toDomain() } }
    }

    func observeRecipesIn(listId: Int64) -> AnyPublisher<[RecipeSummary], Never> {
        db.observe { conn in try ListDao(db: conn).recipesIn(listId: listId).map { $0.toDomain() } }
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
