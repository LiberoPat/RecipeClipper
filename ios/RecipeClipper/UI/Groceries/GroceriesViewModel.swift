import Combine
import Foundation
import Observation

/// What the undo snackbar says was removed: one row's `label`, or the checked items (`label`
/// nil), whether "Done shopping" also changed the pantry (`putAway`), or the whole list (`all`, #219).
struct RemovedGroceries: Equatable {
    let id: Int
    let label: String?
    var putAway = false
    var all = false
}

/// One thing to put away after shopping (#146): the pantry item it restocks (`trackedId`), or a
/// new one named `name`. `key` tells them apart in `PutAwaySheet.ticked`.
struct PutAwayItem: Equatable, Identifiable {
    let key: String
    let name: String
    let language: String
    let aisle: Aisle
    let trackedId: Int64?

    var id: String { key }
}

/// The "Done shopping" sheet (#146): the ticked items the pantry can hold, and which of them go
/// in. What the pantry already tracks starts ticked; the rest doesn't.
struct PutAwaySheet: Equatable {
    let items: [PutAwayItem]
    var ticked: Set<String>
}

/// "For a recipe…" (#422): the row being linked, what was typed to search the cook's recipes, and the
/// `results` (most recently viewed first; nil until the library has answered).
struct LinkRecipeState: Equatable {
    let row: GroceryCombiner.Row
    var query = ""
    var results: [RecipeSummary]?
}

/// `sections` is nil until the list has loaded. `draft` is the "Add an item" field. `moving`
/// is the row whose aisle is being chosen. `putAway` is the open "Done shopping" sheet.
/// `recipeTitles` names the recipes items came from, for "Send list" (#149). `confirmClearAll` is
/// the number of rows "Clear the whole list" asks about (#219), while its dialog is open.
/// `recipeLines` is the line under each row naming the recipes it came from (#286), by the row's
/// id; a row only typed in has none. `recipeTags` is the same as small tags (#422), each with the
/// day it is planned on, for the `groceryRecipeTags` flag.
struct GroceriesUiState: Equatable {
    var sections: [GroceryCombiner.Section]?
    var draft = ""
    var moving: GroceryCombiner.Row?
    var removed: RemovedGroceries?
    var putAway: PutAwaySheet?
    var recipeTitles: [Int64: String] = [:]
    var confirmClearAll: Int?
    var recipeLines: [Int64: String] = [:]
    var recipeTags: [Int64: [RecipeTag]] = [:]
    var linking: LinkRecipeState?

    var hasChecked: Bool { (sections ?? []).contains { $0.rows.contains { $0.items.contains(where: \.checked) } } }
    /// The rows ticked, for the "Done shopping" bar (#286): a combined row is one thing in the basket.
    var ticked: Int {
        (sections ?? []).reduce(0) { sum, section in sum + section.rows.filter { $0.items.contains(where: \.checked) }.count }
    }
    var isEmpty: Bool { sections?.isEmpty == true }
    /// Anything on the list: what "Clear the whole list" needs (#219).
    var hasItems: Bool { !(sections ?? []).isEmpty }
}

/// The Groceries tab (#50; Android's GroceriesViewModel): the list grouped by aisle, with lines
/// naming the same ingredient together (and added up when that's exact, `GroceryCombiner`).
/// Everything is written as it happens. A tick only ticks (#146): "Done shopping" puts what was
/// bought in the pantry and clears the ticked items in one step. A delete or "Done shopping" can
/// be undone from the snackbar, and so can the menu's "Clear ticked items" and "Clear the whole
/// list" (#219).
///
/// **Ticks stay put** (#219): within a visit, ticking or unticking never re-sorts the list
/// (`VisitOrder`); it's tidied when the screen is left (`onLeave`).
///
/// The ViewModel owns `uiState` and hands the rest on (#234): `VisitOrder` lays the list out,
/// `GroceryQuestions` asks the on-device model about it, and `GroceryRemovals` keeps the undo.
///
/// A typed item has no recipe, so it's read with the phone's language when the app has words
/// for it, else English: the one place the phone's language picks the words.
@MainActor
@Observable
final class GroceriesViewModel {
    private(set) var uiState = GroceriesUiState()

