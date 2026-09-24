import Combine
import Foundation
@testable import RecipeClipper

/// A fake that models membership as real state rather than only recording calls: the
/// behaviour worth testing is a round trip (ticking a list writes, and the publisher the sheet
/// is watching re-emits with the box now checked). `recipeCount` and `containsRecipe` are
/// derived from `membership` exactly as the SQL derives them, so a `recipeCount` staged on a
/// list is ignored — stage membership instead.
final class FakeListRepository: ListRepository {
    struct Membership: Hashable {
        let recipeId: Int64
        let listId: Int64
    }

    /// The lists that exist. `recipeCount` / `containsRecipe` on these are overwritten.
    let lists = CurrentValueSubject<[RecipeList], Never>([])
    let membership = CurrentValueSubject<Set<Membership>, Never>([])
    /// What `observeRecipesIn` emits, regardless of the list asked for.
    let recipesIn = CurrentValueSubject<[RecipeSummary], Never>([])

    private(set) var createCalls: [(name: String, recipeId: Int64?)] = []
    private(set) var renameCalls: [(listId: Int64, name: String)] = []
    private(set) var deleteCalls: [Int64] = []
    private(set) var recipesInQueries: [Int64] = []

    private var nextId: Int64 = 100

    func stageMembership(_ pairs: [(recipeId: Int64, listId: Int64)]) {
        membership.send(Set(pairs.map { Membership(recipeId: $0.recipeId, listId: $0.listId) }))
    }

    func observeLists() -> AnyPublisher<[RecipeList], Never> {
        lists.combineLatest(membership)
            .map { all, rows in all.map { Self.withCounts($0, rows, recipeId: nil) } }
            .eraseToAnyPublisher()
    }

    func observeListsFor(recipeId: Int64) -> AnyPublisher<[RecipeList], Never> {
        lists.combineLatest(membership)
            .map { all, rows in all.map { Self.withCounts($0, rows, recipeId: recipeId) } }
            .eraseToAnyPublisher()
    }

    func observeRecipesIn(listId: Int64) -> AnyPublisher<[RecipeSummary], Never> {
        recipesInQueries.append(listId)
        return recipesIn.eraseToAnyPublisher()
    }

    @MainActor func setMembership(recipeId: Int64, listId: Int64, inList: Bool) async {
        var rows = membership.value
        let row = Membership(recipeId: recipeId, listId: listId)
        if inList { rows.insert(row) } else { rows.remove(row) }
        membership.send(rows)
    }

    @MainActor
    @discardableResult
    func createList(name: String, addRecipeId: Int64?) async -> Int64 {
        createCalls.append((name, addRecipeId))
        let id = nextId
        nextId += 1
        lists.send(lists.value + [RecipeList(id: id, name: name, isBuiltIn: false, isFavorites: false, recipeCount: 0)])
        if let addRecipeId {
            membership.send(membership.value.union([Membership(recipeId: addRecipeId, listId: id)]))
        }
        return id
    }

    @MainActor func rename(listId: Int64, name: String) async {
        renameCalls.append((listId, name))
        lists.send(lists.value.map { list in
            var list = list
            if list.id == listId { list.name = name }
            return list
        })
    }

    @MainActor func deleteList(listId: Int64) async {
        deleteCalls.append(listId)
        // Favorites is refused in SQL, so the fake refuses it here for the same reason.
        if lists.value.contains(where: { $0.id == listId && $0.isFavorites }) { return }
        lists.send(lists.value.filter { $0.id != listId })
        membership.send(membership.value.filter { $0.listId != listId })
    }

    private static func withCounts(_ list: RecipeList, _ rows: Set<Membership>, recipeId: Int64?) -> RecipeList {
        var list = list
        list.recipeCount = rows.filter { $0.listId == list.id }.count
        list.containsRecipe = recipeId.map { rows.contains(Membership(recipeId: $0, listId: list.id)) } ?? false
        return list
    }
}
