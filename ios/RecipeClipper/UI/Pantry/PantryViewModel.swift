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

/// The snackbar. `id` tells two messages about the same item apart.
enum PantryMessage: Equatable {
    /// `item` just ran out: offers "Add to groceries".
    case outOfStock(id: Int, item: PantryItem)
    /// `name` was deleted: offers Undo.
    case deleted(id: Int, name: String)
    /// `name` went onto the grocery list.
    case addedToGroceries(id: Int, name: String)
}

/// `sections` is nil until the pantry has loaded; `hasItems` says whether it holds anything at
/// all (a search can find nothing in a full pantry).
struct PantryUiState: Equatable {
    var sections: [PantrySection]?
    var hasItems = false
    var query = ""
    var sort: PantrySort = .aisle
    var draft = ""
    var today: Int64 = 0
    var editing: PantryEditing?
    var message: PantryMessage?
}

/// The Pantry tab (#51; Android's PantryViewModel): add by typing, search, sort by aisle or
/// expiry, toggle in and out of stock. Running out offers "Add to groceries" from the
/// snackbar; a delete can be undone.
@MainActor
@Observable
final class PantryViewModel {
    private(set) var uiState = PantryUiState()

    @ObservationIgnored private let pantry: PantryRepository
    @ObservationIgnored private let groceries: GroceryRepository
    @ObservationIgnored private let calendar: PlanCalendar
    @ObservationIgnored private let phoneLanguage: () -> String?
    @ObservationIgnored private var subscription: AnyCancellable?
    @ObservationIgnored private var items: [PantryItem] = []
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
                self.arrange()
            }
    }

    private func arrange() {
        uiState.sections = PantryList.arrange(items, query: uiState.query, sort: uiState.sort)
    }

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

    /// In → out offers "Add to groceries"; out → in means just bought, today.
    func onToggleStock(_ item: PantryItem) {
        Task {
            if item.inStock {
                await pantry.setInStock([item.id], inStock: false)
                messages += 1
                uiState.message = .outOfStock(id: messages, item: item)
            } else {
                await pantry.restock([item.id], day: calendar.today())
            }
        }
    }

    func onAddToGroceries(_ item: PantryItem) {
        uiState.message = nil
        Task {
            await groceries.add([NewGroceryLine(text: item.name, language: item.language)])
            messages += 1
            uiState.message = .addedToGroceries(id: messages, name: item.name)
        }
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
