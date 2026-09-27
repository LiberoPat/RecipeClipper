import Combine
import Foundation
import Observation

/// The end-of-cooking sheet (#147): `rows` as `PantryUseUp` worked them out; `ticked` the
/// worked-out rows to apply, by item id (all of them, to start); `choices` the asked rows'
/// answers, keep until the cook picks another.
struct UseUpSheet: Equatable {
    let rows: [UseUpRow]
    var ticked: Set<Int64>
    var choices: [Int64: UseUpChoice] = [:]

    func choice(_ id: Int64) -> UseUpChoice { choices[id] ?? .keep }
}

/// `sheet` is open; `updated` is the Undo snackbar's id, after a confirm that changed something.
struct UseUpUiState: Equatable {
    var sheet: UseUpSheet?
    var updated: Int?
}

/// Using up the pantry at the end of cooking (#147; Android's `PantryUseUpViewModel`): the
/// recipe screen hands over the ticked lines as shown when cook mode is finished; the sheet
/// lists what they did to the pantry, and one confirm applies it, with one Undo. Nothing changes
/// on a tick, and nothing without the confirm.
@MainActor
@Observable
final class PantryUseUpViewModel: Identifiable {
    private(set) var uiState = UseUpUiState()

    @ObservationIgnored private let pantry: PantryRepository
    @ObservationIgnored private let groceries: GroceryRepository
    // The model's definite "same ingredient" answers already given (#104); none without it.
    @ObservationIgnored private let decisions: DecisionRepository?
    @ObservationIgnored private var undo: (pantry: PantrySnapshot, added: [Int64])?
    @ObservationIgnored private var updates = 0

    init(pantry: PantryRepository, groceries: GroceryRepository, decisions: DecisionRepository? = nil) {
        self.pantry = pantry
        self.groceries = groceries
        self.decisions = decisions
    }

    /// Cooking finished with `lines` ticked: the sheet opens when any of them uses the pantry.
    func onCookFinished(language: String?, lines: [String]) {
        Task {
            let decided = await decisions?.current() ?? .none
            let rows = PantryUseUp.rows(lines, language: language, pantry: await pantry.items(), decisions: decided)
            if rows.isEmpty { return }
            let ticked = rows.filter { if case .subtract = $0.change { return true } else { return false } }.map(\.item.id)
            uiState.sheet = UseUpSheet(rows: rows, ticked: Set(ticked))
        }
    }

    func onToggle(_ id: Int64) {
        guard var sheet = uiState.sheet else { return }
        if sheet.ticked.contains(id) { sheet.ticked.remove(id) } else { sheet.ticked.insert(id) }
        uiState.sheet = sheet
    }

    func onChoice(_ id: Int64, _ choice: UseUpChoice) {
        guard var sheet = uiState.sheet else { return }
        sheet.choices[id] = choice
        uiState.sheet = sheet
    }

    /// Dismissed: nothing changes.
    func onDismissed() { uiState.sheet = nil }

    /// The sheet's one button. A ticked worked-out row gets its new quantity; used up, it goes out
    /// of stock with no quantity (none is left to know). Running low and out put the item's name
    /// on the grocery list, unless it's there already (#146's "On list").
    func onConfirm() {
        guard let sheet = uiState.sheet else { return }
        uiState.sheet = nil
        var quantities: [(PantryItem, String?)] = []
        var out: [PantryItem] = []
        var onList: [PantryItem] = []
        for row in sheet.rows {
            let item = row.item
            switch row.change {
            case .subtract(_, let after):
                guard sheet.ticked.contains(item.id) else { continue }
                quantities.append((item, after))
                if after == nil { out.append(item); onList.append(item) }
            case .ask:
                switch sheet.choice(item.id) {
                case .keep: break
                case .low: onList.append(item)
                case .out: out.append(item); onList.append(item)
                }
            }
        }
        var touched: [Int64] = []
        for id in quantities.map(\.0.id) + onList.map(\.id) where !touched.contains(id) { touched.append(id) }
        if touched.isEmpty { return }
        // An unstructured Task isn't cancelled with the screen: once confirmed, it all lands.
        Task {
            let before = await pantry.snapshot(touched)
            for (item, quantity) in quantities {
                await pantry.edit(item.id, PantryEdit(name: item.name, quantity: quantity, alwaysHave: item.alwaysHave, expiresDay: item.expiresDay))
            }
            if !out.isEmpty { await pantry.setInStock(out.map(\.id), inStock: false) }
            let list = await currentGroceries()
            let lines = onList.filter { PantryList.ownLines($0, list).isEmpty }.map { NewGroceryLine(text: $0.name, language: $0.language) }
            var added: [Int64] = []
            if !lines.isEmpty {
                await groceries.add(lines)
                let existing = Set(list.map(\.id))
                added = await currentGroceries().map(\.id).filter { !existing.contains($0) }
            }
            undo = (before, added)
            updates += 1
            uiState.updated = updates
        }
    }

    /// Puts the pantry back as it was and takes the added lines off the grocery list.
    func onUndo() {
        guard let last = undo else { return }
        undo = nil
        uiState.updated = nil
        Task {
            await pantry.restore(last.pantry)
            if !last.added.isEmpty { _ = await groceries.delete(last.added) }
        }
    }

    /// The snackbar timed out or was dismissed: the changes stand.
    func onUpdatedDismissed() {
        undo = nil
        uiState.updated = nil
    }

    private func currentGroceries() async -> [GroceryItem] {
        for await list in groceries.observeItems().values { return list }
        return []
    }
}