    @ObservationIgnored private let repository: GroceryRepository
    @ObservationIgnored private let pantry: PantryRepository
    @ObservationIgnored private let calendar: PlanCalendar
    // The servings a recipe is planned at, for a tag's tap (#422); none opens it at its own yield.
    @ObservationIgnored private let mealPlan: MealPlanRepository?
    // The cook's recipes, for "For a recipe…" (#422); none leaves the item menu without it.
    @ObservationIgnored private let recipes: RecipeRepository?
    @ObservationIgnored private var linkSubscription: AnyCancellable?
    @ObservationIgnored private var linkTask: Task<Void, Never>?
    @ObservationIgnored private let phoneLanguage: () -> String?
    @ObservationIgnored private var subscription: AnyCancellable?
    @ObservationIgnored private var pantrySubscription: AnyCancellable?
    @ObservationIgnored private var titlesSubscription: AnyCancellable?
    @ObservationIgnored private var pantryItems: [PantryItem] = []
    // Removals and their one undo.
    @ObservationIgnored private let removals: GroceryRemovals
    // The model's questions about the list (#99, #104); none without it.
    @ObservationIgnored private let questions: GroceryQuestions?

    init(
        repository: GroceryRepository, pantry: PantryRepository, calendar: PlanCalendar,
        decisions: DecisionRepository? = nil, mealPlan: MealPlanRepository? = nil, recipes: RecipeRepository? = nil,
        phoneLanguage: @escaping () -> String? = { Locale.current.language.languageCode?.identifier }
    ) {
        self.repository = repository
        self.pantry = pantry
        self.calendar = calendar
        self.mealPlan = mealPlan
        self.recipes = recipes
        self.phoneLanguage = phoneLanguage
        removals = GroceryRemovals(repository: repository, pantry: pantry)
        questions = decisions.map { GroceryQuestions(decisions: $0, repository: repository) }
        questions?.latestItems = { [weak self] in self?.latestItems ?? [] }
        pantrySubscription = pantry.observeItems()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.pantryItems = $0 }
        titlesSubscription = repository.observeRecipeTitles()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] titles in
                guard let self else { return }
                self.uiState.recipeTitles = titles
                self.uiState.recipeLines = Self.recipeLines(self.uiState.sections, titles: titles)
                self.uiState.recipeTags = Self.recipeTags(self.uiState.sections, titles: titles)
            }
        let answers = decisions?.observe() ?? Just(Decisions.none).eraseToAnyPublisher()
        subscription = repository.observeItems().combineLatest(answers)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items, decided in
                guard let self else { return }
                self.latestItems = items
                self.latestDecisions = decided
                self.show()
                self.questions?.ask(items, decided)
            }
    }

    @ObservationIgnored private var latestItems: [GroceryItem] = []
    @ObservationIgnored private var latestDecisions = Decisions.none
    // The visit's order (#219).
    @ObservationIgnored private var visitOrder = VisitOrder()

    /// Lays the list out in the visit's order, working it out afresh when more than a tick changed.
    private func show() {
        let items = latestItems
        uiState.sections = visitOrder.sections(items, decisions: latestDecisions)
        uiState.recipeLines = Self.recipeLines(uiState.sections, titles: uiState.recipeTitles)
        uiState.recipeTags = Self.recipeTags(uiState.sections, titles: uiState.recipeTitles)
        // A row being moved that has since gone closes the aisle picker.
        if let moving = uiState.moving, !moving.items.allSatisfy({ i in items.contains { $0.id == i.id } }) {
            uiState.moving = nil
        }
    }

    /// The line under each row naming the recipes it came from (#286), as "Send list" names them
    /// (#149): each once, in the order added, whatever the row adds up. A row only typed in has none.
    private static func recipeLines(_ sections: [GroceryCombiner.Section]?, titles: [Int64: String]) -> [Int64: String] {
        var lines: [Int64: String] = [:]
        for row in (sections ?? []).flatMap(\.rows) {
            let recipes = GroceryShareText.recipes(row.items, recipeTitles: titles)
            if !recipes.isEmpty { lines[row.id] = recipes.joined(separator: ", ") }
        }
        return lines
    }

    // MARK: Linking a typed item to a recipe (#422)

    /// Whether "For a recipe…" can open a picker: there is a library to pick from.
    var canLink: Bool { recipes != nil }

    /// "For a recipe…" on `row`: opens the picker. Only a row with no recipe of its own can be
    /// linked: what the app added from a recipe keeps its link.
    func onLinkStart(_ row: GroceryCombiner.Row) {
        guard recipes != nil, row.items.allSatisfy({ $0.recipeId == nil }) else { return }
        uiState.linking = LinkRecipeState(row: row)
        searchLink("")
    }

    func onLinkQueryChange(_ query: String) {
        uiState.linking?.query = query
        searchLink(query)
    }

    /// Debounced when something is typed, then the latest query's history only.
    private func searchLink(_ query: String) {
        linkTask?.cancel()
        let blank = query.kIsBlank
        linkTask = Task { [weak self] in
            if !blank { try? await Task.sleep(nanoseconds: 250_000_000) }
            guard !Task.isCancelled, let self, let recipes = self.recipes else { return }
            self.linkSubscription = recipes.observeHistory(query: query)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] found in self?.uiState.linking?.results = found }
        }
    }

    func onLinkDismissed() {
        linkTask?.cancel()
        linkSubscription = nil
        uiState.linking = nil
    }

    /// The recipe chosen: every line of the row is for it, on the next day this week it is planned
    /// or with no day.
    func onLinkPick(_ recipeId: Int64) {
        guard let row = uiState.linking?.row else { return }
        onLinkDismissed()
        Task {
            let day = await plannedDayThisWeek(recipeId)
            await repository.setRecipe(row.items.map(\.id), recipeId: recipeId, plannedDay: day)
        }
    }

    /// "Not for a recipe": takes the link off every line of `row`.
    func onUnlink(_ row: GroceryCombiner.Row) {
        Task { await repository.setRecipe(row.items.map(\.id), recipeId: nil, plannedDay: nil) }
    }

    /// The first day from today through the next six that `recipeId` is planned, or nil.
    private func plannedDayThisWeek(_ recipeId: Int64) async -> Int64? {
        guard let mealPlan else { return nil }
        let today = calendar.today()
        var found: [PlannedMeal] = []
        for await meals in mealPlan.observeDays(start: today, end: today + 6).values { found = meals; break }
        return found.filter { $0.recipeId == recipeId }.map(\.day).min()
    }

    /// The small recipe tags under each row (#422), by the row's id; none for a row only typed in.
    private static func recipeTags(_ sections: [GroceryCombiner.Section]?, titles: [Int64: String]) -> [Int64: [RecipeTag]] {
        var tags: [Int64: [RecipeTag]] = [:]
        for row in (sections ?? []).flatMap(\.rows) {
            let found = GroceryRecipeTags.of(row.items, recipeTitles: titles)
            if !found.isEmpty { tags[row.id] = found }
        }
        return tags
    }

    /// A tag was tapped (#422): `open` the recipe, at the servings the week plans it at when the
    /// tag has a day and the plan still holds it, else at its own yield.
    func onOpenRecipe(_ tag: RecipeTag, open: @escaping (_ recipeId: Int64, _ servings: Int?) -> Void) {
        Task {
            var servings: Int?
            if let day = tag.day, let mealPlan {
                var first: [PlannedMeal]?
                for await meals in mealPlan.observeDays(start: day, end: day).values { first = meals; break }
                servings = first?.first { $0.recipeId == tag.recipeId }?.servings
            }
            open(tag.recipeId, servings)
        }
    }

    /// The screen was left (#219): the next visit tidies the list, ticked rows at the bottom of
    /// their aisle. Done now, while nothing is on screen to jump.
    func onLeave() {
        visitOrder.reset()
        if uiState.sections != nil { show() }
    }

    func onDraftChange(_ text: String) { uiState.draft = text }

    func onAddTyped() {
        let text = uiState.draft.kTrimmed
        guard !text.isEmpty else { return }
        uiState.draft = ""
        let language = LanguageWords.forTag(phoneLanguage())?.language ?? LanguageWords.english.language
        Task { await repository.add([NewGroceryLine(text: text, language: language)]) }
    }

    /// Ticks or unticks every line in `row`: a combined row is one thing to pick up. A tick only
    /// ticks (#146): the pantry changes only at "Done shopping".
    func onToggle(_ row: GroceryCombiner.Row) {
        let checked = !row.items.allSatisfy(\.checked)
        Task { await repository.setChecked(row.items.map(\.id), checked: checked) }
    }

    func onMoveStart(_ row: GroceryCombiner.Row) { uiState.moving = row }

    func onMoveTo(_ aisle: Aisle) {
        guard let row = uiState.moving else { return }
        uiState.moving = nil
        Task { await repository.setAisle(row.items.map(\.id), aisle: aisle) }
    }

    func onMoveDismissed() { uiState.moving = nil }

    // MARK: Removing, with undo. One undo at a time: a second removal settles the first.

    func onDelete(_ row: GroceryCombiner.Row, label: String) {
        Task {
            guard let deleted = await repository.delete(row.items.map(\.id)) else { return }
            removed(deleted, label)
        }
    }

    /// "Done shopping" (#146): opens the sheet of ticked items the pantry can hold, one per
    /// ingredient, in the list's order; what it tracks starts ticked. A line the app can't name
    /// isn't listed but is cleared all the same; with nothing to list, the list clears at once.
    func onDoneShopping() {
        var items: [PutAwayItem] = []
        for item in (uiState.sections ?? []).flatMap({ $0.rows.flatMap(\.items) }) where item.checked {
            guard let words = LanguageWords.forTag(item.language), let name = IngredientName.of(item.text, words: words) else { continue }
            let tracked = PantryMatch.find(name, language: words.language, pantry: pantryItems)
            // One row per ingredient: "1 onion" and "2 onions" are one new item, named as first met.
            let key = tracked.map { "pantry-\($0.id)" } ?? "new-\(words.language)-\(IngredientName.key(name, words: words))"
            if !items.contains(where: { $0.key == key }) {
                items.append(PutAwayItem(key: key, name: tracked?.name ?? name, language: words.language, aisle: item.aisle, trackedId: tracked?.id))
            }
        }
        if items.isEmpty { return putAway([]) }
        uiState.putAway = PutAwaySheet(items: items, ticked: Set(items.filter { $0.trackedId != nil }.map(\.key)))
    }

    func onPutAwayToggle(_ key: String) {
        guard var sheet = uiState.putAway else { return }
        if sheet.ticked.contains(key) { sheet.ticked.remove(key) } else { sheet.ticked.insert(key) }
        uiState.putAway = sheet
    }

    func onPutAwayDismissed() { uiState.putAway = nil }

    /// The sheet's one button: the ticked items go in the pantry, and every ticked line leaves the list.
    func onPutAwayConfirm() {
        guard let sheet = uiState.putAway else { return }
        uiState.putAway = nil
        putAway(sheet.items.filter { sheet.ticked.contains($0.key) })
    }

    /// Restocks or adds `items`, bought today, then clears every ticked line: one undo for it all.
    private func putAway(_ items: [PutAwayItem]) {
        let today = calendar.today()
        Task {
            guard let removed = await removals.putAway(items, today: today) else { return }
            uiState.removed = removed
        }
    }

    /// "Clear ticked items" (#219): every ticked line leaves the list at once, with no "Done
    /// shopping" sheet, so the pantry is untouched. Undo puts them back.
    func onClearTicked() {
        Task {
            guard let cleared = await repository.clearChecked() else { return }
            removed(cleared, nil)
        }
    }

    /// "Clear the whole list" (#219) asks first, naming how many rows would go.
    func onClearAll() {
        let rows = (uiState.sections ?? []).reduce(0) { $0 + $1.rows.count }
        if rows > 0 { uiState.confirmClearAll = rows }
    }

    func onClearAllDismissed() { uiState.confirmClearAll = nil }

    /// The dialog's Clear: every line, ticked or not, leaves the list; the pantry is untouched. Undo puts them back.
    func onClearAllConfirm() {
        guard uiState.confirmClearAll != nil else { return }
        uiState.confirmClearAll = nil
        let ids = latestItems.map(\.id)
        Task {
            guard let deleted = await repository.delete(ids) else { return }
            removed(deleted, nil, all: true)
        }
    }

    private func removed(_ deleted: DeletedGroceries, _ label: String?, all: Bool = false) {
        uiState.removed = removals.removed(deleted, label, all: all)
    }

    /// Puts back what the last removal took: the items and, after "Done shopping", the pantry as it was.
    func onUndoRemove() {
        guard let last = removals.takeUndo() else { return }
        uiState.removed = nil
        Task { await removals.restore(last) }
    }

    /// The snackbar timed out: the removal stands.
    func onSnackbarDismissed() {
        removals.settle()
        uiState.removed = nil
    }

    /// "Send list" (#149): every unticked item as plain text for the share sheet, each naming the
    /// recipes it's for; nil when there's nothing left to buy.
    func shareText(title: String, aisleName: (Aisle) -> String) -> String? {
        let sections = uiState.sections ?? []
        guard sections.contains(where: { $0.rows.contains { $0.items.allSatisfy { !$0.checked } } }) else { return nil }
        return GroceryShareText.format(sections, title: title, recipeTitles: uiState.recipeTitles, aisleName: aisleName)
    }
}
