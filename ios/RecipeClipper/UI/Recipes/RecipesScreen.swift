import SwiftUI

/// Every recipe on the phone (#102, the library that replaced History): newest viewed first by
/// default, searchable, sortable, swipe to delete. The + adds one: typed in (the editor), from a
/// pasted link (the usual import flow), or scanned (#226).
struct RecipesScreen: View {
    let vm: RecipesViewModel
    let onOpenRecipe: (Int64) -> Void
    var onNewRecipe: () -> Void = {}
    var onOpenUrl: (String) -> Void = { _ in }
    /// Pasted text that isn't a link (#372): the editor, the meal-plan review, Groceries' list sheet.
    var onOpenPasted: (PasteTarget) -> Void = { _ in }
    /// "Scan a recipe" (#226): the pictures taken or picked, in order; nil hides it (flag off).
    var onScan: (([Data]) -> Void)? = nil
    /// One cookbook (#345): the views strip in place of the filter pills, and a bookmark to save from a
    /// row, through `saveVM`'s sheet. A view's tile and a list's open their own screens.
    var oneCookbook = false
    var saveVM: SaveToListViewModel? = nil
    var onOpenView: (RecipeFilter) -> Void = { _ in }
    /// The strip's Lists card (#371): the Lists screen, Back to here.
    var onOpenLists: () -> Void = {}
    /// Quick filter chips under search (#378).
    var quickFilters = false
    /// The rows / photo grid toggle (#380); off, Recipes is the rows whatever was stored.
    var gridEnabled = false

    @State private var scanSource: ScanSource?
    @State private var saveSheetOpen = false
    /// A List's rows take insets, not a frame, so the readable column is made from the width.
    @State private var sideInset = ReadableWidth.gutter

    /// A link imports as always; any other pasted text goes where the same text shared would (#372).
    private func openPasted(_ target: PasteTarget) {
        if case .link(let url) = target { onOpenUrl(url) } else { onOpenPasted(target) }
    }

