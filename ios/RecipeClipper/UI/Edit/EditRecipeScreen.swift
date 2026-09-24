import SwiftUI

/// Edit a recipe's text, or type one in (#29): name, yield, times, ingredients and steps one
/// per line, and an optional photo link. Saving opens the recipe via `onSaved`; Back discards.
struct EditRecipeScreen: View {
    let vm: EditRecipeViewModel
    let onSaved: (Int64) -> Void

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                ScreenTitle(state.isNew ? Strings.editTitleNew : Strings.editTitleEdit, style: Typography.headlineSmall)
                    .padding(.bottom, 4)
                if state.loading {
                    ProgressView().tint(Palette.primary)
                } else if state.missing {
                    Text(Strings.errorNotSaved)
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.error)
                } else {
                    fields(state)
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(Strings.save, action: vm.onSave)
                    .disabled(state.loading || state.saving || state.missing)
                    .accessibilityIdentifier("edit.save")
            }
        }
        .onChange(of: state.savedId) { _, id in
            if let id { onSaved(id) }
        }
    }

    @ViewBuilder
    private func fields(_ state: EditRecipeUiState) -> some View {
        if state.showInvalid && !state.draft.isValid {
            message(Strings.editErrorInvalid)
        }
        if state.saveFailed {
            message(Strings.editErrorSaveFailed)
        }
        OutlinedField(label: Strings.editLabelName, text: binding(\.name))
        OutlinedField(label: Strings.editLabelYield, text: binding(\.yield))
        OutlinedField(label: Strings.editLabelPrep, text: binding(\.prepTime))
        OutlinedField(label: Strings.editLabelCook, text: binding(\.cookTime))
        OutlinedField(label: Strings.editLabelTotal, text: binding(\.totalTime))
        MultilineField(label: Strings.editLabelIngredients, text: binding(\.ingredientsText))
        MultilineField(label: Strings.editLabelSteps, text: binding(\.instructionsText))
        OutlinedField(label: Strings.editLabelPhoto, text: binding(\.image), keyboard: .URL)
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .textStyle(Typography.bodyMedium)
            .foregroundStyle(Palette.error)
    }

    /// One field of the draft, written back through the ViewModel.
    private func binding(_ field: WritableKeyPath<RecipeDraft, String>) -> Binding<String> {
        Binding(
            get: { vm.uiState.draft[keyPath: field] },
            set: { value in
                var draft = vm.uiState.draft
                draft[keyPath: field] = value
                vm.onDraftChange(draft)
            }
        )
    }
}

/// A labelled box for one-entry-per-line text (ingredients, steps): grows with its content,
/// and a return starts a new line rather than submitting.
private struct MultilineField: View {
    let label: String
    @Binding var text: String
    @FocusState private var focused: Bool

    var body: some View {
        TextField(label, text: $text, axis: .vertical)
            .lineLimit(5...)
            .textStyle(Typography.bodyLarge)
            .foregroundStyle(Palette.onBackground)
            .textInputAutocapitalization(.sentences)
            .focused($focused)
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(focused ? Palette.primary : Palette.outline, lineWidth: focused ? 2 : 1)
            )
    }
}
