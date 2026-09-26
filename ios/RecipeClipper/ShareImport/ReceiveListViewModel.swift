import Foundation
import Observation

// Compiled into both the app ("Paste a list" in Groceries) and the share extension (a list
// shared in), like the rest of this folder (see project.yml).

/// Where the sheet put the lines.
enum ReceiveTarget: Equatable {
    case groceries
    case pantry
}

/// The "Add this list" sheet (#149; Android's ReceiveListUiState). `lines` is nil while it's
/// closed, and empty when what was pasted held no list. Every line starts ticked; `added` is set
/// once the ticked ones are written.
struct ReceiveListUiState: Equatable {
    var lines: [String]?
    var unticked: Set<Int> = []
    var added: ReceiveTarget?

    var tickedCount: Int { (lines ?? []).indices.filter { !unticked.contains($0) }.count }
}

/// Backs "Add this list" (#149; Android's ReceiveListViewModel): a list shared in as text, or
/// pasted, offered line by line with one button for Groceries and one for the Pantry. Lines are
/// added as written, read like a typed item: the phone's language, unless their words clearly
/// say another. On the grocery list they combine as any lines do (`GroceryCombiner`). In the
/// pantry each is its ingredient's name (the whole line when the app can't name it), and one it
/// already tracks is put back in stock rather than added twice, as ticking a grocery line off does.
@MainActor
@Observable
final class ReceiveListViewModel {
    private(set) var uiState = ReceiveListUiState()

    @ObservationIgnored private let groceries: GroceryRepository
    @ObservationIgnored private let pantry: PantryRepository
    @ObservationIgnored private let calendar: PlanCalendar
    @ObservationIgnored private let phoneLanguage: () -> String?
    /// The write in progress, for a caller (the tests, the share card) that waits for it.
    @ObservationIgnored private(set) var currentWrite: Task<Void, Never>?

    init(
        groceries: GroceryRepository, pantry: PantryRepository, calendar: PlanCalendar,
        phoneLanguage: @escaping () -> String? = { Locale.current.language.languageCode?.identifier }
    ) {
        self.groceries = groceries
        self.pantry = pantry
        self.calendar = calendar
        self.phoneLanguage = phoneLanguage
    }

    /// Opens the sheet on `text`'s lines (a share, or the clipboard; nil: nothing there).
    func open(_ text: String?) {
        uiState = ReceiveListUiState(lines: ReceivedList.lines(text ?? ""))
    }

    func onToggle(_ index: Int) {
        if uiState.unticked.contains(index) { uiState.unticked.remove(index) } else { uiState.unticked.insert(index) }
    }

    func onAddToGroceries() {
        guard let lines = ticked() else { return }
        let language = language(lines)
        uiState.added = .groceries
        let groceries = groceries
        currentWrite = Task { await groceries.add(lines.map { NewGroceryLine(text: $0, language: language) }) }
    }

    func onAddToPantry() {
        guard let lines = ticked() else { return }
        let language = language(lines)
        uiState.added = .pantry
        let pantry = pantry
        let today = calendar.today()
        currentWrite = Task {
            let words = LanguageWords.forTag(language)
            let items = await pantry.items()
            var seen = Set<String>()
            var restock: [Int64] = []
            for line in lines {
                let name = words.flatMap { IngredientName.of(line, words: $0) } ?? line.kTrimmed
                guard seen.insert(name.lowercased()).inserted else { continue }
                let tracked = words.map { PantryMatch.find(name, language: $0.language, pantry: items) }
                    ?? PantryList.sameName(items, name: name, language: language)
                if let tracked {
                    if !tracked.inStock && !tracked.alwaysHave && !restock.contains(tracked.id) { restock.append(tracked.id) }
                } else {
                    await pantry.add(NewPantryItem(name: name, language: language, purchasedDay: today))
                }
            }
            if !restock.isEmpty { await pantry.restock(restock, day: today) }
        }
    }

    /// Closed, by the user or once the lines are added.
    func onDismiss() {
        uiState = ReceiveListUiState()
    }

    private func ticked() -> [String]? {
        guard uiState.added == nil else { return nil }
        let lines = (uiState.lines ?? []).enumerated().filter { !uiState.unticked.contains($0.offset) }.map(\.element)
        return lines.isEmpty ? nil : lines
    }

    // As a typed item's, unless the lines' own words clearly say another language the app has.
    private func language(_ lines: [String]) -> String {
        let typed = LanguageWords.forTag(phoneLanguage())?.language ?? LanguageWords.english.language
        let resolved = LanguageWords.resolve(declared: typed, page: nil) { lines.joined(separator: "\n") }
        return LanguageWords.forTag(resolved)?.language ?? typed
    }
}
