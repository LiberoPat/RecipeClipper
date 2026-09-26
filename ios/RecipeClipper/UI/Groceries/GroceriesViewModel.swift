import Combine
import Foundation
import Observation

/// What the undo snackbar says was removed: one row's `label`, or the checked items (`label`
/// nil), and whether "Done shopping" also changed the pantry (`putAway`).
struct RemovedGroceries: Equatable {
    let id: Int
    let label: String?
    var putAway = false
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

/// `sections` is nil until the list has loaded. `draft` is the "Add an item" field. `moving`
/// is the row whose aisle is being chosen. `putAway` is the open "Done shopping" sheet.
/// `recipeTitles` names the recipes items came from, for "Send list" (#149).
struct GroceriesUiState: Equatable {
    var sections: [GroceryCombiner.Section]?
    var draft = ""
    var moving: GroceryCombiner.Row?
    var removed: RemovedGroceries?
    var putAway: PutAwaySheet?
    var recipeTitles: [Int64: String] = [:]

    var hasChecked: Bool { (sections ?? []).contains { $0.rows.contains { $0.items.contains(where: \.checked) } } }
    var isEmpty: Bool { sections?.isEmpty == true }
}

/// The Groceries tab (#50; Android's GroceriesViewModel): the list grouped by aisle, with lines
/// naming the same ingredient together (and added up when that's exact, `GroceryCombiner`).
/// Everything is written as it happens. A tick only ticks (#146): "Done shopping" puts what was
/// bought in the pantry and clears the ticked items in one step. A delete or "Done shopping" can
/// be undone from the snackbar, and nothing else raises one.
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
    @ObservationIgnored private let phoneLanguage: () -> String?
    @ObservationIgnored private var subscription: AnyCancellable?
    @ObservationIgnored private var pantrySubscription: AnyCancellable?
    @ObservationIgnored private var titlesSubscription: AnyCancellable?
    @ObservationIgnored private var pantryItems: [PantryItem] = []
    @ObservationIgnored private var undo: Undo?
    @ObservationIgnored private var removals = 0
    // The model's aisles for what the keyword table puts in Other (#104), and the items asked about.
    @ObservationIgnored private let decisions: DecisionRepository?
    @ObservationIgnored private var askedAisles = Set<Int64>()

