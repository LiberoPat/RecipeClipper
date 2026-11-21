import Combine
import Foundation
import Observation

/// `recipes` is nil until the database has answered, to tell "loading" from "nothing
/// matched". `pendingDeletes` holds the titles swiped away but not yet settled, newest last,
/// for the undo snackbar, and `deleteBatch` names the burst they belong to (nil when none is
/// pending): the snackbar's timeout is keyed on it, so further swipes join one snackbar (#268).
/// `linkInput` is the "Paste a link" alert's text, shown while
/// `pastingLink`. `filter` is All / In a list / Not in a list (#345), applied by the query beside
/// the search; `counts` are the library's, whatever the filter or search, nil until the
/// database answers. `view` is the automatic view the screen was opened on (V4: Not in a list,
/// Cooked, Typed by you), nil for the whole cookbook; `lists` are the lists the strip shows
/// after the views (#345). `quick` are the chips on under search (#378: 30 min or less, Not cooked yet,
/// Uses what's in my pantry), combined with each other, the search and the sort; not remembered.
/// `coverage` is, with the pantry chip on, how many of each recipe's nameable ingredients the pantry
/// has ("You have 6 of 8"), by recipe id. `layout` is the stored choice of rows or a photo grid (#380).
struct RecipesUiState: Equatable {
    var query = ""
    var recipes: [RecipeSummary]?
    var pendingDeletes: [String] = []
    var deleteBatch: Int?
    var sort: RecipeSort = .recentlyViewed
    var pastingLink = false
    var linkInput = ""
    /// Go found nothing to open in the pasted text (#372); cleared by the next keystroke.
    var linkNoRecipe = false
    /// Every recipe saved, whatever the query (#107: the free tier's count).
    var savedCount: Int?
    var filter: RecipeFilter = .all
    var counts: RecipeCounts?
    var view: RecipeFilter?
    var lists: [RecipeList] = []
    /// An Undo was refused, the free library being full (#345): the screen says so once.
    var restoreRefused = false
    var quick: Set<RecipeQuickFilter> = []
    var coverage: [Int64: PantryCoverage] = [:]
    var layout: RecipesLayout = .rows

    /// The chips offered: Not cooked yet is no question inside the Cooked view, which holds only
    /// cooked recipes.
    var quickFilters: [RecipeQuickFilter] {
        RecipeQuickFilter.allCases.filter { $0 != .notCooked || view != .cooked }
    }

    /// Selecting several recipes (#377): whether the screen is, and the ticked recipes that are listed now
    /// (a search never leaves a hidden one for an action to take).
    var selecting = false
    var selectedIds: Set<Int64> = []
    /// The recipes a Delete (a row's menu, or the selection's) asks to confirm; empty when none.
    var confirmingDelete: [RecipeSummary] = []

    /// The top bar's Select shows once there is a row to select.
    var canSelect: Bool { !(recipes?.isEmpty ?? true) }

    /// The strip shows once there is a recipe or a list to show (V4), never inside a view.
    var showsViewsStrip: Bool { view == nil && ((counts?.all ?? 0) > 0 || !lists.isEmpty) }

    /// The alert's Go is enabled only for what reads as a link (`UrlInput`).
    var canOpenLink: Bool { !linkInput.kIsBlank }

    /// How many rows are listed (the matches, while searching); nil until the database answers.
    var listed: Int? { recipes?.count }

    /// The filter shows once there is a recipe to filter, and stays when a filter matches none.
    var showsFilter: Bool { (counts?.all ?? 0) > 0 }
}

@MainActor
@Observable
final class RecipesViewModel {
    static let debounce = Duration.milliseconds(250)

    private(set) var uiState = RecipesUiState()

