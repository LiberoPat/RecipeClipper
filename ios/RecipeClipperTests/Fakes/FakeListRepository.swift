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
    /// What `observeCoverPhotos` emits: list id to its cover photos, as the SQL groups them.
    let coverPhotos = CurrentValueSubject<[Int64: [CoverPhoto]], Never>([:])

    /// What `observeCoverPlaceholders` emits: list id to its no-photo recipes' image sources (#403).
    let coverPlaceholders = CurrentValueSubject<[Int64: [NoPhotoSource]], Never>([:])

    private(set) var createCalls: [(name: String, recipeId: Int64?)] = []
    /// Every `createList(name:, addRecipeIds:)` (#377), in order.
    private(set) var createManyCalls: [(name: String, recipeIds: [Int64])] = []
    /// Every `setMembershipAll` (#377): each is one transaction in the real repository.
    private(set) var setMembershipAllCalls: [(recipeIds: [Int64], listId: Int64, inList: Bool)] = []
    private(set) var renameCalls: [(listId: Int64, name: String)] = []
    private(set) var deleteCalls: [Int64] = []
    /// Every order `reorderLists` was given, in order.
    private(set) var reorderCalls: [[Int64]] = []
    /// How long after `reorderLists` returns the new order is published, as the database's
    /// observation does after a commit (it re-queries on its own queue). Zero: at once.
    var reorderPublishDelay: Duration = .zero
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

    func observeCoverPhotos() -> AnyPublisher<[Int64: [CoverPhoto]], Never> {
        coverPhotos.eraseToAnyPublisher()
    }

    func observeCoverPlaceholders() -> AnyPublisher<[Int64: [NoPhotoSource]], Never> {
        coverPlaceholders.eraseToAnyPublisher()
    }

    /// What `observeSavedRecipes` emits (#305).
    let savedRecipes = CurrentValueSubject<[SavedRecipe], Never>([])

    func observeSavedRecipes() -> AnyPublisher<[SavedRecipe], Never> {
        savedRecipes.eraseToAnyPublisher()
    }

    @MainActor func setMembership(recipeId: Int64, listId: Int64, inList: Bool) async {
        var rows = membership.value
        let row = Membership(recipeId: recipeId, listId: listId)
        if inList { rows.insert(row) } else { rows.remove(row) }
        membership.send(rows)
    }

    @MainActor func setMembershipAll(recipeIds: [Int64], listId: Int64, inList: Bool) async {
        setMembershipAllCalls.append((recipeIds, listId, inList))
        var rows = membership.value
        for id in recipeIds {
            let row = Membership(recipeId: id, listId: listId)
            if inList { rows.insert(row) } else { rows.remove(row) }
        }
        membership.send(rows)
    }

    func observeMembershipCounts(recipeIds: [Int64]) -> AnyPublisher<[Int64: Int], Never> {
        membership
            .map { rows in
                var counts: [Int64: Int] = [:]
                for row in rows where recipeIds.contains(row.recipeId) { counts[row.listId, default: 0] += 1 }
                return counts
            }
            .eraseToAnyPublisher()
    }

    @MainActor
    @discardableResult
    func createList(name: String, addRecipeIds: [Int64]) async -> Int64 {
        createManyCalls.append((name, recipeIds: addRecipeIds))
        let id = nextId
        nextId += 1
        lists.send(lists.value + [RecipeList(id: id, name: name, isBuiltIn: false, isFavorites: false, recipeCount: 0)])
        membership.send(membership.value.union(addRecipeIds.map { Membership(recipeId: $0, listId: id) }))
        return id
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

    /// As the SQL does it: the named lists first, in that order, then the rest as they were.
    @MainActor func reorderLists(_ orderedIds: [Int64]) async {
        reorderCalls.append(orderedIds)
        let byId = Dictionary(lists.value.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        var seen = Set<Int64>()
        let named = orderedIds.filter { byId[$0] != nil && seen.insert($0).inserted }
        let rest = lists.value.map(\.id).filter { !seen.contains($0) }
        let reordered = (named + rest).compactMap { byId[$0] }
        if reorderPublishDelay == .zero {
            lists.send(reordered)
        } else {
            let delay = reorderPublishDelay
            Task { @MainActor in
                try? await Task.sleep(for: delay)
                self.lists.send(reordered)
            }
        }
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
