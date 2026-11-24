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
    /// Ask with a switch (#465, `pantryListSwitch`): whether the sheet has the switch at all.
    var listSwitch = false
    /// The "Add to grocery list" switch, applied at once too, shown only while low or out (`showsListSwitch`).
    var addToList = false

    var showsListSwitch: Bool { listSwitch && stock != .inStock }
}

/// The snackbar, only ever for undo (#146). `id` tells two messages about the same item apart.
enum PantryMessage: Equatable {
    /// `name` was deleted: offers Undo.
    case deleted(id: Int, name: String)
    /// "Clear run-out items" removed every item that had run out (#194): offers Undo.
    case runOutCleared(id: Int)
    /// The basket tag took `count` lines off the grocery list (owner, 2026-09-29): offers Undo.
    case takenOffList(id: Int, count: Int)
    /// The selection's Remove took `count` items out of the pantry (#258): offers Undo.
    case removed(id: Int, count: Int)
    /// The selection's Mark run out ran `count` items out (#258): offers Undo.
    case markedRunOut(id: Int, count: Int)
    /// A shortcut ran `name` low or out and put its line on the grocery list (#465): offers Undo.
    case addedToList(id: Int, name: String)
}

/// Selecting several items (#258). `ids` are checked. While a drag is under way (`anchor` set),
/// every row from `anchor` to `current`, in the screen's order, is checked (`adding`, the drag
/// began on an unchecked row) or unchecked, on top of `base`, the selection when the drag began:
/// dragging back over rows gives them back their state, as in Google Photos.
struct PantrySelection: Equatable {
    var ids: Set<Int64> = []
    var anchor: Int64?
    var current: Int64?
    var base: Set<Int64> = []
    var adding = true

    func ranged(_ order: [Int64]) -> PantrySelection {
        guard let anchor, let current, let from = order.firstIndex(of: anchor), let to = order.firstIndex(of: current) else {
            return self
        }
        let range = Set(order[min(from, to)...max(from, to)])
        var next = self
        next.ids = adding ? base.union(range) : base.subtracting(range)
        return next
    }
}

/// One of the three counts under the title (#286): how many of the rows shown are `stock` (in
/// stock, running low with its Low tag, or in the Run out section), and `firstId`, the first of
/// them top to bottom, where a tap scrolls; nil when there are none.
struct PantryCount: Equatable {
    let stock: PantryStock
    let count: Int
    let firstId: Int64?
}

/// `sections` is nil until the pantry has loaded; `hasItems` says whether it holds anything at
/// all (a search can find nothing in a full pantry), and `hasInStock` whether anything is in
/// stock, which is what "Send list" and "Send as file" send (#149). `onList` holds the items that
/// are on the grocery list, unticked (`PantryList.onListLines`), in any stock: their rows show the
/// basket Groceries tag (#146).
/// `hasRunOut` says whether anything has run out, what "Clear run-out items" needs, and
/// `confirmClearRunOut` is how many items its dialog asks about, while it's open (#194).
/// `counts` are In stock, Running low and Run out, counting what the list shows (a search
/// narrows them too), or empty while the pantry is, which hides them (#286).
struct PantryUiState: Equatable {
    var sections: [PantrySection]?
    var counts: [PantryCount] = []
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
    /// Nil while not selecting (#258).
    var selection: PantrySelection?

    /// The rows' ids, top to bottom, as the screen shows them: what a drag's range runs along.
    var order: [Int64] { (sections ?? []).flatMap { $0.items.map(\.id) } }

    /// Select all is done when every row shown is checked.
    var allSelected: Bool {
        guard let ids = selection?.ids else { return false }
        return order.allSatisfy(ids.contains)
    }
}

