import Foundation
import Observation

/// One row of the sheet: its key ("r:", "g:" or "p:" and the file id), its text and a quieter
/// line (a grocery item's recipe, a pantry item's quantity).
struct ReceivedRow: Equatable, Identifiable {
    var key: String
    var text: String
    var detail: String?
    var id: String { key }
}

/// Where the added things went, so the app can show them.
enum ReceivedWhere: Equatable {
    case recipes, groceries, pantry
}

/// The "Add from this file" sheet (#149, phase 2; Android's ReceiveFileUiState). `open` while it
/// shows; `error` when the file couldn't be read or saved. Every row starts ticked. `added` is
/// set once the ticked ones are in, and the sheet closes on it, unless `skippedFree` says the free
/// library (#107) left some recipes out: then it stays open to say so until Done.
struct ReceiveFileUiState: Equatable {
    var open = false
    var error: BackupError?
    var recipes: [ReceivedRow] = []
    var groceries: [ReceivedRow] = []
    var pantry: [ReceivedRow] = []
    var unticked: Set<String> = []
    var pantryTo: PantryDestination = .pantry
    var adding = false
    var added: ReceivedWhere?
    /// How many recipes the free library left out, and its size.
    var skippedFree: SkippedFree?

    struct SkippedFree: Equatable {
        var skipped: Int
        var limit: Int
    }

    var isEmpty: Bool { recipes.isEmpty && groceries.isEmpty && pantry.isEmpty }
    var tickedCount: Int { (recipes + groceries + pantry).filter { !unticked.contains($0.key) }.count }
}

/// Backs the sheet a shared file opens (#149, phase 2; Android's ReceiveFileViewModel): what's
/// inside, each part ticked, and one Add. Recipes come in as an import would (merged by cleaned
/// link, never replacing one here); grocery items go on the grocery list, each naming its recipe;
/// pantry items go to the Pantry or onto the grocery list, the receiver's choice.
@MainActor
@Observable
final class ReceiveFileViewModel {
    private(set) var uiState = ReceiveFileUiState()

    @ObservationIgnored private let files: BackupFiles
    @ObservationIgnored private let share: ShareFileRepository
    @ObservationIgnored private var file: Backup?
    /// The read or the write in progress, for the tests.
    @ObservationIgnored private(set) var currentWork: Task<Void, Never>?

    init(files: BackupFiles, share: ShareFileRepository) {
        self.files = files
        self.share = share
    }

    /// Reads the file at `url` and opens the sheet on it (or on why it can't be read).
    func open(_ url: URL) {
        file = nil
        let files = files
        currentWork = Task {
            let decoded: Result<Backup, BackupError> = switch await files.read(url) {
            case .failure(let error): .failure(error)
            case .success(let package): BackupJson.decode(package.json)
            }
            switch decoded {
            case .failure(let error): uiState = ReceiveFileUiState(open: true, error: error)
            case .success(let backup):
                file = backup
                uiState = rows(backup)
            }
        }
    }

    func onToggle(_ key: String) {
        if uiState.unticked.contains(key) { uiState.unticked.remove(key) } else { uiState.unticked.insert(key) }
    }

    func onPantryTo(_ destination: PantryDestination) {
        uiState.pantryTo = destination
    }

    func onAdd() {
        let state = uiState
        guard let file, !state.adding, state.added == nil, state.tickedCount > 0 else { return }
        func ticked(_ rows: [ReceivedRow]) -> Set<String> {
            Set(rows.filter { !state.unticked.contains($0.key) }.map { String($0.key.dropFirst(2)) })
        }
        let choice = ShareChoice(
            recipeIds: ticked(state.recipes), groceryIds: ticked(state.groceries),
            pantryIds: ticked(state.pantry), pantryTo: state.pantryTo
        )
        let pantryToGroceries = !choice.pantryIds.isEmpty && choice.pantryTo == .groceries
        let where_: ReceivedWhere = if !choice.groceryIds.isEmpty || pantryToGroceries {
            .groceries
        } else if !choice.pantryIds.isEmpty {
            .pantry
        } else {
            .recipes
        }
        uiState.adding = true
        uiState.error = nil
        let share = share
        currentWork = Task {
            switch await share.receive(file, choice: choice) {
            case .failure(let error):
                uiState.adding = false
                uiState.error = error
            case .success(let summary):
                uiState.adding = false
                uiState.added = where_
                if let limit = summary.freeLimit, summary.recipesSkipped > 0 {
                    uiState.skippedFree = .init(skipped: summary.recipesSkipped, limit: limit)
                }
            }
        }
    }

    /// Closed, by the user or once the things were added and shown.
    func onDismiss() {
        file = nil
        uiState = ReceiveFileUiState()
    }

    private func rows(_ file: Backup) -> ReceiveFileUiState {
        let titles = Dictionary(file.recipes.map { ($0.id, $0.title) }, uniquingKeysWith: { first, _ in first })
        return ReceiveFileUiState(
            open: true,
            recipes: file.recipes.map { ReceivedRow(key: "r:\($0.id)", text: $0.title) },
            groceries: file.groceries.map {
                ReceivedRow(key: "g:\($0.id)", text: $0.text, detail: $0.recipeId.flatMap { titles[$0] })
            },
            pantry: file.pantry.map { ReceivedRow(key: "p:\($0.id)", text: $0.name, detail: $0.quantity) }
        )
    }
}