    init(
        repository: GroceryRepository, pantry: PantryRepository, calendar: PlanCalendar,
        decisions: DecisionRepository? = nil,
        phoneLanguage: @escaping () -> String? = { Locale.current.language.languageCode?.identifier }
    ) {
        self.repository = repository
        self.pantry = pantry
        self.calendar = calendar
        self.phoneLanguage = phoneLanguage
        self.decisions = decisions
        pantrySubscription = pantry.observeItems()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.pantryItems = $0 }
        titlesSubscription = repository.observeRecipeTitles()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.uiState.recipeTitles = $0 }
        let answers = decisions?.observe() ?? Just(Decisions.none).eraseToAnyPublisher()
        subscription = repository.observeItems().combineLatest(answers)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items, decided in
                guard let self else { return }
                self.latestItems = items
                self.uiState.sections = GroceryCombiner.sections(items, decisions: decided)
                // A row being moved that has since gone closes the aisle picker.
                if let moving = self.uiState.moving,
                   !moving.items.allSatisfy({ i in items.contains { $0.id == i.id } }) {
                    self.uiState.moving = nil
                }
                self.askAisles(items)
                self.askGroceryQuestions(items, decided)
            }
    }

    @ObservationIgnored private var latestItems: [GroceryItem] = []
    @ObservationIgnored private var askedGrocery = Set<DecisionQuestion>()

    /// Asks the model about close names and trailing text (#99), in the background. The list
    /// shows today's grouping until an answer lands; the decisions publisher then regroups it,
    /// and a fresh answer may file a line out of Other beside its partner.
    private func askGroceryQuestions(_ items: [GroceryItem], _ current: Decisions) {
        guard let decisions else { return }
        let unchecked = items.filter { !$0.checked }
        let open = (GroceryDecisions.ingredientNames(unchecked) + GroceryDecisions.trailingTexts(unchecked, decisions: current)
            + GroceryDecisions.samePairs(unchecked, decisions: current))
            .filter { !current.isAnswered($0) && askedGrocery.insert($0).inserted }
        if open.isEmpty { return }
        Task {
            await decisions.decide(open)
            let after = await decisions.current()
            let fresh = Set(open.filter { after.isAnswered($0) })
            for (aisle, ids) in GroceryDecisions.filing(self.latestItems, fresh: fresh, decisions: after) {
                await repository.fileFromOther(ids, aisle: aisle)
            }
        }
    }

    /// Asks the model the aisle of each item in Other whose name the keyword table doesn't know
    /// (#104), in the background. Only an answer that lands now files the items, and only those
    /// still in Other: an item in Other whose aisle was already decided was put there by the user.
    private func askAisles(_ items: [GroceryItem]) {
        guard let decisions else { return }
        var byQuestion: [DecisionQuestion: [Int64]] = [:]
        for item in items where item.aisle == .other && !item.checked && askedAisles.insert(item.id).inserted {
            if let q = DecisionCandidates.aisle(item.text, language: item.language) { byQuestion[q, default: []].append(item.id) }
        }
        if byQuestion.isEmpty { return }
        Task {
            let before = await decisions.current()
            let open = byQuestion.filter { !before.isAnswered($0.key) }
            if open.isEmpty { return }
            await decisions.decide(Array(open.keys))
            let after = await decisions.current()
            for (question, ids) in open {
                guard let aisle = after.aisle(question.input, language: question.language) else { continue }
                await repository.fileFromOther(ids, aisle: aisle)
            }
        }
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

    /// What the snackbar's Undo puts back: the list's items and, after "Done shopping", the pantry.
    private struct Undo {
        let groceries: DeletedGroceries?
        var restocked: PantrySnapshot?
        var added: [Int64] = []
    }

    /// "Done shopping" (#146): opens the sheet of ticked items the pantry can hold, one per
    /// ingredient, in the list's order; what it tracks starts ticked. A line the app can't name
    /// isn't listed but is cleared all the same; with nothing to list, the list clears at once.
    func onDoneShopping() {
        var items: [PutAwayItem] = []
        for item in (uiState.sections ?? []).flatMap({ $0.rows.flatMap(\.items) }) where item.checked {
            guard let words = LanguageWords.forTag(item.language), let name = IngredientName.of(item.text, words: words) else { continue }
            let tracked = PantryMatch.find(name, language: words.language, pantry: pantryItems)
            let key = tracked.map { "pantry-\($0.id)" } ?? "new-\(words.language)-\(name.lowercased())"
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
            let restock = items.compactMap(\.trackedId)
            var restocked: PantrySnapshot?
            if !restock.isEmpty {
                restocked = await pantry.snapshot(restock)
                await pantry.restock(restock, day: today)
            }
            var added: [Int64] = []
            for item in items where item.trackedId == nil {
                let new = NewPantryItem(name: item.name, language: item.language, aisle: item.aisle, purchasedDay: today)
                if let id = await pantry.add(new) { added.append(id) }
            }
            let cleared = await repository.clearChecked()
            if cleared == nil && restocked == nil && added.isEmpty { return }
            undo = Undo(groceries: cleared, restocked: restocked, added: added)
            removals += 1
            uiState.removed = RemovedGroceries(id: removals, label: nil, putAway: !items.isEmpty)
        }
    }

    private func removed(_ deleted: DeletedGroceries, _ label: String?) {
        undo = Undo(groceries: deleted)
        removals += 1
        uiState.removed = RemovedGroceries(id: removals, label: label)
    }

    /// Puts back what the last removal took: the items and, after "Done shopping", the pantry as it was.
    func onUndoRemove() {
        guard let last = undo else { return }
        undo = nil
        uiState.removed = nil
        Task {
            if let groceries = last.groceries { await repository.restore(groceries) }
            if let restocked = last.restocked { await pantry.restore(restocked) }
            for id in last.added { _ = await pantry.delete(id) }
        }
    }

    /// The snackbar timed out: the removal stands.
    func onSnackbarDismissed() {
        undo = nil
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

/// One line in the add sheet: `source`'s line number `index`.
struct SourceLine: Hashable {
    let source: String
    let index: Int
}

/// The "Add to groceries" sheet (#50): one recipe's lines or every planned recipe's, each
/// ticked to start unless the pantry has it (#51). `sources` is nil while the week's are loading. `added` is set once the
/// ticked lines are written; the sheet closes on it.
struct AddToGroceriesUiState: Equatable {
    var sources: [GrocerySource]?
    var unticked: Set<SourceLine> = []
    var added = false

    var tickedCount: Int {
        (sources ?? []).reduce(0) { total, s in
            total + s.lines.indices.filter { !unticked.contains(SourceLine(source: s.key, index: $0)) }.count
        }
    }
}

/// Backs the "Add to groceries" sheet (Android's AddToGroceriesViewModel). Adding is one
/// deliberate act with a button: the cook first unticks what's already in the cupboard.
@MainActor
@Observable
final class AddToGroceriesViewModel {
    private(set) var uiState = AddToGroceriesUiState()

    @ObservationIgnored private let repository: GroceryRepository
    @ObservationIgnored private let preferences: AppPreferences
    @ObservationIgnored private let pantry: PantryRepository
    @ObservationIgnored private let decisions: DecisionRepository?

    /// `decisions`: the model's answers (#104); none without it.
    init(
        repository: GroceryRepository, preferences: AppPreferences, pantry: PantryRepository,
        decisions: DecisionRepository? = nil
    ) {
        self.repository = repository
        self.preferences = preferences
        self.pantry = pantry
        self.decisions = decisions
    }

    /// One recipe, its `rendered` lines exactly as the reading view shows them.
    func setRecipe(_ recipeId: Int64, title: String, language: String?, rendered: [String]) {
        let sources = [GrocerySources.fromRecipe(recipeId: recipeId, title: title, language: language, rendered: rendered)]
        uiState = AddToGroceriesUiState(sources: sources)
        loading?.cancel()
        loading = Task { await untickCovered(sources) }
    }

    /// Lines whose ingredient the pantry has (in stock, or a staple) start unticked (#51), so
    /// the cook only reviews them. Matching is by name, never amount; a tick already changed stays.
    private func untickCovered(_ sources: [GrocerySource]) async {
        let items = await pantry.items()
        guard !Task.isCancelled, !items.isEmpty, uiState.sources == sources else { return }
        // Answers already cached count now (#104); new questions are asked for next time, so
        // ticks never change under the cook while the sheet is open.
        let decided = await decisions?.current() ?? .none
        var questions: [DecisionQuestion] = []
        for source in sources {
            for (index, line) in source.lines.enumerated()
            where PantryMatch.covered(line, language: source.language, pantry: items, decisions: decided) {
                uiState.unticked.insert(SourceLine(source: source.key, index: index))
            }
            if let words = LanguageWords.forTag(source.language) {
                let names = source.lines.compactMap { IngredientName.of($0, words: words) }
                questions += DecisionCandidates.samePairs(names, language: source.language, pantry: items)
            }
        }
        if let decisions, !questions.isEmpty { Task { await decisions.decide(questions) } }
    }

    /// Every recipe planned from `start` for seven days, at its planned servings, in the
    /// user's units.
    /// Starts empty at once (the sheet shows a spinner), then fills in; `loading` is that fetch
    /// (and, for one recipe too, the pantry read that unticks what's there).
    func loadWeek(_ start: Int64) {
        uiState = AddToGroceriesUiState()
        loading?.cancel()
        loading = Task { [repository, preferences] in
            let planned = await repository.plannedIngredients(start: start, end: start + 6)
            guard !Task.isCancelled else { return }
            let settings = preferences.current
            let decided = await self.decisions?.current() ?? .none
            let sources = GrocerySources.fromPlan(
                planned, system: settings.unitSystem, convertLiquids: settings.convertLiquids, decisions: decided
            )
            uiState.sources = sources
            await untickCovered(sources)
        }
    }

    @ObservationIgnored private(set) var loading: Task<Void, Never>?

    func onToggle(_ line: SourceLine) {
        if uiState.unticked.contains(line) { uiState.unticked.remove(line) } else { uiState.unticked.insert(line) }
    }

    func onAdd() {
        guard !uiState.added else { return }
        let lines: [NewGroceryLine] = (uiState.sources ?? []).flatMap { source in
            source.lines.enumerated().compactMap { index, text in
                uiState.unticked.contains(SourceLine(source: source.key, index: index)) ? nil
                    : NewGroceryLine(text: text, language: source.language, recipeId: source.recipeId, plannedDay: source.day)
            }
        }
        guard !lines.isEmpty else { return }
        uiState.added = true
        Task { await repository.add(lines) }
    }
}

extension AddToGroceriesViewModel: Identifiable {}
