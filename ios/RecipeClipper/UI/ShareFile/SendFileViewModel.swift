import Foundation
import Observation

/// A shared file ready for the share sheet (#149): its text and the name it goes by.
struct SentFile: Equatable {
    var text: String
    var name: String
}

/// "Send as file" (#149, phase 2; Android's SendFileUiState). `file` is set once the file's text
/// is made, for the screen to write and hand to the share sheet; `failed` once it couldn't be.
/// The screen clears both when it has acted.
struct SendFileUiState: Equatable {
    var busy = false
    var file: SentFile?
    var failed = false
}

/// Makes the shared file for one recipe (the recipe screen's menu), for the grocery list's
/// unticked items and their recipes (the Groceries menu), or for the pantry's in-stock items
/// (the Pantry menu). Nothing leaves the phone from here:
/// the screen writes the file and opens the user's own share sheet on it (Android's
/// SendFileViewModel writes it through BackupFiles; here, as for the Week's calendar file, the
/// view writes it).
@MainActor
@Observable
final class SendFileViewModel {
    private(set) var uiState = SendFileUiState()

    @ObservationIgnored private let share: ShareFileRepository
    /// The work in progress, for the tests.
    @ObservationIgnored private(set) var currentWork: Task<Void, Never>?

    init(share: ShareFileRepository) {
        self.share = share
    }

    /// Recipe `recipeId`, named for its `title`.
    func sendRecipe(_ recipeId: Int64, title: String) {
        let share = share
        send(title) { await share.recipeFile(recipeId: recipeId) }
    }

    /// The grocery list's unticked items, named `title` (the Groceries tab's name).
    func sendGroceries(title: String) {
        let share = share
        send(title) { await share.groceriesFile() }
    }

    /// The pantry's in-stock items, named `title` (the Pantry tab's name).
    func sendPantry(title: String) {
        let share = share
        send(title) { await share.pantryFile() }
    }

    /// The share sheet was opened on `file` (and has gone).
    func onSent() {
        uiState = SendFileUiState()
    }

    /// The file couldn't be written where the share sheet can read it.
    func onWriteFailed() {
        uiState = SendFileUiState(failed: true)
    }

    /// The failure was shown.
    func onFailureShown() {
        uiState = SendFileUiState()
    }

    private func send(_ title: String, make: @escaping () async -> String?) {
        guard !uiState.busy else { return }
        uiState = SendFileUiState(busy: true)
        currentWork = Task {
            let text = await make()
            uiState = text.map { SendFileUiState(file: SentFile(text: $0, name: ShareFile.fileName(title))) }
                ?? SendFileUiState(failed: true)
        }
    }
}
