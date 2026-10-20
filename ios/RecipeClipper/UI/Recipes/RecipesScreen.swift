import SwiftUI

/// Every recipe on the phone (#102, the library that replaced History): newest viewed first by
/// default, searchable, sortable, swipe to delete. The + adds one: typed in (the editor), from a
/// pasted link (the usual import flow), or scanned (#226).
struct RecipesScreen: View {
    let vm: RecipesViewModel
    let onOpenRecipe: (Int64) -> Void
    var onNewRecipe: () -> Void = {}
    var onOpenUrl: (String) -> Void = { _ in }
    /// "Scan a recipe" (#226): the pictures taken or picked, in order; nil hides it (flag off).
    var onScan: (([Data]) -> Void)? = nil

    @State private var scanSource: ScanSource?
    /// A List's rows take insets, not a frame, so the readable column is made from the width.
    @State private var sideInset = ReadableWidth.gutter

    var body: some View {
        let state = vm.uiState
        List {
            Group {
                VStack(alignment: .leading, spacing: 12) {
                    ScreenTitle(Strings.recipesTitle, style: Typography.headlineSmall)
                    SearchField(query: state.query, onQueryChange: vm.onQueryChange)
                }
                .padding(.bottom, vm.showsSortLine ? 0 : 12)
                .listRowSeparator(.hidden)

                if vm.showsSortLine {
                    sortLine(state)
                        .listRowSeparator(.hidden)
                }

                if let recipes = state.recipes, recipes.isEmpty {
                    Text(state.query.trimmingCharacters(in: .whitespaces).isEmpty
                         ? Strings.recipesEmpty
                         : Strings.recipesNoResults(state.query))
                        .textStyle(Typography.bodyLarge)
                        .foregroundStyle(Palette.muted)
                        .listRowSeparator(.hidden)
                }

                ForEach(state.recipes ?? []) { recipe in
                    // The hairline above each row is drawn, not a list separator: adjacent
                    // rows share one separator, so hiding a row's bottom edge also hid the
                    // next row's top and no line showed at all.
                    VStack(spacing: 0) {
                        Hairline()
                        LibraryRow(recipe: recipe) { onOpenRecipe(recipe.id) }
                    }
                        .listRowSeparator(.hidden)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) { vm.onDelete(recipe) } label: {
                                Label(Strings.delete, systemImage: "trash")
                            }
                            .tint(Palette.error)
                        }
                        .swipeActions(edge: .leading, allowsFullSwipe: true) {
                            Button(role: .destructive) { vm.onDelete(recipe) } label: {
                                Label(Strings.delete, systemImage: "trash")
                            }
                            .tint(Palette.error)
                        }
                }
            }
            .listRowInsets(EdgeInsets(top: 0, leading: sideInset, bottom: 0, trailing: sideInset))
            .listRowBackground(Palette.background)
        }
        .listStyle(.plain)
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width in
            sideInset = ReadableWidth.inset(in: width)
        }
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Menu {
                    Button(Strings.typeRecipe, action: onNewRecipe)
                    Button(Strings.pasteLink, action: vm.onPasteLink)
                    // "Scan a recipe" (#226, the photoText flag): a submenu of its two sources.
                    if onScan != nil {
                        Menu(Strings.scanRecipe) { ScanChoices(source: $scanSource) }
                    }
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel(Strings.addRecipe)
                .accessibilityIdentifier("recipes.add")
            }
        }
        .alert(Strings.pasteLink, isPresented: Binding(
            get: { vm.uiState.pastingLink },
            set: { presented in if !presented { vm.onLinkDismissed() } }
        )) {
            TextField(Strings.labelRecipeUrl, text: Binding(get: { vm.uiState.linkInput }, set: vm.onLinkChange))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            Button(Strings.cancel, role: .cancel, action: vm.onLinkDismissed)
            Button(Strings.go) { if let url = vm.onOpenLink() { onOpenUrl(url) } }
                .disabled(!state.canOpenLink)
        }
        .overlay(alignment: .bottom) {
            if let message = Strings.deletedMessage(state.pendingDeletes) {
                Snackbar(message: message, actionLabel: Strings.undo, action: vm.onUndoDelete)
                    .frame(maxWidth: ReadableWidth.column)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 12)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: state.pendingDeletes.isEmpty)
        // Keyed on the burst, as Android's snackbar is (#268): a further swipe joins the batch
        // and updates the one snackbar's count in place; the window runs from the first swipe.
        .task(id: state.deleteBatch) {
            await SnackbarTimeout.run(pending: state.pendingDeletes, onTimeout: vm.onSnackbarDismissed)
        }
        .scanSources($scanSource) { onScan?($0) }
    }

    /// The quiet line under search (#286): how many recipes on the left ("20 recipes", or on the
    /// free tier "12 of 20 recipes", #107), and the order they're in on the right, which opens
    /// the sort menu. The sort was the only item of a ⋯ menu in the toolbar before.
    private func sortLine(_ state: RecipesUiState) -> some View {
        HStack(spacing: 8) {
            Group {
                if let count = vm.count {
                    Text(Strings.recipesCount(count.saved, of: count.max))
                        .accessibilityIdentifier("recipes.count")
                } else {
                    Text(Strings.recipesListed(state.listed ?? 0))
                }
            }
            .textStyle(Typography.bodySmall)
            .foregroundStyle(Palette.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            // An exclusive choice, so radio glyphs rather than a bare checkmark.
            Menu {
                sortButton(.recentlyViewed, Strings.sortRecentlyViewed, current: state.sort)
                sortButton(.name, Strings.sortName, current: state.sort)
                sortButton(.dateAdded, Strings.sortDateAdded, current: state.sort)
                if vm.showsCookedSort {
                    sortButton(.recentlyCooked, Strings.sortRecentlyCooked, current: state.sort)
                }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "arrow.up.arrow.down")
                        .foregroundStyle(Palette.muted)
                        .imageScale(.small)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    Text(sortName(state.sort))
                        .textStyle(Typography.labelMedium)
                        .foregroundStyle(Palette.onBackground)
                }
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .accessibilityLabel(Strings.sort)
            .accessibilityValue(sortName(state.sort))
            .accessibilityIdentifier("recipes.sort")
        }
    }

    private func sortName(_ sort: RecipeSort) -> String {
        switch sort {
        case .recentlyViewed: Strings.sortRecentlyViewed
        case .name: Strings.sortName
        case .dateAdded: Strings.sortDateAdded
        case .recentlyCooked: Strings.sortRecentlyCooked
        }
    }

    private func sortButton(_ sort: RecipeSort, _ title: String, current: RecipeSort) -> some View {
        Button { vm.onSortChange(sort) } label: {
            Label(title, systemImage: sort == current ? "largecircle.fill.circle" : "circle")
        }
    }
}