    @ObservationIgnored private let repository: RecipeRepository
    @ObservationIgnored private let preferences: AppPreferences
    @ObservationIgnored private var sortSubscription: AnyCancellable?
    @ObservationIgnored private let sleep: Sleep
    @ObservationIgnored private var debounceTask: Task<Void, Never>?
    @ObservationIgnored private var appliedQuery: String?
    @ObservationIgnored private var appliedFilter: RecipeFilter?
    @ObservationIgnored private var countsSubscription: AnyCancellable?
    @ObservationIgnored private var historySubscription: AnyCancellable?
    @ObservationIgnored private var countSubscription: AnyCancellable?
    @ObservationIgnored private var listsSubscription: AnyCancellable?
    @ObservationIgnored private var layoutSubscription: AnyCancellable?
    @ObservationIgnored private var coverageSubscription: AnyCancellable?
    @ObservationIgnored private var coverageTask: Task<Void, Never>?
    /// "Uses what's in my pantry" (#378); without it the chip matches nothing.
    @ObservationIgnored private let pantry: PantryRepository?
    /// How each recipe's ingredients stand against the pantry, nil while that is being worked out.
    @ObservationIgnored private var coverage: [Int64: PantryCoverage]?
    @ObservationIgnored private var coverageInputs: CoverageInputs?
    /// How many times the pantry was matched against every recipe; the tests read it (#378).
    @ObservationIgnored private(set) var coverageMatchings = 0

    /// What the coverage is matched from; the same again is skipped, so a tick or a cook step
    /// (which rewrite a recipe without changing a line) match nothing again.
    private struct CoverageInputs: Equatable {
        let lines: [SavedRecipe]
        let items: [PantryItem]
    }
    @ObservationIgnored private let library: LibraryPolicy?
    /// Recently cooked (#116) only while its flag is on; stored, it reads as the default.
    @ObservationIgnored private let cookedSort: Bool
    var showsCookedSort: Bool { cookedSort }
    /// The countdowns outside the app (#309), squared with the database after a swipe's delete
    /// (#333); nil without them (tests that aren't about them).
    @ObservationIgnored private let countdowns: TimerCountdownSync?
    // "Paste a link" takes any text (#372): where it goes is the shared-text route's; nil opens links only.
    @ObservationIgnored private let pasted: PastedTextRouter?
    /// What the database last answered, in its own order, so a new sort needs no new query.
    @ObservationIgnored private var found: [RecipeSummary]?

    // The deletes not yet settled, for the one Undo snackbar; the selection and a row's menu (#376, #377).
    @ObservationIgnored private let deletions: RecipeDeletions
    @ObservationIgnored private let rowSupport: RecipeRowSupport
    @ObservationIgnored private var selection = RecipeSelection()

