import SwiftUI

/// One list's recipes, most recently added first. Renaming and deleting the list live in the
/// overflow here. Removing a recipe from the list is deliberately not offered: membership is
/// edited in one place, the save-to-list sheet.
struct ListDetailScreen: View {
    let vm: ListDetailViewModel
    let onOpenRecipe: (Int64) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var confirmingDelete = false
    @State private var now = currentMillis()

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(state.list?.name ?? "").padding(.bottom, 12)
                Hairline().padding(.bottom, 4)
                if state.loaded && state.recipes.isEmpty {
                    Text(Strings.listEmpty)
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.muted)
                        .padding(.top, 24)
                }
                ForEach(state.recipes) { recipe in
                    RecipeRow(recipe: recipe, now: now) { onOpenRecipe(recipe.id) }
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let list = state.list {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button(action: vm.onStartRenaming) {
                            Label(Strings.rename, systemImage: "pencil")
                        }
                        // Favorites is the only list that can't be deleted — "saved" is built
                        // around it. The other seeded lists delete like any other.
                        if !list.isFavorites {
                            Button(role: .destructive) { confirmingDelete = true } label: {
                                Label(Strings.deleteList, systemImage: "trash")
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel(Strings.moreOptions)
                }
            }
        }
        .alert(Strings.renameListTitle, isPresented: renamingBinding) {
            TextField(Strings.listName, text: Binding(get: { vm.uiState.renameValue }, set: vm.onRenameValueChange))
            Button(Strings.cancel, role: .cancel, action: vm.onCancelRenaming)
            Button(Strings.save, action: vm.onRenameConfirm)
                .disabled(state.renameValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .alert(Strings.deleteListTitle(state.list?.name ?? ""), isPresented: $confirmingDelete) {
            Button(Strings.delete, role: .destructive, action: vm.onDelete)
            Button(Strings.cancel, role: .cancel, action: vm.onCancelRenaming)
        } message: {
            Text(Strings.deleteListBody)
        }
        // The list is gone the moment the delete lands, so leave rather than show a shell.
        .onChange(of: state.deleted) { _, deleted in
            if deleted { dismiss() }
        }
        .onAppear { now = currentMillis() }
        #if DEBUG
        .onChange(of: state.list?.id) { _, id in
            if id != nil && DebugLaunch.autoRename && !state.renaming { vm.onStartRenaming() }
        }
        #endif
    }

    /// The alert closes itself on any button. Dismissal only lowers the flag and keeps the
    /// typed name, so Save works whichever order SwiftUI runs its action and this setter in.
    private var renamingBinding: Binding<Bool> {
        Binding(
            get: { vm.uiState.renaming },
            set: { presented in if !presented { vm.onRenameDismissed() } }
        )
    }
}
