import Combine
import Foundation

/// The real, SQLite-backed MealPlanRepository (Android's DefaultMealPlanRepository). Database
/// failures are logged; writes become no-ops and a delete returns nil.
final class DefaultMealPlanRepository: MealPlanRepository {
    private let db: AppDatabase
    private let clock: Clock

    init(db: AppDatabase, clock: Clock) {
        self.db = db
        self.clock = clock
    }

    func observeMealTypes() -> AnyPublisher<[MealType], Never> {
        db.observe { conn in
            try MealPlanDao(db: conn).mealTypes().map {
                MealType(id: $0.id, name: $0.name, builtInKey: $0.builtInKey, sortOrder: $0.sortOrder)
            }
        }
    }

    func observeDays(start: Int64, end: Int64) -> AnyPublisher<[PlannedMeal], Never> {
        db.observe { conn in try MealPlanDao(db: conn).days(start: start, end: end) }
    }

    func addRecipe(recipeId: Int64, day: Int64, mealTypeId: Int64, servings: Int?) async {
        let entry = MealPlanEntryRecord(
            day: day, mealTypeId: mealTypeId, recipeId: recipeId, servings: servings, note: nil,
            sortOrder: 0, updatedAt: clock.now()
        )
        await perform("addRecipe") { dao in try dao.add(entry) }
    }

    func addNote(_ note: String, day: Int64, mealTypeId: Int64) async {
        let text = note.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let entry = MealPlanEntryRecord(
            day: day, mealTypeId: mealTypeId, recipeId: nil, servings: nil, note: text,
            sortOrder: 0, updatedAt: clock.now()
        )
        await perform("addNote") { dao in try dao.add(entry) }
    }

    func move(entryId: Int64, day: Int64, mealTypeId: Int64) async {
        let now = clock.now()
        await perform("move") { dao in try dao.move(entryId, day: day, mealTypeId: mealTypeId, now: now) }
    }

    func delete(entryId: Int64) async -> DeletedMeal? {
        do {
            return try await db.write { conn -> DeletedMeal? in
                let dao = MealPlanDao(db: conn)
                guard let entry = try dao.entry(entryId) else { return nil }
                try dao.deleteEntry(entryId)
                return DeletedMeal(entry: entry)
            }
        } catch {
            dataLog.error("deleteMeal failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func restore(_ deleted: DeletedMeal) async {
        await perform("restoreMeal") { dao in try dao.restore(deleted.entry) }
    }

    func addMealType(name: String) async {
        let text = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let now = clock.now()
        await perform("addMealType") { dao in try dao.addType(name: text, now: now) }
    }

    func renameMealType(id: Int64, name: String) async {
        let text = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let now = clock.now()
        await perform("renameMealType") { dao in try dao.renameType(id, name: text, now: now) }
    }

    func reorderMealTypes(_ orderedIds: [Int64]) async {
        let now = clock.now()
        await perform("reorderMealTypes") { dao in try dao.reorderTypes(orderedIds, now: now) }
    }

    func deleteMealType(id: Int64) async {
        let now = clock.now()
        await perform("deleteMealType") { dao in try dao.deleteType(id, now: now) }
    }

    private func perform(_ what: String, _ body: @escaping (MealPlanDao) throws -> Void) async {
        do {
            try await db.write { conn in try body(MealPlanDao(db: conn)) }
        } catch {
            dataLog.error("\(what, privacy: .public) failed: \(String(describing: error), privacy: .public)")
        }
    }
}

// MARK: - Reusable weekly menus (#52)

extension DefaultMealPlanRepository {
    func observeMenus() -> AnyPublisher<[WeekMenu], Never> {
        db.observe { conn in try MenuDao(db: conn).menus() }
    }

    func saveWeekAsMenu(name: String, weekStart: Int64) async -> Bool {
        let text = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return false }
        let now = clock.now()
        return await menuWrite("saveWeekAsMenu", false) { dao in
            try dao.saveWeek(name: text, weekStart: weekStart, now: now) != nil
        }
    }

    func applyMenu(id: Int64, weekStart: Int64) async -> Int {
        let now = clock.now()
        return await menuWrite("applyMenu", 0) { dao in try dao.apply(menuId: id, weekStart: weekStart, now: now) }
    }

    func renameMenu(id: Int64, name: String) async {
        let text = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let now = clock.now()
        await menuWrite("renameMenu", ()) { dao in try dao.rename(id, name: text, now: now) }
    }

    func deleteMenu(id: Int64) async {
        await menuWrite("deleteMenu", ()) { dao in try dao.delete(id) }
    }

    private func menuWrite<T>(_ what: String, _ fallback: T, _ body: @escaping (MenuDao) throws -> T) async -> T {
        do {
            return try await db.write { conn in try body(MenuDao(db: conn)) }
        } catch {
            dataLog.error("\(what, privacy: .public) failed: \(String(describing: error), privacy: .public)")
            return fallback
        }
    }
}
