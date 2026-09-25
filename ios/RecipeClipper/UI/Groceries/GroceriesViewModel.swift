import Combine
import Foundation
import Observation

/// What the undo snackbar says was removed: one row's `label`, or the checked items (`label` nil).
struct RemovedGroceries: Equatable {
    let id: Int
    let label: String?
}

/// What ticking a line off did to the pantry (#51), for the snackbar. `id` tells two apart.
enum PantryOffer: Equatable {
    /// A tracked item was out and is back in stock: on by default, with Undo.
    case restocked(id: Int, name: String)
    /// Not in the pantry yet: "Add to pantry" is offered, not done.
    case offer(id: Int, name: String, item: NewPantryItem)
}

/// `sections` is nil until the list has loaded. `draft` is the "Add an item" field. `moving`
/// is the row whose aisle is being chosen.
struct GroceriesUiState: Equatable {
    var sections: [GroceryCombiner.Section]?
    var draft = ""
    var moving: GroceryCombiner.Row?
    var removed: RemovedGroceries?
    var pantryOffer: PantryOffer?

    var hasChecked: Bool { (sections ?? []).contains { $0.rows.contains { $0.items.contains(where: \.checked) } } }
    var isEmpty: Bool { sections?.isEmpty == true }
}

/// The Groceries tab (#50; Android's GroceriesViewModel): the list grouped by aisle, with lines
/// naming the same ingredient together (and added up when that's exact, `GroceryCombiner`).
/// Everything is written as it happens; a delete or "Clear checked" can be undone.
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
    @ObservationIgnored private var pantryItems: [PantryItem] = []
    @ObservationIgnored private var restocked: PantrySnapshot?
    @ObservationIgnored private var removedItems: DeletedGroceries?
    @ObservationIgnored private var removals = 0
    @ObservationIgnored private var offers = 0

    init(
        repository: GroceryRepository, pantry: PantryRepository, calendar: PlanCalendar,
        phoneLanguage: @escaping () -> String? = { Locale.current.language.languageCode?.identifier }
    ) {
        self.repository = repository
        self.pantry = pantry
        self.calendar = calendar
        self.phoneLanguage = phoneLanguage
        pantrySubscription = pantry.observeItems()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.pantryItems = $0 }
        subscription = repository.observeItems()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items in
                guard let self else { return }
                self.uiState.sections = GroceryCombiner.sections(items)
                // A row being moved that has since gone closes the aisle picker.
                if let moving = self.uiState.moving,
                   !moving.items.allSatisfy({ i in items.contains { $0.id == i.id } }) {
                    self.uiState.moving = nil
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

    /// Ticks or unticks every line in `row`: a combined row is one thing to pick up. Ticking
    /// one off feeds the pantry (#51): an item the pantry tracks that was out is back in stock at
    /// once (undoable); one it doesn't track is offered, never added unasked. A line the app
    /// can't name is left alone.
    func onToggle(_ row: GroceryCombiner.Row) {
        let checked = !row.items.allSatisfy(\.checked)
        let first = row.items[0]
        Task {
            await repository.setChecked(row.items.map(\.id), checked: checked)
            if checked { await toPantry(first) }
        }
    }

    private func toPantry(_ item: GroceryItem) async {
        guard let words = LanguageWords.forTag(item.language), let name = IngredientName.of(item.text, words: words) else { return }
        let offer: PantryOffer
        if let tracked = PantryMatch.find(name, language: words.language, pantry: pantryItems) {
            if tracked.inStock || tracked.alwaysHave { return }
            restocked = await pantry.snapshot([tracked.id])
            await pantry.restock([tracked.id], day: calendar.today())
            offers += 1
            offer = .restocked(id: offers, name: tracked.name)
        } else {
            offers += 1
            offer = .offer(
                id: offers, name: name,
                item: NewPantryItem(name: name, language: words.language, aisle: item.aisle, purchasedDay: calendar.today())
            )
        }
        uiState.pantryOffer = offer
    }

    func onAddToPantry() {
        guard case .offer(_, _, let item) = uiState.pantryOffer else { return }
        uiState.pantryOffer = nil
        Task { await pantry.add(item) }
    }

    func onUndoRestock() {
        guard let snapshot = restocked else { return }
        restocked = nil
        uiState.pantryOffer = nil
        Task { await pantry.restore(snapshot) }
    }

    /// The pantry snackbar timed out: what was done stands, what was offered isn't.
    func onPantryOfferDismissed() {
        restocked = nil
        uiState.pantryOffer = nil
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

    func onClearChecked() {
        Task {
            guard let deleted = await repository.clearChecked() else { return }
            removed(deleted, nil)
        }
    }

    private func removed(_ deleted: DeletedGroceries, _ label: String?) {
        removedItems = deleted
        removals += 1
        uiState.removed = RemovedGroceries(id: removals, label: label)
    }

    func onUndoRemove() {
        guard let deleted = removedItems else { return }
        removedItems = nil
        uiState.removed = nil
        Task { await repository.restore(deleted) }
    }

    /// The snackbar timed out: the removal stands.
    func onSnackbarDismissed() {
        removedItems = nil
        uiState.removed = nil
    }

    /// The list as plain text for the share sheet; nil when there's nothing left to buy.
    func shareText(title: String, aisleName: (Aisle) -> String) -> String? {
        let sections = uiState.sections ?? []
        guard sections.contains(where: { $0.rows.contains { $0.items.allSatisfy { !$0.checked } } }) else { return nil }
        return GroceryShareText.format(sections, title: title, aisleName: aisleName)
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

    init(repository: GroceryRepository, preferences: AppPreferences, pantry: PantryRepository) {
        self.repository = repository
        self.preferences = preferences
        self.pantry = pantry
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
        for source in sources {
            for (index, line) in source.lines.enumerated() where PantryMatch.covered(line, language: source.language, pantry: items) {
                uiState.unticked.insert(SourceLine(source: source.key, index: index))
            }
        }
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
            let sources = GrocerySources.fromPlan(planned, system: settings.unitSystem, convertLiquids: settings.convertLiquids)
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