/// The Pantry tab (#51; Android's PantryViewModel): add by typing, search, sort by aisle or
/// expiry, and mark each item in stock, running low or run out (#194). Running low or out puts
/// the item on the grocery list, silently (#146), or with `pantryListSwitch` (#465) as the edit sheet's
/// switch says, and from a shortcut with an Undo. An item on the grocery list, its own line or any line naming
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

    /// What the snackbar's Undo puts back: pantry items or grocery lines; or the lines a shortcut added, taken off.
    private enum Undo {
        case pantry(PantrySnapshot)
        case groceries(DeletedGroceries)
        case added([Int64])
    }
    @ObservationIgnored private var messages = 0
    /// Ask with a switch (#465). Off (the default), as before: no switch, no "Added" snackbar.
    @ObservationIgnored private let listSwitch: () -> Bool
    /// Stock and grocery writes, one at a time in the order made (#465): the switch turned off
    /// just after the stock control must find the line the control added.
    @ObservationIgnored private let writes = OrderedWrites()
    /// The open sheet's grocery writes (#465): the line ids its own choices added, and what its
    /// switch, turned off, took off the list (put back if it is turned on again).
    @ObservationIgnored private var sheetAdded: Set<Int64> = []
    @ObservationIgnored private var sheetRemoved: DeletedGroceries?

    init(
        pantry: PantryRepository, groceries: GroceryRepository, calendar: PlanCalendar,
        phoneLanguage: @escaping () -> String? = { Locale.current.language.languageCode?.identifier },
        listSwitch: @escaping () -> Bool = { false }
    ) {
        self.pantry = pantry
        self.groceries = groceries
        self.calendar = calendar
        self.phoneLanguage = phoneLanguage
        self.listSwitch = listSwitch
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
        let sections = PantryList.arrange(items, query: uiState.query, sort: uiState.sort)
        uiState.sections = sections
        let rows = sections.flatMap(\.items)
        uiState.counts = items.isEmpty ? [] : PantryStock.allCases.map { stock in
            let those = rows.filter { $0.stock == stock }
            return PantryCount(stock: stock, count: those.count, firstId: those.first?.id)
        }
        // What the search hides, or what has gone, is no longer selected.
        if uiState.selection != nil {
            let shown = Set(uiState.order)
            uiState.selection?.ids.formIntersection(shown)
            uiState.selection?.base.formIntersection(shown)
        }
    }

    /// The unticked grocery lines that are `item` itself (`PantryList.ownLines`): what running out checks for.
    private func lines(for item: PantryItem) -> [GroceryItem] { PantryList.ownLines(item, groceryItems) }

    private func onList() -> Set<Int64> {
        Set(items.filter { !PantryList.onListLines($0, groceryItems).isEmpty }.map(\.id))
    }

    /// The Pantry is back in front: the expiry badges follow the real day, as the tab's ViewModel outlives it (#332).
    func onScreenResumed() {
        uiState.today = calendar.today()
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

    /// Adds what was typed, in stock, with the amount it starts with as its quantity (#302,
    /// `PantryEntry.split`). A name already here is put back in stock instead, taking a typed
    /// amount; with none, it keeps its own.
    /// Returns the writes so a test can await them (nil: nothing typed).
    @discardableResult
    func onAddTyped() -> Task<Void, Never>? {
        let typed = uiState.draft.kTrimmed
        guard !typed.isEmpty else { return nil }
        uiState.draft = ""
        let language = LanguageWords.forTag(phoneLanguage())?.language ?? LanguageWords.english.language
        let entry = PantryEntry.split(typed, words: LanguageWords.forTag(language))
        let today = calendar.today()
        let existing = PantryList.sameName(items, name: entry.name, language: language)
        return Task {
            if let existing {
                if let quantity = entry.quantity {
                    await pantry.edit(existing.id, PantryEdit(
                        name: existing.name, quantity: quantity, alwaysHave: existing.alwaysHave, expiresDay: existing.expiresDay
                    ))
                }
                await pantry.restock([existing.id], day: today)
            } else {
                await pantry.add(NewPantryItem(name: entry.name, language: language, quantity: entry.quantity, purchasedDay: today))
            }
        }
    }

    /// A row's swipe or menu (#194). Running low and run out put the item on the grocery list
    /// unless it's there already (#146): silently, or with `pantryListSwitch` (#465) with an
    /// "Added to groceries" Undo when a line really was added. Back in stock means just bought, today.
    func onSetStock(_ item: PantryItem, _ stock: PantryStock) {
        guard stock != item.stock else { return }
        let announce = listSwitch()
        writes.enqueue { [self] in
            let added = await applyStock(item, stock, addToList: true)
            if announce && !added.isEmpty {
                undo = .added(added)
                messages += 1
                uiState.message = .addedToList(id: messages, name: item.name)
            }
        }
    }

    /// Writes `stock`; running low or out, with `addToList`, puts the item's own line on the
    /// grocery list unless it's there. Returns the ids of the lines added. Run on `writes`.
    private func applyStock(_ item: PantryItem, _ stock: PantryStock, addToList: Bool) async -> [Int64] {
        if stock == .inStock {
            await pantry.restock([item.id], day: calendar.today())
            return []
        }
        await pantry.setStock([item.id], stock: stock)
        guard addToList else { return [] }
        return await addOwnLine(item)
    }

    /// Adds `item`'s own line unless it's on the list already; the ids added. Run on `writes`.
    private func addOwnLine(_ item: PantryItem) async -> [Int64] {
        let before = await currentGroceries()
        guard PantryList.ownLines(item, before).isEmpty else { return [] }
        await groceries.add([NewGroceryLine(text: item.name, language: item.language)])
        let existing = Set(before.map(\.id))
        return await currentGroceries().map(\.id).filter { !existing.contains($0) }
    }

    private func currentGroceries() async -> [GroceryItem] {
        for await list in groceries.observeItems().values { return list }
        return []
    }

    /// Waits for every queued stock and grocery write. For tests.
    func settleWrites() async { await writes.settle() }

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

    /// Opens the sheet. Its switch (#465) starts on when the item, low or out, is on the grocery list.
    func onEdit(_ item: PantryItem) {
        resetSheet()
        uiState.editing = PantryEditing(
            id: item.id, name: item.name, quantity: item.quantity ?? "", alwaysHave: item.alwaysHave,
            expiresDay: item.expiresDay, purchasedDay: item.purchasedDay, stock: item.stock,
            listSwitch: listSwitch(), addToList: item.stock != .inStock && uiState.onList.contains(item.id)
        )
    }

    /// The sheet's stock control: applied at once, through the same path as the row's menu and
    /// swipes, but never with the shortcuts' snackbar. With the switch (#465), a move from In
    /// stock turns it on; between Running low and Run out it stays as it is, and the list with it.
    func onEditStock(_ stock: PantryStock) {
        guard let editing = uiState.editing, let item = items.first(where: { $0.id == editing.id }), stock != editing.stock else { return }
        let on: Bool
        if !editing.listSwitch {
            on = true
        } else if stock == .inStock {
            on = editing.addToList
        } else if editing.stock == .inStock {
            on = true
        } else {
            on = editing.addToList
        }
        uiState.editing?.stock = stock
        uiState.editing?.addToList = on && editing.listSwitch
        writes.enqueue { [self] in
            // Turned back on by the move: what the switch took off comes back first.
            var back = false
            if on && stock != .inStock { back = await restoreRemoved() }
            let added = await applyStock(item, stock, addToList: on && !back)
            sheetAdded.formUnion(added)
        }
    }

    /// The sheet's "Add to grocery list" switch (#465), applied at once. Off takes every line that
    /// puts the item on the list off it (the lines the basket tag would); on puts back what off
    /// took, or adds the item's own line.
    func onEditAddToList(_ on: Bool) {
        guard let editing = uiState.editing, editing.showsListSwitch, editing.addToList != on,
              let item = items.first(where: { $0.id == editing.id }) else { return }
        uiState.editing?.addToList = on
        writes.enqueue { [self] in
            if on {
                if await restoreRemoved() { return }
                let added = await addOwnLine(item)
                sheetAdded.formUnion(added)
            } else {
                let list = await currentGroceries()
                let ids = PantryList.onListLines(item, list).map(\.id)
                guard !ids.isEmpty, let gone = await groceries.delete(ids) else { return }
                if let earlier = sheetRemoved {
                    sheetRemoved = DeletedGroceries(items: earlier.items + gone.items)
                } else {
                    sheetRemoved = gone
                }
            }
        }
    }

    /// Forgets the sheet's writes, behind any still queued.
    private func resetSheet() {
        writes.enqueue { [self] in
            sheetAdded = []
            sheetRemoved = nil
        }
    }

    /// Puts back what the sheet's switch took off, if anything; whether it did. Run on `writes`.
    private func restoreRemoved() async -> Bool {
        guard let removed = sheetRemoved else { return false }
        sheetRemoved = nil
        await groceries.restore(removed)
        return true
    }

    /// The sheet closes (#465). If its switch took off lines it hadn't added itself (a recipe's,
    /// or the item's own from before), the Undo snackbar offers them back, as the basket tag's does.
    /// Queued behind the sheet's writes, so it sees what they did.
    private func closeSheet() {
        writes.enqueue { [self] in
            let removed = sheetRemoved
            let added = sheetAdded
            sheetRemoved = nil
            sheetAdded = []
            guard let removed, !removed.items.allSatisfy({ added.contains($0.id) }) else { return }
            undo = .groceries(removed)
            messages += 1
            uiState.message = .takenOffList(id: messages, count: removed.items.count)
        }
    }

    func onEditName(_ name: String) { uiState.editing?.name = name }
    func onEditQuantity(_ quantity: String) { uiState.editing?.quantity = quantity }
    func onEditAlwaysHave(_ alwaysHave: Bool) { uiState.editing?.alwaysHave = alwaysHave }
    func onEditExpiry(_ day: Int64?) { uiState.editing?.expiresDay = day }

    func onEditSave() {
        guard let editing = uiState.editing, !editing.name.kIsBlank else { return }
        uiState.editing = nil
        closeSheet()
        Task {
            await pantry.edit(
                editing.id,
                PantryEdit(name: editing.name, quantity: editing.quantity, alwaysHave: editing.alwaysHave, expiresDay: editing.expiresDay)
            )
        }
    }

    func onEditDismissed() {
        uiState.editing = nil
        closeSheet()
    }

    /// Deletes the item being edited. One undo at a time: a second delete settles the first.
    func onEditDelete() {
        guard let editing = uiState.editing else { return }
        uiState.editing = nil
        // The delete's own Undo is the one offered: lines the switch took off stay off.
        resetSheet()
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

    // MARK: Selecting several items (#258)

    /// The ⋮ menu's "Select items": selecting, with nothing checked yet.
    func onStartSelecting() {
        if uiState.selection == nil { uiState.selection = PantrySelection() }
    }

    /// A touch and hold on `id`'s row: starts selecting with it checked, and a drag anchored there.
    func onLongPress(_ id: Int64) {
        onStartSelecting()
        onDragStart(id)
    }

    /// The row's Select action (VoiceOver, TalkBack): starts selecting with `id` checked, or checks it.
    func onSelect(_ id: Int64) {
        onStartSelecting()
        uiState.selection?.ids.insert(id)
    }

    /// A tap on a row, or its checkbox, while selecting.
    func onToggle(_ id: Int64) {
        guard var selection = uiState.selection else { return }
        if selection.ids.contains(id) { selection.ids.remove(id) } else { selection.ids.insert(id) }
        uiState.selection = selection
    }

    /// A drag from `id`'s checkbox (or after a touch and hold): it checks, or unchecks if `id` was checked.
    func onDragStart(_ id: Int64) {
        guard var selection = uiState.selection else { return }
        selection.anchor = id
        selection.current = id
        selection.base = selection.ids
        selection.adding = !selection.ids.contains(id)
        uiState.selection = selection.ranged(uiState.order)
    }

    /// The drag has reached `id`'s row: everything from the anchor to it takes the drag's state.
    func onDragTo(_ id: Int64) {
        guard var selection = uiState.selection, selection.anchor != nil, selection.current != id else { return }
        selection.current = id
        uiState.selection = selection.ranged(uiState.order)
    }

    func onDragEnd() {
        guard uiState.selection != nil else { return }
        uiState.selection?.anchor = nil
        uiState.selection?.current = nil
        uiState.selection?.base = []
    }

    /// Checks every row shown.
    func onSelectAll() {
        guard uiState.selection != nil else { return }
        let shown = Set(uiState.order)
        uiState.selection?.ids = shown
    }

    /// A section heading's "Select all": checks that section's rows too.
    func onSelectSection(_ section: PantrySection) {
        guard uiState.selection != nil else { return }
        uiState.selection?.ids.formUnion(section.items.map(\.id))
    }

    /// ✕.
    func onExitSelection() { uiState.selection = nil }

    /// Remove: every checked item leaves the pantry in one write, with one Undo for them all.
    func onRemoveSelected() {
        guard let ids = uiState.selection?.ids, !ids.isEmpty else { return }
        uiState.selection = nil
        Task {
            guard let gone = await pantry.delete(Array(ids)) else { return }
            undo = .pantry(gone)
            messages += 1
            uiState.message = .removed(id: messages, count: gone.items.count)
        }
    }

    /// Mark run out: every checked item still in stock runs out, as a swipe would (its name goes
    /// on the grocery list unless it's there), with one Undo that puts their stock back. The
    /// grocery lines stay, as they do when an item is restocked.
    func onMarkRunOutSelected() {
        guard let ids = uiState.selection?.ids, !ids.isEmpty else { return }
        uiState.selection = nil
        let changing = items.filter { ids.contains($0.id) && $0.inStock }
        guard !changing.isEmpty else { return }
        let missing = changing.filter { lines(for: $0).isEmpty }
        Task {
            let before = await pantry.snapshot(changing.map(\.id))
            await pantry.setStock(changing.map(\.id), stock: .runOut)
            if !missing.isEmpty {
                await groceries.add(missing.map { NewGroceryLine(text: $0.name, language: $0.language) })
            }
            undo = .pantry(before)
            messages += 1
            uiState.message = .markedRunOut(id: messages, count: changing.count)
        }
    }

    /// Undoes the last removal: a delete, "Clear run-out items", or the tag's lines; or takes off a shortcut's line (#465).
    func onUndoDelete() {
        guard let last = undo else { return }
        undo = nil
        uiState.message = nil
        Task {
            switch last {
            case .pantry(let snapshot): await pantry.restore(snapshot)
            case .groceries(let lines): await groceries.restore(lines)
            case .added(let ids): _ = await groceries.delete(ids)
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
