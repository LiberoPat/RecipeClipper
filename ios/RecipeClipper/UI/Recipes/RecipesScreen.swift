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
    /// The long-press menu on a row (#376, the `rowActions` flag) and Select (#377, `multiSelect`).
    var rowActions = false
    var multiSelect = false
    /// Makes the Add to plan sheet's ViewModel, once a row's menu first needs it; nil leaves the item out.
    var makePlanVM: (() -> AddToPlanViewModel)? = nil
    /// Library first (#375): this is the Recipes tab's first screen, not a page pushed from Home. No Back, the
    /// gear and a + that opens the Add a recipe sheet beside the title, Today's card above search, and the
    /// four ways in when there is nothing yet. Nil, the screen is as before.
    var libraryRoot: LibraryRoot? = nil
    /// The top redesigned (#449, design B): with the library as the first screen and one cookbook, a ⋮ menu
    /// (Select, Settings) after the +, the views shelf above search, the filters in a sheet behind search.
    var topB = false

    /// Design B applies to the library as the first screen only, with one cookbook's views.
    private var styleB: Bool { topB && libraryRoot != nil && oneCookbook }

    @State private var scanSource: ScanSource?
    /// The library as the first screen (#375): the + 's sheet, what to do once it has gone, the
    /// restore's file picker and the Ask an AI sheet's ViewModel (made on first use).
    @State private var addSheetOpen = false
    @State private var afterAddSheet: (() -> Void)?
    @State private var restoring = false
    @State private var askAiVM: AskAiViewModel?
    /// Design B's filter sheet (#449).
    @State private var filterSheetOpen = false
    /// Design B (#449): the large title has scrolled out of sight, so the slim bar pins at the top.
    @State private var titleGone = false
    /// What a row's menu has open: the save-to-list, plan and share sheets.
    @State private var rowMenu = RowMenuPresenter()
    /// A List's rows take insets, not a frame, so the readable column is made from the width.
    @State private var sideInset = ReadableWidth.gutter

    /// A link imports as always; any other pasted text goes where the same text shared would (#372).
    private func openPasted(_ target: PasteTarget) {
        if case .link(let url) = target { onOpenUrl(url) } else { onOpenPasted(target) }
    }

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
            onDelete: { vm.onRequestDelete([recipe]) }
        )
    }

    var body: some View {
        let state = vm.uiState
        container(state)
        // Design B's pinned bar (#449): over the list, once the large title has gone by; not while selecting,
        // which has its own header.
        .overlay(alignment: .top) {
            if styleB, titleGone, !state.selecting, let root = libraryRoot {
                PinnedTitleBar(
                    onAdd: { addSheetOpen = true },
                    onSelect: multiSelect && state.canSelect ? { vm.onStartSelecting() } : nil,
                    onOpenSettings: root.onOpenSettings,
                    inset: sideInset
                )
                .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.15), value: titleGone)
        .listStyle(.plain)
        .onGeometryChange(for: CGFloat.self, of: { $0.size.width }) { width in
            sideInset = ReadableWidth.inset(in: width)
        }
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        // The library as the first screen (#375) has its title row, gear and + in the page; the bar shows
        // only while selecting, for Done.
        // Design B (#449) selects under its own header, pinned in the page, so the bar stays hidden then too.
        .toolbar(libraryRoot != nil && (!state.selecting || styleB) ? .hidden : .automatic, for: .navigationBar)
        // Selecting (#377): Done leaves it (Back would leave the screen, so it is hidden meanwhile).
        .navigationBarBackButtonHidden(state.selecting)
        .toolbar(state.selecting && !styleB ? .hidden : .automatic, for: .tabBar)
        .toolbar {
            if state.selecting && !styleB {
                ToolbarItem(placement: .principal) { RecipesSelectionTitle(count: state.selectedIds.count) }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(Strings.done, action: vm.onExitSelection)
                        .accessibilityIdentifier("recipes.selection.done")
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                if multiSelect && !state.selecting && state.canSelect && libraryRoot == nil {
                    Button(Strings.select) { vm.onStartSelecting() }
                        .accessibilityIdentifier("recipes.select")
                }
                if state.view == nil && !state.selecting && libraryRoot == nil {
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
        // The + 's sheet (#375): the four ways in, each going where Home's tile did once the sheet is away.
        .sheet(isPresented: $addSheetOpen, onDismiss: {
            let action = afterAddSheet
            afterAddSheet = nil
            action?()
        }) {
            AddRecipeSheet(
                onPasteLink: vm.onPasteLink,
                onScan: onScan,
                onTypeRecipe: onNewRecipe,
                onAskAi: libraryRoot.flatMap(askAiAction),
                onChoose: { action in
                    afterAddSheet = action
                    addSheetOpen = false
                }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        // Ask an AI (#351): a prompt to copy or share; the sheet starts over each time.
        .sheet(isPresented: Binding(
            get: { libraryRoot?.vm.uiState.askAiOpen ?? false },
            set: { open in
                if !open {
                    askAiVM?.onReset()
                    libraryRoot?.vm.onAskAiDismissed()
                }
            }
        )) {
            if let askAiVM, let root = libraryRoot {
                AskAiSheet(vm: askAiVM, onOpenPasted: { target in
                    // The sheet closes, then the answer opens where a share of it would.
                    askAiVM.onReset()
                    root.vm.onAskAiDismissed()
                    openPasted(target)
                })
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
        }
        // Design B's filters (#449): the three as switches, and Clear; they act at once, as the chips did.
        .sheet(isPresented: $filterSheetOpen) {
            RecipeFilterSheet(
                offered: vm.uiState.quickFilters, on: vm.uiState.quick,
                onToggle: vm.onQuickFilterToggle, onClear: vm.onClearQuickFilters
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        // The same picker as Settings' Import.
        .fileImporter(isPresented: $restoring, allowedContentTypes: [.json, .plainText, .zip]) { result in
            switch result {
            case .success(let url): libraryRoot?.vm.onRestorePicked(url)
            case .failure: libraryRoot?.vm.onRestorePickFailed()
            }
        }
        // The tour's tooltip points at the + (#375): the library is the first screen it shows on. None
        // while a sheet or the file picker is up.
        .rootTooltipHost(
            libraryRoot != nil,
            blocked: addSheetOpen || filterSheetOpen || restoring || state.pastingLink || state.selecting
                || (libraryRoot?.vm.uiState.askAiOpen ?? false)
        )
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
        .rowMenuSheets(rowMenu, saveVM: saveVM)
        // The selection's actions (#377): Add to list over the ticked recipes, or Delete them, with one confirmation.
        // Design B (#449): the count, Select all and Done, then the actions, pinned above the rows.
        .safeAreaInset(edge: .top, spacing: 0) {
            if state.selecting && styleB {
                RecipesSelectionHeaderB(
                    count: state.selectedIds.count,
                    allSelected: state.allSelected,
                    onSelectAll: vm.onSelectAll,
                    onDone: vm.onExitSelection,
                    onAddToList: saveVM.map { saveVM in { rowMenu.addToList(vm.selectedRecipes, saveVM: saveVM) } },
                    onDelete: { vm.onRequestDelete(vm.selectedRecipes) }
                )
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if state.selecting && !styleB, let saveVM {
                RecipesSelectionBar(
                    count: state.selectedIds.count,
                    onAddToList: { rowMenu.addToList(vm.selectedRecipes, saveVM: saveVM) },
                    onDelete: { vm.onRequestDelete(vm.selectedRecipes) }
                )
            }
        }
        .recipeDeleteAlert(state.confirmingDelete, onConfirm: vm.onConfirmDelete, onCancel: vm.onCancelDelete)
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
                            // No swipe in a grid: Delete is in the long-press menu (#376), with the rows' other actions.
                            LibraryGridCell(
                                recipe: recipe, onClick: { openOrToggle(recipe, state) },
                                onSaveToList: saveAction(for: recipe),
                                pantry: pantryChip ? state.coverage[recipe.id] : nil,
                                selecting: state.selecting, selected: state.selectedIds.contains(recipe.id)
                            )
                            .recipeRowMenu(menu(for: recipe))
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
                                recipe: recipe, onClick: { openOrToggle(recipe, state) },
                                onSaveToList: saveAction(for: recipe),
                                pantry: pantryChip ? state.coverage[recipe.id] : nil,
                                selecting: state.selecting, selected: state.selectedIds.contains(recipe.id)
                            )
                            .recipeRowMenu(menu(for: recipe))
                        }
                            .listRowSeparator(.hidden)
                            // No swipes while selecting (#377): a tap ticks, Delete is the bar's.
                            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                if !state.selecting {
                                    Button(role: .destructive) { vm.onDelete(recipe) } label: {
                                        Label(Strings.delete, systemImage: "trash")
                                    }
                                    .tint(Palette.error)
                                }
                            }
                            .swipeActions(edge: .leading, allowsFullSwipe: true) {
                                if !state.selecting {
                                    Button(role: .destructive) { vm.onDelete(recipe) } label: {
                                        Label(Strings.delete, systemImage: "trash")
                                    }
                                    .tint(Palette.error)
                                }
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
        if styleB, let root = libraryRoot {
            headerItemsB(state, root)
        } else {
            headerItemsA(state)
        }
    }

    /// Design B (#449): the title row (the +, then the ⋮ menu with Select and Settings), Today's card, the
    /// views shelf, search with its filter button, the pills of the filters on, and the count line (the
    /// count, the sort, then the rows / grid toggle). While selecting, the pinned header is the whole top.
    @ViewBuilder
    private func headerItemsB(_ state: RecipesUiState, _ root: LibraryRoot) -> some View {
        let emptyLibrary = state.view == nil && root.vm.uiState.libraryEmpty
        if !state.selecting {
            VStack(alignment: .leading, spacing: 12) {
                LibraryTitleRowB(
                    onAdd: { addSheetOpen = true },
                    onSelect: multiSelect && state.canSelect ? { vm.onStartSelecting() } : nil,
                    onOpenSettings: root.onOpenSettings
                )
                // Gone once its foot passes the top of the scroll view; reported as it crosses, so a row the
                // list recycles off screen keeps the last answer.
                .onGeometryChange(for: Bool.self, of: { $0.frame(in: .scrollView).maxY < 0 }) { titleGone = $0 }
                LibraryCard(state: root.vm.uiState, root: root, onOpenRecipe: onOpenRecipe, styleB: true)
                if emptyLibrary {
                    EmptyLibraryView(
                        onPasteLink: vm.onPasteLink, onScan: onScan, onTypeRecipe: onNewRecipe,
                        onAskAi: askAiAction(root), restore: root.vm.uiState.restore,
                        onRestore: root.vm.canRestore ? { restoring = true } : nil
                    )
                } else {
                    if state.showsViewsStrip {
                        ViewsShelf(counts: state.counts, listCount: state.lists.count, onOpenView: onOpenView, onOpenLists: onOpenLists)
                    }
                    VStack(alignment: .leading, spacing: 4) {
                        SearchFieldB(
                            query: state.query, onQueryChange: vm.onQueryChange,
                            filtersOn: state.activeQuickFilters.count,
                            onOpenFilters: quickFilters && state.showsFilter ? { filterSheetOpen = true } : nil
                        )
                        ActiveFilterPills(active: state.activeQuickFilters, onRemove: vm.onQuickFilterToggle, onClear: vm.onClearQuickFilters)
                    }
                }
            }
            .padding(.top, 8)
            .padding(.bottom, emptyLibrary || !vm.showsSortLine ? 12 : 0)
            .listRowSeparator(.hidden)
        }
        if !emptyLibrary {
            if vm.showsSortLine && !state.selecting {
                sortLine(state, toggleLast: true)
                    .listRowSeparator(.hidden)
            }
            if let recipes = state.recipes, recipes.isEmpty {
                Text(emptyMessage(state))
                    .textStyle(Typography.bodyLarge)
                    .foregroundStyle(Palette.muted)
                    .listRowSeparator(.hidden)
            }
        }
    }

    @ViewBuilder
    private func headerItemsA(_ state: RecipesUiState) -> some View {
        // Nothing of the cook's yet (the sample, #151, aside): the ways in take the list's place (#375).
        let emptyLibrary = libraryRoot != nil && state.view == nil && (libraryRoot?.vm.uiState.libraryEmpty ?? false)
        VStack(alignment: .leading, spacing: 12) {
            if let root = libraryRoot {
                rootTitleRow(state, root)
            } else {
                ScreenTitle(state.view.map(RecipeFilterPills.name) ?? Strings.recipesTitle, style: Typography.headlineSmall)
            }
            if let view = state.view { ViewNote(view: view) }
            // Today's card (#375), above search, scrolling away with the list.
            if let root = libraryRoot, !state.selecting {
                LibraryCard(state: root.vm.uiState, root: root, onOpenRecipe: onOpenRecipe)
            }
            if emptyLibrary, let root = libraryRoot {
                EmptyLibraryView(
                    onPasteLink: vm.onPasteLink, onScan: onScan, onTypeRecipe: onNewRecipe,
                    onAskAi: askAiAction(root), restore: root.vm.uiState.restore,
                    onRestore: root.vm.canRestore ? { restoring = true } : nil
                )
            } else {
                SearchField(query: state.query, onQueryChange: vm.onQueryChange)
            }
        }
        .padding(.top, libraryRoot == nil ? 0 : 12)
        .padding(.bottom, !emptyLibrary && (vm.showsSortLine || state.showsFilter || (oneCookbook && state.showsViewsStrip)) ? 0 : 12)
        .listRowSeparator(.hidden)

        if !emptyLibrary {
            headerRest(state)
        }
    }

    /// The title with its gear and + (#375), and Select (#377) between them.
    private func rootTitleRow(_ state: RecipesUiState, _ root: LibraryRoot) -> some View {
        HStack(spacing: 4) {
            ScreenTitle(Strings.recipesTitle)
            if multiSelect && !state.selecting && state.canSelect {
                Button(Strings.select) { vm.onStartSelecting() }
                    .buttonStyle(TextActionStyle())
                    .accessibilityIdentifier("recipes.select")
            }
            LibraryTopActions(onOpenSettings: root.onOpenSettings, onAdd: { addSheetOpen = true })
        }
    }

    /// The Ask an AI entry (#351), with its sheet's ViewModel made on first use; nil with the flag off.
    private func askAiAction(_ root: LibraryRoot) -> (() -> Void)? {
        root.makeAskAiVM.map { make in
            {
                askAiVM = askAiVM ?? make()
                root.vm.onAskAi()
            }
        }
    }

    /// The views strip or pills, the chips, the sort line and what an empty list says.
    @ViewBuilder
    private func headerRest(_ state: RecipesUiState) -> some View {

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
        return { rowMenu.addToList([recipe], saveVM: saveVM) }
    }

    /// A tap: opens the recipe, or while selecting (#377) ticks it. The same in the rows and the grid.
    private func openOrToggle(_ recipe: RecipeSummary, _ state: RecipesUiState) {
        if state.selecting { vm.onToggleSelected(recipe.id) } else { onOpenRecipe(recipe.id) }
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
    private func sortLine(_ state: RecipesUiState, toggleLast: Bool = false) -> some View {
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
            if gridEnabled && !toggleLast { layoutToggle(state.layout) }
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
            // Design B (#449): the toggle last on the right, after the sort.
            if gridEnabled && toggleLast { layoutToggle(state.layout) }
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
        .background(RoundedRectangle(cornerRadius: Radius.field).fill(Palette.surfaceContainer))
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
        .background(RoundedRectangle(cornerRadius: Radius.field).fill(Palette.inverseSurface))
        .shadow(color: .black.opacity(0.15), radius: 6, y: 2)
    }
}
