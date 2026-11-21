import SwiftUI

/// One list's recipes, most recently added first. Renaming and deleting the list live in the
/// overflow here. Membership is edited in the save-to-list sheet; the one other way off a list
/// is "Remove from this list" in a row's long-press menu or the selection's (#376, #377), which
/// is not a deletion.
struct ListDetailScreen: View {
    let vm: ListDetailViewModel
    let onOpenRecipe: (Int64) -> Void
    /// Makes "Make a cookbook" (#318); nil (the flag off, or tests) leaves it out. Made on first use.
    var makePrintVM: (() -> PrintViewModel)? = nil
    /// The long-press menu on a row (#376, the `rowActions` flag) and Select (#377, `multiSelect`).
    var rowActions = false
    var multiSelect = false
    /// The save-to-list sheet's ViewModel, for a row's Add to list; nil leaves the menu out.
    var saveVM: SaveToListViewModel? = nil
    /// Makes the Add to plan sheet's ViewModel, once a row's menu first needs it; nil leaves the item out.
    var makePlanVM: (() -> AddToPlanViewModel)? = nil

    @Environment(\.dismiss) private var dismiss
    @State private var printVM: PrintViewModel?
    @State private var confirmingDelete = false
    @State private var now = currentMillis()
    /// What a row's menu has open: the save-to-list, plan and share sheets.
    @State private var rowMenu = RowMenuPresenter()

    /// The long-press menu for one row (#376); nil while selecting or with the flag off.
    private func menu(for recipe: RecipeSummary) -> RecipeRowActions? {
        guard rowActions, !vm.uiState.selecting, let saveVM else { return nil }
        return RecipeRowActions(
            onAddToList: { rowMenu.addToList([recipe], saveVM: saveVM) },
            onAddToPlan: makePlanVM.map { make in
                { rowMenu.addToPlan(recipe, makePlanVM: make) { await vm.yieldServings(recipeId: recipe.id) } }
            },
            onShare: { rowMenu.share { await vm.onShare(recipeId: recipe.id, labels: Strings.shareTextLabels) } },
            onSelect: multiSelect ? { vm.onStartSelecting(recipe) } : nil,
            onRemoveFromList: { vm.onRemoveFromList(ids: [recipe.id]) },
            onDelete: { vm.onRequestDelete([recipe]) }
        )
    }

    var body: some View {
        let state = vm.uiState
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ScreenTitle(state.list?.name ?? "").padding(.bottom, 12)
                Hairline().padding(.bottom, 4)
                // A cookbook takes a while (#331): say so until the share sheet opens.
                if printVM?.uiState.busy == true {
                    HStack(spacing: 10) {
                        ProgressView().controlSize(.small)
                        Text(Strings.cookbookMaking).textStyle(Typography.bodyMedium).foregroundStyle(Palette.muted)
                    }
                    .padding(.top, 12)
                    .padding(.bottom, 4)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("list.cookbookMaking")
                }
                if state.loaded && state.recipes.isEmpty {
                    Text(Strings.listEmpty)
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.muted)
                        .padding(.top, 24)
                }
                ForEach(state.recipes) { recipe in
                    RecipeRow(
                        recipe: recipe, now: now,
                        onClick: { state.selecting ? vm.onToggleSelected(recipe.id) : onOpenRecipe(recipe.id) },
                        selecting: state.selecting, selected: state.selectedIds.contains(recipe.id)
                    )
                    .recipeRowMenu(menu(for: recipe))
                }
            }
            .padding(.horizontal, 20)
            .readableColumn()
            .padding(.top, 4)
            .padding(.bottom, 32)
        }
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        // Selecting (#377): Done leaves it (Back would leave the screen, so it is hidden meanwhile).
        .navigationBarBackButtonHidden(state.selecting)
        .toolbar {
            if state.selecting {
                ToolbarItem(placement: .principal) { RecipesSelectionTitle(count: state.selectedIds.count) }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(Strings.done, action: vm.onExitSelection)
                        .accessibilityIdentifier("recipes.selection.done")
                }
            } else if let list = state.list {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        if multiSelect && vm.canSelect {
                            Button { vm.onStartSelecting() } label: {
                                Label(Strings.select, systemImage: "checkmark.circle")
                            }
                            .accessibilityIdentifier("list.select")
                        }
                        Button(action: vm.onStartRenaming) {
                            Label(Strings.rename, systemImage: "pencil")
                        }
                        // A cookbook (#318): every recipe here in one PDF, for the share sheet.
                        if let makePrintVM, !state.recipes.isEmpty {
                            Button {
                                let print = printVM ?? makePrintVM()
                                printVM = print
                                print.makeCookbook(
                                    title: list.name, recipeIds: state.recipes.map(\.id),
                                    labels: Strings.printLabels, contents: Strings.cookbookContents
                                )
                            } label: {
                                Text(Strings.cookbook); Text(Strings.menuDescCookbook); Image(systemName: "book")
                            }
                            .accessibilityIdentifier("list.cookbook")
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
        .modifier(PrintEffect(vm: printVM))
        // The undo snackbar for deletes (#377), as Recipes': one for a burst, keyed on it (#268).
        .overlay(alignment: .bottom) {
            if state.restoreRefused {
                Snackbar(message: Strings.restoreFull, actionLabel: nil)
                    .frame(maxWidth: ReadableWidth.column)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .accessibilityIdentifier("recipes.restoreRefused")
            } else if let message = Strings.deletedMessage(state.pendingDeletes) {
                Snackbar(message: message, actionLabel: Strings.undo, action: vm.onUndoDelete)
                    .frame(maxWidth: ReadableWidth.column)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: state.pendingDeletes.isEmpty)
        .animation(.easeOut(duration: 0.2), value: state.restoreRefused)
        .task(id: state.restoreRefused) {
            guard state.restoreRefused else { return }
            do { try await Sleeps.real(SnackbarTimeout.duration) } catch { return }
            vm.onRestoreRefusedShown()
        }
        .task(id: state.deleteBatch) {
            await SnackbarTimeout.run(pending: state.pendingDeletes, onTimeout: vm.onSnackbarDismissed)
        }
        .rowMenuSheets(rowMenu, saveVM: saveVM)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if state.selecting, let saveVM {
                RecipesSelectionBar(
                    count: state.selectedIds.count,
                    onAddToList: { rowMenu.addToList(vm.selectedRecipes, saveVM: saveVM) },
                    onRemoveFromList: { vm.onRemoveFromList(ids: vm.selectedRecipes.map(\.id)) },
                    onDelete: { vm.onRequestDelete(vm.selectedRecipes) }
                )
            }
        }
        .recipeDeleteAlert(state.confirmingDelete, onConfirm: vm.onConfirmDelete, onCancel: vm.onCancelDelete)
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
