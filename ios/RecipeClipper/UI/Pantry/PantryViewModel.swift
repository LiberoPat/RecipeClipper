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
    /// Not written on Save: the sheet's stock control applies at once, as the row's menu and swipes do (#194).
    var stock: PantryStock
}

/// The snackbar, only ever for undo (#146). `id` tells two messages about the same item apart.
enum PantryMessage: Equatable {
    /// `name` was deleted: offers Undo.
    case deleted(id: Int, name: String)
    /// "Clear run-out items" removed every item that had run out (#194): offers Undo.
    case runOutCleared(id: Int)
    /// The basket tag took `count` lines off the grocery list (owner, 2026-09-29): offers Undo.
    case takenOffList(id: Int, count: Int)
}

/// `sections` is nil until the pantry has loaded; `hasItems` says whether it holds anything at
/// all (a search can find nothing in a full pantry), and `hasInStock` whether anything is in
/// stock, which is what "Send list" and "Send as file" send (#149). `onList` holds the items that
/// are on the grocery list, unticked (`PantryList.onListLines`), in any stock: their rows show the
/// basket Groceries tag (#146).
/// `hasRunOut` says whether anything has run out, what "Clear run-out items" needs, and
/// `confirmClearRunOut` is how many items its dialog asks about, while it's open (#194).
struct PantryUiState: Equatable {
    var sections: [PantrySection]?
    var hasItems = false
    var hasInStock = false
    var query = ""
    var sort: PantrySort = .aisle
    var draft = ""
    var today: Int64 = 0
    var editing: PantryEditing?
    var message: PantryMessage?
    var onList: Set<Int64> = []
    var hasRunOut = false
    var confirmClearRunOut: Int?
}

/// The Pantry tab (#51; Android's PantryViewModel): add by typing, search, sort by aisle or
/// expiry, and mark each item in stock, running low or run out (#194). Running low or out puts
/// the item on the grocery list, silently (#146). An item on the grocery list, its own line or any line naming
/// it, shows the basket Groceries tag, and tapping that takes those lines off. A delete, "Clear run-out
/// items" (#194, which leaves the grocery list alone) and the tag's removal can each be undone, one at a time.
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
    @ObservationIgnored private var undo: Undo?

    /// What the snackbar's Undo puts back: pantry items, or grocery lines.
    private enum Undo {
        case pantry(PantrySnapshot)
        case groceries(DeletedGroceries)
    }
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
                self.uiState.hasInStock = all.contains(where: \.inStock)
                self.uiState.hasRunOut = all.contains { !$0.inStock }
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

    /// The unticked grocery lines that are `item` itself (`PantryList.ownLines`): what running out checks for.
    private func lines(for item: PantryItem) -> [GroceryItem] { PantryList.ownLines(item, groceryItems) }

    private func onList() -> Set<Int64> {
        Set(items.filter { !PantryList.onListLines($0, groceryItems).isEmpty }.map(\.id))
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

    /// A row's swipe or menu, or the sheet's control (#194). Running low and run out put the item on the grocery
    /// list, silently, unless it's there already (#146); back in stock means just bought, today.
    func onSetStock(_ item: PantryItem, _ stock: PantryStock) {
        guard stock != item.stock else { return }
        Task {
            if stock == .inStock {
                await pantry.restock([item.id], day: calendar.today())
            } else {
                await pantry.setStock([item.id], stock: stock)
                if lines(for: item).isEmpty {
                    await groceries.add([NewGroceryLine(text: item.name, language: item.language)])
                }
            }
        }
    }

    /// The row's basket tag, tapped: every line that puts the item on the grocery list
    /// (`PantryList.onListLines`, a recipe's included) leaves it, with Undo (owner, 2026-09-29).
    func onTakeOffList(_ item: PantryItem) {
        let ids = PantryList.onListLines(item, groceryItems).map(\.id)
        guard !ids.isEmpty else { return }
        Task {
            guard let gone = await groceries.delete(ids) else { return }
            undo = .groceries(gone)
            messages += 1
            uiState.message = .takenOffList(id: messages, count: gone.items.count)
        }
    }

    // MARK: The edit sheet

    func onEdit(_ item: PantryItem) {
        uiState.editing = PantryEditing(
            id: item.id, name: item.name, quantity: item.quantity ?? "", alwaysHave: item.alwaysHave,
            expiresDay: item.expiresDay, purchasedDay: item.purchasedDay, stock: item.stock
        )
    }

    /// The sheet's stock control: applied at once, through the same path as the row's menu and swipes.
    func onEditStock(_ stock: PantryStock) {
        guard let editing = uiState.editing, let item = items.first(where: { $0.id == editing.id }) else { return }
        uiState.editing?.stock = stock
        onSetStock(item, stock)
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
            undo = .pantry(gone)
            messages += 1
            uiState.message = .deleted(id: messages, name: gone.items[0].name)
        }
    }

    /// "Clear run-out items" (#194) asks first, naming how many items would go, whatever the search.
    func onClearRunOut() {
        let count = items.filter { !$0.inStock }.count
        if count > 0 { uiState.confirmClearRunOut = count }
    }

    func onClearRunOutDismissed() { uiState.confirmClearRunOut = nil }

    /// The dialog's Clear: every item that has run out leaves the pantry, in one write; their
    /// grocery lines stay. Undo (`onUndoDelete`) puts them back exactly as they were.
    func onClearRunOutConfirm() {
        guard uiState.confirmClearRunOut != nil else { return }
        uiState.confirmClearRunOut = nil
        Task {
            guard let gone = await pantry.deleteRunOut() else { return }
            undo = .pantry(gone)
            messages += 1
            uiState.message = .runOutCleared(id: messages)
        }
    }

    /// Undoes the last removal: a delete, "Clear run-out items", or the tag's lines.
    func onUndoDelete() {
        guard let last = undo else { return }
        undo = nil
        uiState.message = nil
        Task {
            switch last {
            case .pantry(let snapshot): await pantry.restore(snapshot)
            case .groceries(let lines): await groceries.restore(lines)
            }
        }
    }

    /// The snackbar timed out.
    func onMessageDismissed() {
        if uiState.message != nil { undo = nil }
        uiState.message = nil
    }

    /// "Send list" (#149): every in-stock item (running low included) as plain text for the share sheet, arranged as the
    /// screen's sort arranges them, whatever the search; nil when nothing is in stock.
    func shareText(title: String, aisleName: (Aisle) -> String) -> String? {
        let inStock = items.filter(\.inStock)
        guard !inStock.isEmpty else { return nil }
        return PantryShareText.format(PantryList.arrange(inStock, query: "", sort: uiState.sort), title: title, aisleName: aisleName)
    }
}