    init(
        repository: RecipeRepository, preferences: AppPreferences, sleep: @escaping Sleep = Sleeps.real,
        library: LibraryPolicy? = nil, cookedSort: Bool = false, countdowns: TimerCountdownSync? = nil,
        view: RecipeFilter? = nil, lists: ListRepository? = nil, pasted: PastedTextRouter? = nil,
        pantry: PantryRepository? = nil,
        decisions: DecisionRepository? = nil
    ) {
        deletions = RecipeDeletions(repository: repository, countdowns: countdowns)
        rowSupport = RecipeRowSupport(repository: repository, preferences: preferences, decisions: decisions)
        self.pasted = pasted
        self.pantry = pantry
        self.cookedSort = cookedSort
        self.countdowns = countdowns
        self.repository = repository
        self.preferences = preferences
        self.sleep = sleep
        self.library = library
        // Remembered in AppPreferences, so it survives leaving the screen; read now so the
        // first frame is right, then kept in step with what is stored.
        uiState.sort = Self.shown(preferences.recipeSort, cookedSort: cookedSort)
        // Opened on an automatic view (#345): its recipes only, from the first query.
        let opened = view.flatMap { RecipeFilter.views.contains($0) ? $0 : nil }
        uiState.view = opened
        uiState.filter = opened ?? .all
        sortSubscription = preferences.settings
            .map { [cookedSort] in Self.shown($0.recipeSort, cookedSort: cookedSort) }
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] sort in self?.applySort(sort) }
        uiState.layout = preferences.recipesLayout
        layoutSubscription = preferences.settings
            .map(\.recipesLayout)
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] layout in self?.uiState.layout = layout }
        deletions.onChange = { [weak self] in self?.syncDeletions() }
        schedule(query: "")
        countsSubscription = repository.observeRecipeCounts()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] counts in self?.uiState.counts = counts }
        listsSubscription = lists?.observeLists()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] lists in self?.uiState.lists = lists }
        if library != nil {
            countSubscription = repository.observeCount()
                .receive(on: DispatchQueue.main)
                .sink { [weak self] count in self?.uiState.savedCount = count }
        }
    }

    /// "12 of 50 recipes" (#107, #345), on the free tier only: unlocked, or with the flag off, there
    /// is nothing to count against. Reads the observable policy, so the view follows it.
    var count: (saved: Int, max: Int)? {
        guard let saved = uiState.savedCount else { return nil }
        switch library?.limit {
        case .free(let max), .capped(let max): return (saved, max)
        default: return nil
        }
    }

    /// The line under search, with the count and the sort (#286): shown once there is a row to
    /// order, or on the free tier, whose count shows even for an empty library.
    var showsSortLine: Bool { count != nil || !(uiState.recipes?.isEmpty ?? true) || !uiState.quick.isEmpty }

    /// The cap on recipes in no list (the old history's 50) while it applies, nil when nothing
    /// clears on its own (the free tier and an unlocked library have their own rules).
    var cullKeep: Int? {
        guard case .history(let keep) = library?.limit else { return nil }
        return keep
    }

    /// The quiet line saying recipes in no list are cleared past `cullKeep` (#345): only under
    /// "Not in a list", only where that clean-up applies, and only with a row to read it over.
    var showsCullNote: Bool {
        cullKeep != nil && uiState.filter == .notInAList && !(uiState.recipes?.isEmpty ?? true)
    }

    func onQueryChange(_ text: String) {
        uiState.query = text
        schedule(query: text)
    }

    /// Debounce, then drop a repeat of the query already applied, then switch to the latest
    /// query's results (the old subscription is cancelled) — Android's
    /// `debounce(250).distinctUntilChanged().flatMapLatest`.
    private func schedule(query: String) {
        debounceTask?.cancel()
        debounceTask = Task { [weak self, sleep] in
            do { try await sleep(Self.debounce) } catch { return }
            guard !Task.isCancelled, let self,
                  query != self.appliedQuery || self.uiState.filter != self.appliedFilter else { return }
            self.subscribe(query: query)
        }
    }

    /// The latest query and filter's results; the old subscription is cancelled.
    private func subscribe(query: String) {
        let filter = uiState.filter
        appliedQuery = query
        appliedFilter = filter
        historySubscription = repository.observeHistory(query: query, filter: filter)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] recipes in
                guard let self else { return }
                self.found = recipes
                self.refreshShown()
            }
    }

    /// A tap on a filter answers at once, with the query already applied; the debounce is the
    /// typing's alone. A query still waiting out its debounce picks the filter up when it fires.
    func onFilterChange(_ filter: RecipeFilter) {
        guard filter != uiState.filter else { return }
        uiState.filter = filter
        if let appliedQuery { subscribe(query: appliedQuery) }
    }

    /// Stored at once and applied at once, rather than waiting for `settings` to echo it.
    func onSortChange(_ sort: RecipeSort) {
        preferences.recipeSort = sort
        applySort(sort)
    }

    private func applySort(_ sort: RecipeSort) {
        guard sort != uiState.sort else { return }
        uiState.sort = sort
        refreshShown()
    }

    /// The search's matches, narrowed by the chips, then in the sort's order. Nothing is shown while
    /// the pantry chip's answer is still on its way, rather than a wrong "no recipes" for a moment.
    private func refreshShown() {
        let chips = uiState.quick
        uiState.coverage = chips.contains(.usesPantry) ? (coverage ?? [:]) : [:]
        guard let found else { return }
        if chips.contains(.usesPantry) && coverage == nil {
            uiState.recipes = nil
            syncSelection()
            return
        }
        uiState.recipes = Self.sorted(
            RecipeQuickFilters.apply(found, filters: chips, coverage: coverage ?? [:]), uiState.sort
        )
        // The ticks an action acts on are the rows still in sight, chips and all (#377).
        syncSelection()
    }

    /// A chip (#378): on, or off again. Each answers at once; the chips combine.
    func onQuickFilterToggle(_ filter: RecipeQuickFilter) {
        if uiState.quick.contains(filter) { uiState.quick.remove(filter) } else { uiState.quick.insert(filter) }
        if filter == .usesPantry {
            if uiState.quick.contains(.usesPantry) { startCoverage() } else { stopCoverage() }
        }
        refreshShown()
    }

    /// The rows / grid toggle (#380): stored at once and applied at once.
    func onLayoutChange(_ layout: RecipesLayout) {
        preferences.recipesLayout = layout
        uiState.layout = layout
    }

    /// The lines of every recipe are read only while the pantry chip is on.
    private func startCoverage() {
        coverage = nil
        coverageInputs = nil
        guard let pantry else { coverage = [:]; return }
        coverageSubscription = repository.observeRecipeLines()
            .combineLatest(pantry.observeItems())
            .receive(on: DispatchQueue.main)
            .sink { [weak self] lines, items in self?.matchCoverage(lines, items) }
    }

    private func stopCoverage() {
        coverageSubscription = nil
        coverageTask?.cancel()
        coverage = nil
        coverageInputs = nil
    }

    /// Matches the pantry against every recipe off the main actor, unless nothing it's made from changed.
    private func matchCoverage(_ lines: [SavedRecipe], _ items: [PantryItem]) {
        let inputs = CoverageInputs(lines: lines, items: items)
        guard inputs != coverageInputs else { return }
        coverageInputs = inputs
        coverageMatchings += 1
        coverageTask?.cancel()
        coverageTask = Task { [weak self] in
            let result = await Task.detached(priority: .userInitiated) {
                RecipeQuickFilters.coverages(inputs.lines, pantry: inputs.items)
            }.value
            guard !Task.isCancelled, let self, self.uiState.quick.contains(.usesPantry) else { return }
            self.coverage = result
            self.refreshShown()
        }
    }

    private func syncDeletions() {
        let state = deletions.state
        uiState.pendingDeletes = state.pendingDeletes
        uiState.deleteBatch = state.deleteBatch
        uiState.restoreRefused = state.restoreRefused
    }

    private func syncSelection() {
        uiState.selecting = selection.isSelecting
        uiState.selectedIds = selection.effective(in: uiState.recipes)
    }

    /// "Paste a link" from the + menu: opens the alert, empty.
    func onPasteLink() {
        uiState.linkInput = ""
        uiState.pastingLink = true
    }

    func onLinkChange(_ text: String) {
        uiState.linkInput = text
        uiState.linkNoRecipe = false
    }

    /// The alert went away by itself. Only the flag is cleared, as with a list's rename:
    /// SwiftUI may run Go's action before or after resetting the binding.
    func onLinkDismissed() {
        uiState.pastingLink = false
        uiState.linkNoRecipe = false
    }

    /// Go (#372): a link imports, any other text goes where the same text shared would (the
    /// editor, the meal-plan review, Groceries' list sheet). Returns where, closing the sheet;
    /// nil, and the sheet stays saying so, with nothing to open. Nothing is saved here.
    func onOpenLink() -> PasteTarget? {
        let text = uiState.linkInput
        guard !text.kIsBlank else { return nil }
        guard let target = pasted?.open(text) ?? UrlInput.normalize(text).map(PasteTarget.link) else {
            uiState.linkNoRecipe = true
            return nil
        }
        uiState.pastingLink = false
        uiState.linkInput = ""
        return target
    }

    /// `recipes`, which the database gives newest viewed first, in `sort`'s order. Name follows
    /// the phone's language; Date added is newest first by id, which only ever grows
    /// (AUTOINCREMENT). Ties keep recency. Android's `RecipesViewModel.sorted`.
    private static func shown(_ sort: RecipeSort, cookedSort: Bool) -> RecipeSort {
        sort == .recentlyCooked && !cookedSort ? .recentlyViewed : sort
    }

    static func sorted(_ recipes: [RecipeSummary], _ sort: RecipeSort) -> [RecipeSummary] {
        switch sort {
        case .recentlyViewed:
            return recipes
        case .name:
            return recipes.enumerated().sorted { a, b in
                let order = a.element.title.localizedCompare(b.element.title)
                return order == .orderedSame ? a.offset < b.offset : order == .orderedAscending
            }.map(\.element)
        case .dateAdded:
            return recipes.sorted { $0.id > $1.id }
        case .recentlyCooked:
            // #116: cooked ones first, latest cook first; the rest keep recency after them.
            return recipes.enumerated().sorted { a, b in
                let x = a.element.lastCookedDay ?? Int64.min, y = b.element.lastCookedDay ?? Int64.min
                return x == y ? a.offset < b.offset : x > y
            }.map(\.element)
        }
    }

    /// Swipe-to-delete: deletes immediately, keeping what it removed so `onUndoDelete` can
    /// restore it.
    func onDelete(_ recipe: RecipeSummary) {
        Task { await deletions.delete([recipe]) }
    }

    /// Restores everything still pending, oldest first. All or nothing: one snackbar covers
    /// the batch, so there is no way to pick one out of it. On the free tier at its limit (#345)
    /// none comes back: `RecipesUiState.restoreRefused`.
    func onUndoDelete() { deletions.undo() }

    /// The screen has said the Undo was refused.
    func onRestoreRefusedShown() { deletions.restoreRefusedShown() }

    /// The snackbar timed out: the deletes stand.
    func onSnackbarDismissed() { deletions.settle() }

    // MARK: A row's menu and selecting several (#376, #377)

    /// Starts selecting (#377): a row's menu begins with that row ticked, the top bar's Select with none.
    func onStartSelecting(_ recipe: RecipeSummary? = nil) {
        selection.start(recipe?.id)
        syncSelection()
    }

    func onToggleSelected(_ id: Int64) {
        selection.toggle(id)
        syncSelection()
    }

    /// Done, or Back: leaves selecting, dropping the ticks.
    func onExitSelection() {
        selection.exit()
        syncSelection()
    }

    /// Asks to confirm deleting `recipes`: a row's menu passes its row, the selection's Delete the ticked ones.
    func onRequestDelete(_ recipes: [RecipeSummary]) {
        uiState.confirmingDelete = recipes
    }

    func onCancelDelete() { uiState.confirmingDelete = [] }

    /// The confirmation's Delete: every recipe in one transaction, one batch, one Undo (#345's
    /// free-tier rule applies to it); selecting ends.
    func onConfirmDelete() {
        let recipes = uiState.confirmingDelete
        uiState.confirmingDelete = []
        selection.exit()
        syncSelection()
        Task { await deletions.delete(recipes) }
    }

    /// The selected recipes, in the order listed: what the selection's actions take.
    var selectedRecipes: [RecipeSummary] {
        (uiState.recipes ?? []).filter { uiState.selectedIds.contains($0.id) }
    }

    /// A row's Share: the recipe as the reading view would send it, at its saved servings and the
    /// user's units. `labels` come from the screen's strings. Nil when the recipe is gone.
    func onShare(recipeId: Int64, labels: RecipeShareText.Labels = .english) async -> (title: String, text: String)? {
        await rowSupport.shareText(id: recipeId, labels: labels)
    }

    /// A row's Add to plan starts from the recipe's own yield (nil when it has no number).
    func yieldServings(recipeId: Int64) async -> Int? {
        await rowSupport.yieldServings(id: recipeId)
    }
}

/// How long the undo snackbar stays, and what settles the batch when it goes. Run from the
/// screen's `.task(id: deleteBatch)`, so it runs once per burst, from its first swipe, and is
/// cancelled when the screen goes away; Android's `LaunchedEffect(deleteBatch)` around
/// `showSnackbar` (#268).
///
/// On cancellation neither outcome runs — no undo, no dismissal — so leaving the screen neither
/// restores anything nor forgets it (coming back re-shows the snackbar, as on Android; popping
/// History drops the ViewModel and with it the captures, so the deletes stand).
enum SnackbarTimeout {
    static let duration = Duration.seconds(4)
    /// An Undo snackbar's window: longer than a notice, so a cook mid-shop can still reach Undo.
    static let undo = Duration.seconds(10)

    @MainActor
    static func run(pending: [String], for window: Duration = undo, sleep: Sleep = Sleeps.real, onTimeout: () -> Void) async {
        guard !pending.isEmpty else { return }
        do { try await sleep(window) } catch { return }
        guard !Task.isCancelled else { return }
        onTimeout()
    }
}
