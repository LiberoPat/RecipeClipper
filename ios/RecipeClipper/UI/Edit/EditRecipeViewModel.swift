import Foundation
import Observation

/// `isNew` is "New recipe" from Home; otherwise the recipe `draft` was loaded from.
/// `showInvalid` is set by a Save that failed validation, so the rule is only pointed out once
/// the user has tried. `savedId` is set once the save lands, for the view to open it.
struct EditRecipeUiState: Equatable {
    let isNew: Bool
    var loading: Bool
    var draft = RecipeDraft()
    var showInvalid = false
    var saving = false
    var saveFailed = false
    /// The recipe to edit is gone (deleted elsewhere).
    var missing = false
    var savedId: Int64?

    init(isNew: Bool) {
        self.isNew = isNew
        loading = !isNew
    }
}

/// Edits a recipe's content, or types a new one in (#29; Android's EditRecipeViewModel).
/// Opened with a recipe id from the recipe screen's overflow menu, or with none from Home's
/// "New recipe".
@MainActor
@Observable
final class EditRecipeViewModel {
    private(set) var uiState: EditRecipeUiState
    @ObservationIgnored private let recipeId: Int64?
    @ObservationIgnored private let repository: RecipeRepository

    init(recipeId: Int64?, repository: RecipeRepository) {
        self.recipeId = recipeId.flatMap { $0 > 0 ? $0 : nil }
        self.repository = repository
        uiState = EditRecipeUiState(isNew: self.recipeId == nil)
        if let id = self.recipeId {
            Task { [weak self, repository] in
                let recipe = await repository.open(id: id)
                guard let self else { return }
                uiState.loading = false
                if let recipe {
                    uiState.draft = RecipeDraft.of(recipe)
                } else {
                    uiState.missing = true
                }
            }
        }
    }

    func onDraftChange(_ draft: RecipeDraft) {
        uiState.draft = draft
        uiState.saveFailed = false
    }

    func onSave() {
        let state = uiState
        guard !state.loading, !state.saving, !state.missing else { return }
        guard state.draft.isValid else {
            uiState.showInvalid = true
            return
        }
        uiState.saving = true
        uiState.saveFailed = false
        Task { [weak self, recipeId, repository] in
            let saved: Recipe?
            if let recipeId {
                saved = await repository.saveEdit(id: recipeId, draft: state.draft)
            } else {
                saved = await repository.addManual(draft: state.draft)
            }
            guard let self else { return }
            uiState.saving = false
            if let saved {
                uiState.savedId = saved.id
            } else {
                uiState.saveFailed = true
            }
        }
    }
}
