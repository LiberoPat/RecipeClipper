import Combine
import Foundation
import Observation

/// `creatingList` is the "+ New list" field expanded inline — never a dialog on the sheet.
/// `recipeTitle` names the recipe under that field, as the one that goes into the new list (#286).
/// With several recipes (#377) `selectionCount` is how many, each list's `containsRecipe` means
/// "all of them are in it", and `partialListIds` are the lists holding some but not all.
struct SaveToListUiState: Equatable {
    var lists: [RecipeList] = []
    var creatingList = false
    var newListName = ""
    var recipeTitle = ""
    var selectionCount = 1
    var partialListIds: Set<Int64> = []

    /// What the bookmark icon renders from: filled once the recipe is in at least one list.
    var isSaved: Bool { lists.contains { $0.containsRecipe } }

    func isPartial(_ list: RecipeList) -> Bool { partialListIds.contains(list.id) }
}

/// Backs both the save-to-list sheet and the recipe screen's bookmark icon, which is why the
/// recipe screen holds one whether or not the sheet is open. The recipe id arrives through
/// `setRecipe` because on the import route there is no id until the parse finishes. Recipes'
/// selection (#377) hands it several through `setRecipes`.
@MainActor
@Observable
final class SaveToListViewModel {
    private(set) var uiState = SaveToListUiState()

    @ObservationIgnored private let repository: ListRepository
    @ObservationIgnored private var recipeIds: [Int64] = []
    @ObservationIgnored private var listsSubscription: AnyCancellable?

    init(repository: ListRepository) {
        self.repository = repository
    }

    /// Called once the recipe screen knows which recipe it is showing, and again when its title
    /// changes (an edit). A new id switches to that recipe's lists, dropping any earlier
    /// subscription (flatMapLatest); the same id doesn't re-read them.
    func setRecipe(_ id: Int64, title: String) {
        if uiState.recipeTitle != title { uiState.recipeTitle = title }
        uiState.selectionCount = 1
        guard recipeIds != [id] else { return }
        recipeIds = [id]
        uiState.partialListIds = []
        listsSubscription = repository.observeListsFor(recipeId: id)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] lists in self?.uiState.lists = lists }
    }

    /// The sheet for several recipes at once (#377). Each list shows whether all, some or none of
    /// them are in it. One recipe is the single case, exactly.
    func setRecipes(_ ids: [Int64], titles: [Int64: String] = [:]) {
        if ids.count == 1, let id = ids.first { return setRecipe(id, title: titles[id] ?? "") }
        uiState.recipeTitle = ""
        uiState.selectionCount = ids.count
        guard ids != recipeIds else { return }
        recipeIds = ids
        let total = ids.count
        listsSubscription = repository.observeLists()
            .combineLatest(repository.observeMembershipCounts(recipeIds: ids))
            .receive(on: DispatchQueue.main)
            .sink { [weak self] lists, counts in
                guard let self else { return }
                var partial = Set<Int64>()
                self.uiState.lists = lists.map { list in
                    var list = list
                    let held = counts[list.id] ?? 0
                    list.containsRecipe = total > 0 && held == total
                    if held > 0 && held < total { partial.insert(list.id) }
                    return list
                }
                self.uiState.partialListIds = partial
            }
    }

    /// Writes straight through: the sheet is dismissed, not submitted. With several recipes it
    /// adds all of them or removes all of them, in one transaction.
    func onListToggled(listId: Int64, inList: Bool) {
        guard !recipeIds.isEmpty else { return }
        let ids = recipeIds
        if ids.count == 1, let recipe = ids.first {
            Task { await repository.setMembership(recipeId: recipe, listId: listId, inList: inList) }
        } else {
            Task { await repository.setMembershipAll(recipeIds: ids, listId: listId, inList: inList) }
        }
    }

    /// What tapping a row does: a fully ticked list is unticked (removing all), anything else —
    /// empty or partial — ticks it (adding all).
    func onListTapped(_ list: RecipeList) {
        onListToggled(listId: list.id, inList: !list.containsRecipe)
    }

    func onStartCreating() { uiState.creatingList = true }

    func onCancelCreating() {
        uiState.creatingList = false
        uiState.newListName = ""
    }

    func onNewListNameChange(_ value: String) { uiState.newListName = value }

    /// Creating a list from the sheet puts the current recipe (or all the selected ones) in it; a
    /// blank name is ignored.
    func onCreateList() {
        let name = uiState.newListName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        let ids = recipeIds
        Task {
            if ids.count > 1 {
                await repository.createList(name: name, addRecipeIds: ids)
            } else {
                await repository.createList(name: name, addRecipeId: ids.first)
            }
            uiState.creatingList = false
            uiState.newListName = ""
        }
    }
}
