import Combine
import Foundation
@testable import RecipeClipper

/// A fake that models the pantry rather than only recording calls (Android's
/// FakePantryRepository): `items` is real state, a new item gets its aisle from the aisle table
/// as the real repository does, and snapshots restore.
final class FakePantryRepository: PantryRepository {
    let items: CurrentValueSubject<[PantryItem], Never>
    private var nextId: Int64

    init(_ initial: [PantryItem] = []) {
        items = CurrentValueSubject(initial)
        nextId = (initial.map(\.id).max() ?? 0) + 1
    }

    func observeItems() -> AnyPublisher<[PantryItem], Never> { items.eraseToAnyPublisher() }

    func items() async -> [PantryItem] { items.value }

    func add(_ item: NewPantryItem) async {
        let name = item.name.kTrimmed
        guard !name.isEmpty else { return }
        let quantity = item.quantity?.kTrimmed
        items.value.append(PantryItem(
            id: nextId, name: name, quantity: quantity?.isEmpty == false ? quantity : nil, language: item.language,
            aisle: item.aisle ?? Aisles.of(name, words: LanguageWords.forTag(item.language)),
            inStock: true, alwaysHave: false, purchasedDay: item.purchasedDay, expiresDay: nil
        ))
        nextId += 1
    }

    func setInStock(_ ids: [Int64], inStock: Bool) async {
        items.value = items.value.map { var i = $0; if ids.contains(i.id) { i.inStock = inStock }; return i }
    }

    func restock(_ ids: [Int64], day: Int64) async {
        items.value = items.value.map { var i = $0; if ids.contains(i.id) { i.inStock = true; i.purchasedDay = day }; return i }
    }

    func edit(_ id: Int64, _ edit: PantryEdit) async {
        let name = edit.name.kTrimmed
        guard !name.isEmpty else { return }
        let quantity = edit.quantity?.kTrimmed
        items.value = items.value.map {
            var i = $0
            if i.id == id {
                i.name = name
                i.quantity = quantity?.isEmpty == false ? quantity : nil
                i.alwaysHave = edit.alwaysHave
                i.expiresDay = edit.expiresDay
            }
            return i
        }
    }

    func snapshot(_ ids: [Int64]) async -> PantrySnapshot {
        PantrySnapshot(items: items.value.filter { ids.contains($0.id) }.map(Self.record))
    }

    func delete(_ id: Int64) async -> PantrySnapshot? {
        guard let gone = items.value.first(where: { $0.id == id }) else { return nil }
        items.value.removeAll { $0.id == id }
        return PantrySnapshot(items: [Self.record(gone)])
    }

    func restore(_ snapshot: PantrySnapshot) async {
        let back = snapshot.items.map(\.domain)
        items.value = (items.value.filter { item in !back.contains { $0.id == item.id } } + back).sorted { $0.id < $1.id }
    }

    private static func record(_ i: PantryItem) -> PantryItemRecord {
        PantryItemRecord(
            id: i.id, name: i.name, quantity: i.quantity, language: i.language, aisle: i.aisle.key, inStock: i.inStock,
            alwaysHave: i.alwaysHave, purchasedDay: i.purchasedDay, expiresDay: i.expiresDay, updatedAt: 0
        )
    }
}
