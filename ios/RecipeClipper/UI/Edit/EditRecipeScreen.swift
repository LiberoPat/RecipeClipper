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
                ScreenTitle(title(state), style: Typography.headlineSmall)
                    .padding(.bottom, 4)
                if let photo = state.photo {
                    PhotoReview(post: photo, state: state, onReadAgain: vm.onReadAgain)
                }
                if state.loading {
                    ProgressView().tint(Palette.primary)
                } else if state.missing {
                    Text(Strings.errorNotSaved)
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.error)
                } else if !state.reading {
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
                    .disabled(state.loading || state.saving || state.missing || state.reading)
                    .accessibilityIdentifier("edit.save")
            }
        }
        .onChange(of: state.savedId) { _, id in
            if let id { onSaved(id) }
        }
        .libraryFullAlert(
            isPresented: Binding(get: { vm.uiState.libraryFull }, set: { if !$0 { vm.onLibraryFullDismiss() } }),
            onUnlock: vm.onUnlock
        )
    }

    private func title(_ state: EditRecipeUiState) -> String {
        if state.photo != nil { return Strings.photoTitle }
        return state.isNew ? Strings.editTitleNew : Strings.editTitleEdit
    }

    @ViewBuilder
    private func fields(_ state: EditRecipeUiState) -> some View {
        if state.showInvalid && !state.draft.isValid {
            message(Strings.editErrorInvalid)
        }
        if state.saveFailed {
            message(Strings.editErrorSaveFailed)
        }
        if let notice = state.unlockNotice {
            message(Strings.unlockNotice(notice))
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

/// "Read the photo" (#198), above the fields: the post's pictures to check the lines against,
/// how the reading went, and the lines the recogniser was unsure of ("Check these lines").
private struct PhotoReview: View {
    let post: PhotoPost
    let state: EditRecipeUiState
    let onReadAgain: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(post.imageUrls.enumerated()), id: \.offset) { _, image in
                if let url = URL(string: image) {
                    CachedAsyncImage(url: url) { picture in
                        picture.resizable().scaledToFit()
                    } placeholder: {
                        Rectangle().fill(Palette.hairline).frame(height: 200)
                    }
                    .frame(maxWidth: .infinity, maxHeight: 480)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .accessibilityLabel(Strings.photoImageDescription(post.title))
                }
            }
            outcome
            if !state.uncertain.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text(Strings.photoCheckHeading)
                        .textStyle(Typography.titleSmall)
                        .foregroundStyle(Palette.accentText)
                    ForEach(Array(state.uncertain.enumerated()), id: \.offset) { _, line in
                        Text("• \(line)")
                            .textStyle(Typography.bodyMedium)
                            .foregroundStyle(Palette.onBackground)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.primary, lineWidth: 1))
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("edit.photoCheck")
            }
        }
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private var outcome: some View {
        switch state.photoOutcome {
        case nil:
            if state.reading {
                HStack(spacing: 12) {
                    ProgressView().tint(Palette.primary)
                    Text(Strings.photoReading)
                        .textStyle(Typography.bodyMedium)
                        .foregroundStyle(Palette.onBackground)
                }
            }
        case .read:
            note(Strings.photoRead)
        case .notSorted:
            note(Strings.photoNotSorted)
        case .failed:
            Text(Strings.photoFailed)
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(Palette.error)
            Button(Strings.tryAgain, action: onReadAgain)
                .buttonStyle(OutlinedActionStyle())
        }
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .textStyle(Typography.bodyMedium)
            .foregroundStyle(Palette.muted)
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
