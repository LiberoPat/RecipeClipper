import Combine
import Foundation
@testable import RecipeClipper

/// A fake that models the list rather than only recording calls (Android's
/// FakeGroceryRepository): `items` is real state, a new line gets its aisle from the aisle
/// table as the real repository does, and delete and restore round-trip. `planned` is what
/// `plannedIngredients` answers, filtered by day.
final class FakeGroceryRepository: GroceryRepository {
    let items = CurrentValueSubject<[GroceryItem], Never>([])
    var planned: [PlannedIngredients] = []

    private var nextId: Int64 = 1

    func observeItems() -> AnyPublisher<[GroceryItem], Never> { items.eraseToAnyPublisher() }

    func add(_ lines: [NewGroceryLine]) async {
        var order = (items.value.map(\.sortOrder).max() ?? -1) + 1
        var added: [GroceryItem] = []
        for line in lines where !line.text.kIsBlank {
            let text = line.text.kTrimmed
            added.append(GroceryItem(
                id: nextId, text: text, language: line.language,
                aisle: Aisles.of(text, words: LanguageWords.forTag(line.language)), checked: false,
                sortOrder: order, recipeId: line.recipeId, plannedDay: line.plannedDay
            ))
            nextId += 1
            order += 1
        }
        items.value += added
    }

    func setChecked(_ ids: [Int64], checked: Bool) async {
        items.value = items.value.map { item in
            var i = item
            if ids.contains(i.id) { i.checked = checked }
            return i
        }
    }

    func setAisle(_ ids: [Int64], aisle: Aisle) async {
        items.value = items.value.map { item in
            var i = item
            if ids.contains(i.id) { i.aisle = aisle }
            return i
        }
    }

    func delete(_ ids: [Int64]) async -> DeletedGroceries? { remove { ids.contains($0.id) } }

    func clearChecked() async -> DeletedGroceries? { remove(\.checked) }

    private func remove(_ which: (GroceryItem) -> Bool) -> DeletedGroceries? {
        let gone = items.value.filter(which)
        if gone.isEmpty { return nil }
        items.value.removeAll(where: which)
        return DeletedGroceries(items: gone.map {
            GroceryItemRecord(
                id: $0.id, text: $0.text, language: $0.language, aisle: $0.aisle.key, checked: $0.checked,
                sortOrder: $0.sortOrder, recipeId: $0.recipeId, plannedDay: $0.plannedDay, updatedAt: 0
            )
        })
    }

    func restore(_ deleted: DeletedGroceries) async {
        items.value = (items.value + deleted.items.map(\.domain)).sorted { $0.sortOrder < $1.sortOrder }
    }

    func plannedIngredients(start: Int64, end: Int64) async -> [PlannedIngredients] {
        planned.filter { $0.day >= start && $0.day <= end }
    }
}
