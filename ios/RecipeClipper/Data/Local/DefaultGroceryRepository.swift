import Combine
import Foundation

/// The real, SQLite-backed GroceryRepository (Android's DefaultGroceryRepository). Database
/// failures are logged; writes become no-ops, a delete returns nil and a read is empty.
final class DefaultGroceryRepository: GroceryRepository {
    private let db: AppDatabase
    private let clock: Clock
    private let decisions: DecisionRepository?

    init(db: AppDatabase, clock: Clock, decisions: DecisionRepository? = nil) {
        self.db = db
        self.clock = clock
        self.decisions = decisions
    }

    func observeItems() -> AnyPublisher<[GroceryItem], Never> {
        db.observe { conn in try GroceryDao(db: conn).items().map(\.domain) }
    }

    func add(_ lines: [NewGroceryLine]) async {
        let now = clock.now()
        let decided = await decisions?.current() ?? .none
        let items: [GroceryItemRecord] = lines.compactMap { line in
            let text = line.text.kTrimmed
            guard !text.isEmpty else { return nil }
            let words = LanguageWords.forTag(line.language)
            // The keyword table first; for what it puts in Other, an aisle the model decided (#104).
            var aisle = Aisles.of(text, words: words)
            if aisle == .other, let words, let name = IngredientName.of(text, words: words) {
                aisle = decided.aisle(name, language: words.language) ?? .other
            }
            return GroceryItemRecord(
                text: text, language: line.language,
                aisle: aisle.key,
                sortOrder: 0, // the DAO puts them last, in order
                recipeId: line.recipeId, plannedDay: line.plannedDay, updatedAt: now
            )
        }
        if items.isEmpty { return }
        await perform("addGroceries") { dao in try dao.add(items) }
    }

    func setChecked(_ ids: [Int64], checked: Bool) async {
        let now = clock.now()
        await perform("setGroceryChecked") { dao in try dao.setChecked(ids, checked: checked, now: now) }
    }

    func setAisle(_ ids: [Int64], aisle: Aisle) async {
        let now = clock.now()
        await perform("setGroceryAisle") { dao in try dao.setAisle(ids, aisle: aisle.key, now: now) }
    }

    func fileFromOther(_ ids: [Int64], aisle: Aisle) async {
        let now = clock.now()
        await perform("fileGroceryAisle") { dao in try dao.fileFromOther(ids, aisle: aisle.key, now: now) }
    }

    func delete(_ ids: [Int64]) async -> DeletedGroceries? {
        await remove("deleteGroceries") { dao in try dao.items(ids: ids) }
    }

    func clearChecked() async -> DeletedGroceries? {
        await remove("clearCheckedGroceries") { dao in try dao.checkedItems() }
    }

    private func remove(_ what: String, _ which: @escaping (GroceryDao) throws -> [GroceryItemRecord]) async -> DeletedGroceries? {
        do {
            return try await db.write { conn -> DeletedGroceries? in
                let dao = GroceryDao(db: conn)
                let items = try which(dao)
                if items.isEmpty { return nil }
                try dao.delete(items.map(\.id))
                return DeletedGroceries(items: items)
            }
        } catch {
            dataLog.error("\(what, privacy: .public) failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func restore(_ deleted: DeletedGroceries) async {
        await perform("restoreGroceries") { dao in try dao.restore(deleted.items) }
    }

    func plannedIngredients(start: Int64, end: Int64) async -> [PlannedIngredients] {
        do {
            return try await db.read { conn in try GroceryDao(db: conn).plannedIngredients(start: start, end: end) }
        } catch {
            dataLog.error("plannedIngredients failed: \(String(describing: error), privacy: .public)")
            return []
        }
    }

    private func perform(_ what: String, _ body: @escaping (GroceryDao) throws -> Void) async {
        do {
            try await db.write { conn in try body(GroceryDao(db: conn)) }
        } catch {
            dataLog.error("\(what, privacy: .public) failed: \(String(describing: error), privacy: .public)")
        }
    }
}

extension GroceryItemRecord {
    var domain: GroceryItem {
        GroceryItem(
            id: id, text: text, language: language, aisle: Aisle.fromKey(aisle), checked: checked,
            sortOrder: sortOrder, recipeId: recipeId, plannedDay: plannedDay
        )
    }
}
