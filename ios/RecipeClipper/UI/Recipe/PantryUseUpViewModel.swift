import Combine
import Foundation
import Observation

/// The end-of-cooking sheet (#147) for `recipeId`: `rows` as `PantryUseUp` worked them out;
/// `ticked` the worked-out rows to apply, by item id (all of them, to start); `choices` the asked
/// rows' answers, keep until the cook picks another.
struct UseUpSheet: Equatable {
    let recipeId: Int64
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
/// recipe screen hands over the lines as shown when cook mode is finished (`onCookFinished`) or
/// a photo is added with "I made this" (`onMadeThis`); the sheet lists what they did to the
/// pantry, and one confirm applies it, with one Undo. Nothing changes on a tick, and nothing
/// without the confirm.
///
/// One cooking is offered once: a recipe whose sheet was confirmed or dismissed less than
/// `offerAgainAfter` ago isn't offered it again by either way in (`log`).
@MainActor
@Observable
final class PantryUseUpViewModel: Identifiable {
    private(set) var uiState = UseUpUiState()

    @ObservationIgnored private let pantry: PantryRepository
    @ObservationIgnored private let groceries: GroceryRepository
    @ObservationIgnored private let log: UseUpLog
    @ObservationIgnored private let clock: Clock
    // The model's definite "same ingredient" answers already given (#104); none without it.
    @ObservationIgnored private let decisions: DecisionRepository?
    /// What Undo puts back: the pantry rows as they were, the grocery lines added, and when the
    /// recipe's sheet was settled before this one, so a cook who undoes can be offered it again.
    @ObservationIgnored private var undo: (pantry: PantrySnapshot, added: [Int64], recipeId: Int64, settledBefore: Int64?)?
    @ObservationIgnored private var updates = 0
    @ObservationIgnored private var offering = false

    /// A cook's dinner and its photos fit well inside this; two cookings of one recipe rarely do.
    static let offerAgainAfter: Int64 = 12 * 60 * 60 * 1000

    init(pantry: PantryRepository, groceries: GroceryRepository, log: UseUpLog, clock: Clock, decisions: DecisionRepository? = nil) {
        self.pantry = pantry
        self.groceries = groceries
        self.log = log
        self.clock = clock
        self.decisions = decisions
    }

    /// Cooking finished with `lines` ticked: the sheet opens when any of them uses the pantry.
    func onCookFinished(recipeId: Int64, language: String?, lines: [String]) {
        offer(recipeId: recipeId, language: language, lines: lines)
    }

    /// A photo was added with "I made this" (#116): the recipe was cooked. The ticked lines, if
    /// any are ticked (as cook mode hands over), else every line of `ingredients`; both as shown.
    func onMadeThis(recipeId: Int64, language: String?, ingredients: [String], ticked: Set<Int>) {
        let lines = ticked.sorted().compactMap { ingredients.indices.contains($0) ? ingredients[$0] : nil }
        offer(recipeId: recipeId, language: language, lines: lines.isEmpty ? ingredients : lines)
    }

    private func offer(recipeId: Int64, language: String?, lines: [String]) {
        guard uiState.sheet == nil, !offering, !settledRecently(recipeId) else { return }
        offering = true
        Task {
            defer { offering = false }
            let decided = await decisions?.current() ?? .none
            let rows = PantryUseUp.rows(lines, language: language, pantry: await pantry.items(), decisions: decided)
            if rows.isEmpty { return }
            let ticked = rows.filter { if case .subtract = $0.change { return true } else { return false } }.map(\.item.id)
            uiState.sheet = UseUpSheet(recipeId: recipeId, rows: rows, ticked: Set(ticked))
        }
    }

    /// A clock set back leaves an entry in the future: it holds nothing back.
    private func settledRecently(_ recipeId: Int64) -> Bool {
        guard let at = log.useUps[recipeId] else { return false }
        return (0 ..< Self.offerAgainAfter).contains(clock.now() - at)
    }

    /// Records `recipeId`'s sheet as settled `at` (nil: never), keeping only entries still in the window.
    private func settle(_ recipeId: Int64, at: Int64?) {
        let now = clock.now()
        var kept = log.useUps.filter { $0.key != recipeId && (0 ..< Self.offerAgainAfter).contains(now - $0.value) }
        if let at { kept[recipeId] = at }
        log.useUps = kept
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

    /// Dismissed: nothing changes, and this cooking isn't offered again.
    func onDismissed() {
        guard let sheet = uiState.sheet else { return }
        uiState.sheet = nil
        settle(sheet.recipeId, at: clock.now())
    }

    /// The sheet's one button. A ticked worked-out row gets its new quantity; used up, it goes out
    /// of stock with no quantity (none is left to know). Running low and out set those states
    /// (#194) and put the item's name on the grocery list, unless it's there already (#146's "On
    /// list").
    func onConfirm() {
        guard let sheet = uiState.sheet else { return }
        uiState.sheet = nil
        let settledBefore = log.useUps[sheet.recipeId]
        settle(sheet.recipeId, at: clock.now())
        var quantities: [(PantryItem, String?)] = []
        var out: [PantryItem] = []
        var low: [PantryItem] = []
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
                case .low: low.append(item); onList.append(item)
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
            if !out.isEmpty { await pantry.setStock(out.map(\.id), stock: .runOut) }
            if !low.isEmpty { await pantry.setStock(low.map(\.id), stock: .runningLow) }
            let list = await currentGroceries()
            let lines = onList.filter { PantryList.ownLines($0, list).isEmpty }.map { NewGroceryLine(text: $0.name, language: $0.language) }
            var added: [Int64] = []
            if !lines.isEmpty {
                await groceries.add(lines)
                let existing = Set(list.map(\.id))
                added = await currentGroceries().map(\.id).filter { !existing.contains($0) }
            }
            undo = (before, added, sheet.recipeId, settledBefore)
            updates += 1
            uiState.updated = updates
        }
    }

    /// Puts the pantry back as it was and takes the added lines off the grocery list. Nothing was
    /// used up after all, so the recipe can be offered the sheet again.
    func onUndo() {
        guard let last = undo else { return }
        undo = nil
        uiState.updated = nil
        settle(last.recipeId, at: last.settledBefore)
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