    var body: some View {
        let state = vm.uiState
        container(state)
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
                if state.view == nil {
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
        }
        // "Paste a link" takes any recipe text (#372): a link imports, anything else goes where a
        // share of it would.
        .sheet(isPresented: Binding(
            get: { vm.uiState.pastingLink },
            set: { presented in if !presented { vm.onLinkDismissed() } }
        )) {
            PasteLinkSheet(
                text: Binding(get: { vm.uiState.linkInput }, set: vm.onLinkChange),
                canOpen: vm.uiState.canOpenLink, noRecipe: vm.uiState.linkNoRecipe,
                onOpen: { if let target = vm.onOpenLink() { openPasted(target) } },
                onCancel: vm.onLinkDismissed
            )
        }
        .overlay(alignment: .bottom) {
            if state.restoreRefused {
                // The Undo the full free library refused (#345): its own outcome, said once.
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
        // Keyed on the burst, as Android's snackbar is (#268): a further swipe joins the batch
        // and updates the one snackbar's count in place; the window runs from the first swipe.
        .task(id: state.deleteBatch) {
            await SnackbarTimeout.run(pending: state.pendingDeletes, onTimeout: vm.onSnackbarDismissed)
        }
        .scanSources($scanSource) { onScan?($0) }
        .sheet(isPresented: $saveSheetOpen) {
            if let saveVM {
                SaveToListSheet(vm: saveVM)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
    }

    /// The recipes: the tidy rows, or (#380) a photo grid, under the same header.
    @ViewBuilder
    private func container(_ state: RecipesUiState) -> some View {
        let pantryChip = state.quick.contains(.usesPantry)
        if gridEnabled && state.layout == .grid {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    headerItems(state)
                    // Two columns on a phone, more as the width allows.
                    LazyVGrid(
                        columns: [GridItem(.adaptive(minimum: 150), spacing: 14, alignment: .top)],
                        alignment: .leading, spacing: 16
                    ) {
                        ForEach(state.recipes ?? []) { recipe in
                            LibraryGridCell(
                                recipe: recipe, onClick: { onOpenRecipe(recipe.id) },
                                onSaveToList: saveAction(for: recipe),
                                pantry: pantryChip ? state.coverage[recipe.id] : nil
                            )
                        }
                    }
                    .padding(.bottom, 32)
                    .accessibilityIdentifier("recipes.grid")
                }
                .padding(.horizontal, sideInset)
            }
        } else {
            List {
                Group {
                    headerItems(state)

                    ForEach(state.recipes ?? []) { recipe in
                        // The hairline above each row is drawn, not a list separator: adjacent
                        // rows share one separator, so hiding a row's bottom edge also hid the
                        // next row's top and no line showed at all.
                        VStack(spacing: 0) {
                            Hairline()
                            LibraryRow(
                                recipe: recipe, onClick: { onOpenRecipe(recipe.id) },
                                onSaveToList: saveAction(for: recipe),
                                pantry: pantryChip ? state.coverage[recipe.id] : nil
                            )
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
        }
    }

    /// Everything above the recipes: the title, search, the quick filter chips (#378), the views
    /// strip or the old pills, the line with the count, the layout toggle (#380) and the sort, and
    /// what an empty list says. A few rows of the list, or the head of the grid's page.
    @ViewBuilder
    private func headerItems(_ state: RecipesUiState) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ScreenTitle(state.view.map(RecipeFilterPills.name) ?? Strings.recipesTitle, style: Typography.headlineSmall)
            if let view = state.view { ViewNote(view: view) }
            SearchField(query: state.query, onQueryChange: vm.onQueryChange)
        }
        .padding(.bottom, vm.showsSortLine || state.showsFilter || (oneCookbook && state.showsViewsStrip) ? 0 : 12)
        .listRowSeparator(.hidden)

        if oneCookbook {
            // One cookbook (#345): the views, then the lists, in place of the pills.
            if state.showsViewsStrip {
                RecipesViewsStrip(
                    counts: state.counts, listCount: state.lists.count, onOpenView: onOpenView,
                    onOpenLists: onOpenLists
                )
                .listRowSeparator(.hidden)
            }
        } else if state.showsFilter {
            RecipeFilterPills(selected: state.filter, counts: state.counts, onSelect: vm.onFilterChange)
                .listRowSeparator(.hidden)
        }

        if quickFilters && state.showsFilter {
            QuickFilterChips(offered: state.quickFilters, on: state.quick, onToggle: vm.onQuickFilterToggle)
                .listRowSeparator(.hidden)
        }

        if vm.showsSortLine {
            sortLine(state)
                .listRowSeparator(.hidden)
        }

        if vm.showsCullNote, let keep = vm.cullKeep {
            CullNote(keep: keep)
                .padding(.vertical, 8)
                .listRowSeparator(.hidden)
        }

        if let recipes = state.recipes, recipes.isEmpty {
            Text(emptyMessage(state))
                .textStyle(Typography.bodyLarge)
                .foregroundStyle(Palette.muted)
                .listRowSeparator(.hidden)
        }
    }

    /// The bookmark's action: the save-to-list sheet for `recipe` (#345), when there is one.
    private func saveAction(for recipe: RecipeSummary) -> (() -> Void)? {
        guard oneCookbook, let saveVM else { return nil }
        return {
            saveVM.setRecipe(recipe.id, title: recipe.title)
            saveSheetOpen = true
        }
    }

    /// What an empty list says: the search's, else the filter's (while there is a library to
    /// filter), else the empty library's.
    private func emptyMessage(_ state: RecipesUiState) -> String {
        if !state.quick.isEmpty { return Strings.recipesNoResultsFilters }
        if !state.query.trimmingCharacters(in: .whitespaces).isEmpty { return Strings.recipesNoResults(state.query) }
        guard state.showsFilter || state.view != nil else { return Strings.recipesEmpty }
        return switch state.filter {
        case .all: Strings.recipesEmpty
        case .inAList: Strings.recipesEmptyInList
        case .notInAList: Strings.recipesEmptyNotInList
        case .cooked: Strings.recipesEmptyCooked
        case .typed: Strings.recipesEmptyTyped
        case .chat: Strings.recipesEmptyChat
        }
    }

    /// The quiet line under search (#286): how many recipes on the left ("20 recipes", or on the
    /// free tier "12 of 20 recipes", #107), and the order they're in on the right, which opens
    /// the sort menu. The sort was the only item of a ⋯ menu in the toolbar before.
    private func sortLine(_ state: RecipesUiState) -> some View {
        HStack(spacing: 8) {
            Group {
                // Inside a view the count is the view's own, not the free library's.
                if let count = vm.count, state.view == nil {
                    Text(Strings.recipesCount(count.saved, of: count.max))
                        .accessibilityIdentifier("recipes.count")
                } else {
                    Text(Strings.recipesListed(state.listed ?? 0))
                }
            }
            .textStyle(Typography.bodySmall)
            .foregroundStyle(Palette.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            if gridEnabled { layoutToggle(state.layout) }
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

    /// The rows / photo grid toggle beside the sort (#380): one button showing the layout it switches to.
    private func layoutToggle(_ layout: RecipesLayout) -> some View {
        let toGrid = layout == .rows
        return Button { vm.onLayoutChange(toGrid ? .grid : .rows) } label: {
            Image(systemName: toGrid ? "square.grid.2x2" : "list.bullet")
                .foregroundStyle(Palette.muted)
                .imageScale(.small)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(toGrid ? Strings.layoutToGrid : Strings.layoutToRows)
        .accessibilityIdentifier("recipes.layout")
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