private struct SearchField: View {
    let query: String
    let onQueryChange: (String) -> Void

    var body: some View {
        HStack(spacing: 8) {
            // The glyphs are capped at the largest non-accessibility size: uncapped, the
            // magnifier alone reached ~60pt at AX5 and took a fifth of the field's width from
            // the query it decorates. The query text itself is not capped.
            Image(systemName: "magnifyingglass").foregroundStyle(Palette.muted)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            TextField(Strings.searchRecipes, text: Binding(get: { query }, set: onQueryChange))
                .textStyle(Typography.bodyLarge)
                .foregroundStyle(Palette.onBackground)
                .autocorrectionDisabled()
                .submitLabel(.search)
            if !query.isEmpty {
                Button { onQueryChange("") } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Palette.muted)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Strings.clearSearch)
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 52)
        .background(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.outline, lineWidth: 1))
    }
}

/// An inverse card with one action, like Material's Snackbar: ink on the ground, light on ink.
struct Snackbar: View {
    let message: String
    /// Nil shows the message alone (a confirmation, with nothing to undo).
    let actionLabel: String?
    var action: () -> Void = {}
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        // At the accessibility sizes Undo goes under the message, which then gets the full
        // width and as many lines as it needs, instead of two truncated lines beside a button.
        let large = dynamicTypeSize.isAccessibilitySize
        let layout = large
            ? AnyLayout(VStackLayout(alignment: .trailing, spacing: 0))
            : AnyLayout(HStackLayout(spacing: 12))
        layout {
            Text(message)
                .textStyle(Typography.bodyMedium)
                .foregroundStyle(Palette.inverseOnSurface)
                .lineLimit(large ? nil : 2)
                .padding(.top, large ? 8 : 0)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let actionLabel {
                Button(actionLabel, action: action)
                    .buttonStyle(TextActionStyle(color: Palette.inversePrimary))
                    .fixedSize()
            }
        }
        .padding(.leading, 16)
        .padding(.trailing, 4)
        .padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 8).fill(Palette.inverseSurface))
        .shadow(color: .black.opacity(0.15), radius: 6, y: 2)
    }
}
