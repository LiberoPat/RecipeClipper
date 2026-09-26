import Combine
import Foundation
import Observation

/// The edit sheet's fields, for item `id`. Written only on Save.
struct PantryEditing: Equatable {
    let id: Int64
    var name: String
    var quantity: String
    var alwaysHave: Bool
    var expiresDay: Int64?
    let purchasedDay: Int64?
}

/// The snackbar, only ever for undo (#146). `id` tells two messages about the same item apart.
enum PantryMessage: Equatable {
    /// `name` was deleted: offers Undo.
    case deleted(id: Int, name: String)
}

/// `sections` is nil until the pantry has loaded; `hasItems` says whether it holds anything at
/// all (a search can find nothing in a full pantry). `onList` holds the items whose name is on
/// the grocery list, unticked: their rows show "On list" (#146).
struct PantryUiState: Equatable {
    var sections: [PantrySection]?
    var hasItems = false
    var query = ""
    var sort: PantrySort = .aisle
    var draft = ""
    var today: Int64 = 0
    var editing: PantryEditing?
    var message: PantryMessage?
    var onList: Set<Int64> = []
}

/// The Pantry tab (#51; Android's PantryViewModel): add by typing, search, sort by aisle or
/// expiry, toggle in and out of stock. Running out puts the item on the grocery list, silently
/// (#146); its row then says "On list", and tapping that takes it off again. A delete can be
/// undone.
@MainActor
@Observable
final class PantryViewModel {
    private(set) var uiState = PantryUiState()

    @ObservationIgnored private let pantry: PantryRepository
    @ObservationIgnored private let groceries: GroceryRepository
    @ObservationIgnored private let calendar: PlanCalendar
    @ObservationIgnored private let phoneLanguage: () -> String?
    @ObservationIgnored private var subscription: AnyCancellable?
    @ObservationIgnored private var grocerySubscription: AnyCancellable?
    @ObservationIgnored private var items: [PantryItem] = []
    @ObservationIgnored private var groceryItems: [GroceryItem] = []
    @ObservationIgnored private var deleted: PantrySnapshot?
    @ObservationIgnored private var messages = 0

    init(
        pantry: PantryRepository, groceries: GroceryRepository, calendar: PlanCalendar,
        phoneLanguage: @escaping () -> String? = { Locale.current.language.languageCode?.identifier }
    ) {
        self.pantry = pantry
        self.groceries = groceries
        self.calendar = calendar
        self.phoneLanguage = phoneLanguage
        uiState.today = calendar.today()
        subscription = pantry.observeItems()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] all in
                guard let self else { return }
                self.items = all
                self.uiState.hasItems = !all.isEmpty
                self.uiState.onList = self.onList()
                self.arrange()
            }
        grocerySubscription = groceries.observeItems()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] list in
                guard let self else { return }
                self.groceryItems = list
                self.uiState.onList = self.onList()
            }
    }

    private func arrange() {
        uiState.sections = PantryList.arrange(items, query: uiState.query, sort: uiState.sort)
    }

    /// The unticked grocery lines that are `item` itself: its name as the pantry puts it there
    /// (trimmed, case-insensitive, in its language). A recipe's "2 cups flour" isn't, so taking
    /// the item off the list never loses a recipe's line.
    private func lines(for item: PantryItem) -> [GroceryItem] {
        let name = item.name.kTrimmed.lowercased()
        return groceryItems.filter { !$0.checked && $0.language == item.language && $0.text.kTrimmed.lowercased() == name }
    }

    private func onList() -> Set<Int64> { Set(items.filter { !lines(for: $0).isEmpty }.map(\.id)) }

    func onQueryChange(_ query: String) {
        uiState.query = query
        arrange()
    }

    func onSortChange(_ sort: PantrySort) {
        uiState.sort = sort
        arrange()
    }

    func onDraftChange(_ text: String) { uiState.draft = text }

    /// Adds what was typed, in stock; a name already here is put back in stock instead.
    func onAddTyped() {
        let name = uiState.draft.kTrimmed
        guard !name.isEmpty else { return }
        uiState.draft = ""
        let language = LanguageWords.forTag(phoneLanguage())?.language ?? LanguageWords.english.language
        let today = calendar.today()
        let existing = PantryList.sameName(items, name: name, language: language)
        Task {
            if let existing {
                await pantry.restock([existing.id], day: today)
            } else {
                await pantry.add(NewPantryItem(name: name, language: language, purchasedDay: today))
            }
        }
    }

    /// In → out puts the item on the grocery list, silently, unless it's there already (#146);
    /// out → in means just bought, today.
    func onToggleStock(_ item: PantryItem) {
        Task {
            if item.inStock {
                await pantry.setInStock([item.id], inStock: false)
                if lines(for: item).isEmpty {
                    await groceries.add([NewGroceryLine(text: item.name, language: item.language)])
                }
            } else {
                await pantry.restock([item.id], day: calendar.today())
            }
        }
    }

    /// The row's "On list" tag, tapped: the item's own lines leave the grocery list. No snackbar (#146).
    func onTakeOffList(_ item: PantryItem) {
        let ids = lines(for: item).map(\.id)
        guard !ids.isEmpty else { return }
        Task { _ = await groceries.delete(ids) }
    }

    // MARK: The edit sheet

    func onEdit(_ item: PantryItem) {
        uiState.editing = PantryEditing(
            id: item.id, name: item.name, quantity: item.quantity ?? "", alwaysHave: item.alwaysHave,
            expiresDay: item.expiresDay, purchasedDay: item.purchasedDay
        )
    }

    func onEditName(_ name: String) { uiState.editing?.name = name }
    func onEditQuantity(_ quantity: String) { uiState.editing?.quantity = quantity }
    func onEditAlwaysHave(_ alwaysHave: Bool) { uiState.editing?.alwaysHave = alwaysHave }
    func onEditExpiry(_ day: Int64?) { uiState.editing?.expiresDay = day }

    func onEditSave() {
        guard let editing = uiState.editing, !editing.name.kIsBlank else { return }
        uiState.editing = nil
        Task {
            await pantry.edit(
                editing.id,
                PantryEdit(name: editing.name, quantity: editing.quantity, alwaysHave: editing.alwaysHave, expiresDay: editing.expiresDay)
            )
        }
    }

    func onEditDismissed() { uiState.editing = nil }

    /// Deletes the item being edited. One undo at a time: a second delete settles the first.
    func onEditDelete() {
        guard let editing = uiState.editing else { return }
        uiState.editing = nil
        Task {
            guard let gone = await pantry.delete(editing.id) else { return }
            deleted = gone
            messages += 1
            uiState.message = .deleted(id: messages, name: gone.items[0].name)
        }
    }

    func onUndoDelete() {
        guard let gone = deleted else { return }
        deleted = nil
        uiState.message = nil
        Task { await pantry.restore(gone) }
    }

    /// The snackbar timed out.
    func onMessageDismissed() {
        if case .deleted = uiState.message { deleted = nil }
        uiState.message = nil
    }
}
