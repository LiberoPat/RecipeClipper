import Combine
import Foundation

/// The real, SQLite-backed PantryRepository (Android's DefaultPantryRepository). Database
/// failures are logged; writes become no-ops, reads are empty.
final class DefaultPantryRepository: PantryRepository {
    private let db: AppDatabase
    private let clock: Clock

    init(db: AppDatabase, clock: Clock) {
        self.db = db
        self.clock = clock
    }

    func observeItems() -> AnyPublisher<[PantryItem], Never> {
        db.observe { conn in try PantryDao(db: conn).items().map(\.domain) }
    }

    func items() async -> [PantryItem] {
        do {
            return try await db.read { conn in try PantryDao(db: conn).items().map(\.domain) }
        } catch {
            dataLog.error("pantryItems failed: \(String(describing: error), privacy: .public)")
            return []
        }
    }

    @discardableResult
    func add(_ item: NewPantryItem) async -> Int64? {
        let name = item.name.kTrimmed
        guard !name.isEmpty else { return nil }
        let quantity = item.quantity?.kTrimmed
        let record = PantryItemRecord(
            name: name,
            quantity: quantity?.isEmpty == false ? quantity : nil,
            language: item.language,
            aisle: (item.aisle ?? Aisles.of(name, words: LanguageWords.forTag(item.language))).key,
            inStock: true,
            alwaysHave: false,
            purchasedDay: item.purchasedDay,
            expiresDay: nil,
            updatedAt: clock.now()
        )
        do {
            return try await db.write { conn in try PantryDao(db: conn).insert(record) }
        } catch {
            dataLog.error("addPantryItem failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func setInStock(_ ids: [Int64], inStock: Bool) async {
        let now = clock.now()
        await perform("setPantryInStock") { dao in try dao.setInStock(ids, inStock: inStock, now: now) }
    }

    func restock(_ ids: [Int64], day: Int64) async {
        let now = clock.now()
        await perform("restockPantry") { dao in try dao.restock(ids, day: day, now: now) }
    }

    func edit(_ id: Int64, _ edit: PantryEdit) async {
        let name = edit.name.kTrimmed
        guard !name.isEmpty else { return }
        let quantity = edit.quantity?.kTrimmed
        let now = clock.now()
        await perform("editPantryItem") { dao in
            try dao.edit(
                id, name: name, quantity: quantity?.isEmpty == false ? quantity : nil,
                alwaysHave: edit.alwaysHave, expiresDay: edit.expiresDay, now: now
            )
        }
    }

    func snapshot(_ ids: [Int64]) async -> PantrySnapshot {
        do {
            return try await db.read { conn in
                let dao = PantryDao(db: conn)
                return PantrySnapshot(items: try ids.compactMap { try dao.item($0) })
            }
        } catch {
            dataLog.error("pantrySnapshot failed: \(String(describing: error), privacy: .public)")
            return PantrySnapshot(items: [])
        }
    }

    func delete(_ id: Int64) async -> PantrySnapshot? {
        do {
            return try await db.write { conn -> PantrySnapshot? in
                let dao = PantryDao(db: conn)
                guard let item = try dao.item(id) else { return nil }
                try dao.delete(id)
                return PantrySnapshot(items: [item])
            }
        } catch {
            dataLog.error("deletePantryItem failed: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    func restore(_ snapshot: PantrySnapshot) async {
        if snapshot.items.isEmpty { return }
        await perform("restorePantry") { dao in try dao.put(snapshot.items) }
    }

    private func perform(_ what: String, _ body: @escaping (PantryDao) throws -> Void) async {
        do {
            try await db.write { conn in try body(PantryDao(db: conn)) }
        } catch {
            dataLog.error("\(what, privacy: .public) failed: \(String(describing: error), privacy: .public)")
        }
    }
}
