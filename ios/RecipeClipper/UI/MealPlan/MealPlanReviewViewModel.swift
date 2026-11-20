import Combine
import Foundation
import Observation

/// One recipe of the review (#352). `day` is the day the cook has chosen to plan it on (an epoch
/// day), nil to leave it unplanned; `passed` says the answer's own day is already behind us, so
/// the row asks for another. `fits` is false for a kept recipe the free library has no room for.
/// With Plan my week's choices (#392, #367): `mealTypeId` is the meal type it is planned under, and
/// `existingId` is set for a recipe the answer named by its link that is already in the library: Save
/// plans that recipe, never a duplicate.
struct ReviewItem: Equatable, Identifiable {
    let index: Int
    let name: String
    /// The first ingredients, to tell recipes apart at a glance.
    let preview: String
    var day: Int64?
    let passed: Bool
    var ticked: Bool
    var fits = true
    var mealTypeId: Int64? = nil
    var existingId: Int64? = nil

    var id: Int { index }

    /// The recipe is one the cook has: Save only plans it.
    var existing: Bool { existingId != nil }

    /// Save does something with this row: a new recipe that fits, or an existing one with a day.
    var saves: Bool { ticked && (existing ? day != nil : fits) }
}

/// `room` is how many more recipes the free library holds, nil without a limit. `kept` counts the
/// recipes saved from this review so far, `planned` those of them put on a day.
struct MealPlanReviewUiState: Equatable {
    var loading = true
    var items: [ReviewItem] = []
    /// The answer stopped mid-recipe: only the whole recipes before it are listed.
    var cutOff = false
    /// The message for the AI was copied: the note says to paste it into the chat.
    var copied = false
    /// The days the pickers offer: today and the next 13.
    var days: [Int64] = []
    /// Plan my week's choices are on (#392): each row shows its meal, to change like its day.
    var meals = false
    /// The cook's meal types, for each row's meal picker.
    var mealTypes: [MealType] = []
    /// How many recipes the answer named by a link that matches nothing saved: left out, never made up (#367).
    var notFound = 0
    var room: Int?
    var saving = false
    var saveFailed = false
    var kept = 0
    var planned = 0
    /// Everything left was saved: the screen opens Week (when `planned`) or goes back.
    var finished = false
    /// A purchase from the review's Unlock that is pending or failed; shown until the next change.
    var unlockNotice: PurchaseOutcome?
    /// Fresh food (#396): the quiet notes, by `ReviewItem.index`; none with the flag off.
    var nudges: [Int: FreshNudge] = [:]

    var tickedCount: Int { items.filter(\.ticked).count }
    /// What Save does: the new recipes ticked that fit, and the ticked existing ones with a day.
    var savingCount: Int { items.filter(\.saves).count }
    /// Some ticked recipes won't be saved for want of room: the review says so and offers Unlock.
    var overLimit: Bool { items.contains { $0.ticked && !$0.fits } }
}

/// The review of a week of recipes shared in (#352; Android's `MealPlanReviewViewModel`): the
/// answer to "Plan my week" (#353) read once by `MealPlanImport`; a recipe at a time ticked (all
/// are at first), with its day to change or drop. **Nothing is saved before Save**; then each kept
/// recipe goes through the same `RecipeRepository.addManual` as a single chat recipe (CHAT,
/// `manual:<uuid>`, so the one `upsert` and its library limit decide) and is planned on its day
/// under Dinner.
///
/// The free limit (#107, #345): `room` says how many more the library holds (from `limit` and the
/// count, followed); the first ticked recipes that fit are saved, the rest stay listed, never at
/// the cost of anything old, and Unlock (a purchase) makes room. A day that has passed, or none,
/// leaves the recipe unplanned until the cook picks one.
@MainActor
@Observable
final class MealPlanReviewViewModel {
    private(set) var uiState = MealPlanReviewUiState()

    @ObservationIgnored private let recipes: RecipeRepository
    @ObservationIgnored private let plan: MealPlanRepository
    @ObservationIgnored private let limit: () -> LibraryLimit
    @ObservationIgnored private let entitlements: Entitlements
    /// Plan my week's choices (#392, #367) are on: nil/false reads the answer as before, dinners and new recipes.
    @ObservationIgnored private let meals: Bool
    /// What was read, by `ReviewItem.index`; the rows are these with the cook's choices.
    @ObservationIgnored private var read: [MealPlanImport.Item] = []
    @ObservationIgnored private var mealTypes: [MealType] = []
    @ObservationIgnored private var count = 0
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()
    @ObservationIgnored private let today: Int64
    /// Each recipe's most delicate perishable (#396), by `ReviewItem.index`; empty with the flag off.
    @ObservationIgnored private var perishables: [Int: Perishable] = [:]

    init(
        text: String, recipes: RecipeRepository, plan: MealPlanRepository, calendar: PlanCalendar,
        limit: @escaping () -> LibraryLimit = { .unlimited }, entitlements: Entitlements = UnavailableEntitlements(),
        freshFood: Bool = false, planPlus: Bool = false
    ) {
        self.recipes = recipes
        self.plan = plan
        self.limit = limit
        self.entitlements = entitlements
        self.meals = planPlus
        let today = calendar.today()
        self.today = today
        uiState.days = (0..<PlanDays.sheetDays).map { today + Int64($0) }
        let found = MealPlanImport.read(text)
        read = found?.items ?? []
        uiState.cutOff = found?.cutOff ?? false
        if freshFood {
            for (i, item) in read.enumerated() {
                if let found = Perishables.mostDelicate(item.recipe.ingredients, words: LanguageWords.forRecipe(item.recipe)) {
                    perishables[i] = found
                }
            }
        }
        if planPlus {
            // The meal types and, for recipes named by link, the library are read once before the rows show.
            uiState.meals = true
            Task { await loadRows(cutOff: found?.cutOff ?? false) }
        } else {
            // With the choices off a recipe named by its link is left out, as it always was.
            uiState.items = read.enumerated().filter { !$0.element.namesSavedRecipe }
                .map { Self.row($0.offset, $0.element, today: today) }
            uiState.loading = false
        }
        plan.observeMealTypes()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] types in self?.mealTypes = types }
            .store(in: &cancellables)
        recipes.observeCount()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] count in
                self?.count = count
                self?.refreshRoom()
            }
            .store(in: &cancellables)
        refreshRoom()
    }

    /// The rows with Plan my week's choices on: each meal mapped to a type by the name the prompt used
    /// (else Dinner), each recipe named by its link matched to the library or left out and counted.
    private func loadRows(cutOff: Bool) async {
        var types: [MealType] = []
        for await value in plan.observeMealTypes().values { types = value; break }
        var saved: [RecipeSummary] = []
        if read.contains(where: \.namesSavedRecipe) {
            for await value in recipes.observeHistory(query: "").values { saved = value; break }
        }
        var notFound = 0
        var rows: [ReviewItem] = []
        for (i, item) in read.enumerated() {
            if !item.namesSavedRecipe {
                rows.append(Self.row(i, item, today: today, types: types))
            } else if let match = SavedLink.find(item.link ?? "", in: saved) {
                rows.append(Self.row(i, item, today: today, types: types, existingId: match.id, existingTitle: match.title))
            } else {
                notFound += 1
            }
        }
        mealTypes = types
        uiState.items = rows
        uiState.mealTypes = types
        uiState.notFound = notFound
        uiState.loading = false
        fit()
    }

    private static func row(
        _ index: Int, _ item: MealPlanImport.Item, today: Int64, types: [MealType]? = nil,
        existingId: Int64? = nil, existingTitle: String? = nil
    ) -> ReviewItem {
        let passed = item.day.map { $0 < today } ?? false
        return ReviewItem(
            index: index, name: existingTitle ?? item.recipe.name,
            preview: item.recipe.ingredients.prefix(3).joined(separator: ", "),
            day: passed ? nil : item.day, passed: passed, ticked: true,
            // The meal the prompt listed, by the name it used; else Dinner (never guessed from the recipe).
            mealTypeId: types.flatMap { MealType.idForMeal(item.meal, $0) }, existingId: existingId
        )
    }

    /// How many more recipes the library holds under `limit` with `count` saved; nil: no limit.
    private static func room(_ limit: LibraryLimit, count: Int) -> Int? {
        switch limit {
        case .capped(let cap), .free(let cap): max(cap - count, 0)
        case .history, .unlimited: nil
        }
    }

    /// Reads the limit and the count again: the count changed, or a purchase did.
    private func refreshRoom() {
        uiState.room = Self.room(limit(), count: count)
        fit()
    }

    /// Each ticked row marked as fitting or not (the first `room` do), and the plan's quiet notes
    /// (#396) worked out again from the days the rows now hold.
    private func fit() {
        var left = uiState.room
        for i in uiState.items.indices {
            let row = uiState.items[i]
            let remaining = left
            // A recipe the cook already has takes no room.
            let fits = row.existing || !row.ticked || remaining == nil || remaining! > 0
            if !row.existing, row.ticked, let remaining, remaining > 0 { left = remaining - 1 }
            uiState.items[i].fits = fits
        }
        uiState.nudges = nudgesOf(uiState.items)
    }

    private func nudgesOf(_ items: [ReviewItem]) -> [Int: FreshNudge] {
        if perishables.isEmpty { return [:] }
        let meals = items.compactMap { item in
            item.ticked ? item.day.map { FreshMeal(key: Int64(item.index), day: $0, perishable: perishables[item.index]) } : nil
        }
        return Dictionary(uniqueKeysWithValues: FreshPlan.nudges(meals, today: today).map { (Int($0.key), $0) })
    }

    /// The cook tapped "Swap with ..." under a note (#396): this row and the one it names trade
    /// days. Only on the tap; nothing is saved before Save either.
    func onSwap(_ index: Int) {
        guard let nudge = uiState.nudges[index],
              let a = uiState.items.firstIndex(where: { $0.index == index }),
              let b = uiState.items.firstIndex(where: { $0.index == Int(nudge.swapKey) })
        else { return }
        uiState.items[a].day = nudge.swapDay
        uiState.items[b].day = nudge.day
        changed()
    }

    func onTick(_ index: Int) {
        guard let i = uiState.items.firstIndex(where: { $0.index == index }) else { return }
        uiState.items[i].ticked.toggle()
        changed()
    }

    /// A row's day: an epoch day, or nil to leave it unplanned.
    func onDay(_ index: Int, _ day: Int64?) {
        guard let i = uiState.items.firstIndex(where: { $0.index == index }) else { return }
        uiState.items[i].day = day
        changed()
    }

    /// A row's meal type (#392): the one it is planned under.
    func onMeal(_ index: Int, _ typeId: Int64) {
        guard let i = uiState.items.firstIndex(where: { $0.index == index }) else { return }
        uiState.items[i].mealTypeId = typeId
        changed()
    }

    private func changed() {
        uiState.saveFailed = false
        uiState.unlockNotice = nil
        fit()
    }

    func onSave() {
        let state = uiState
        guard !state.loading, !state.saving, state.savingCount > 0 else { return }
        uiState.saving = true
        uiState.saveFailed = false
        Task { await save(state.items.filter { $0.ticked && ($0.existing || $0.fits) }) }
    }

    private func save(_ rows: [ReviewItem]) async {
        let mealType = MealType.defaultTypeId(mealTypes)
        var kept = 0
        var planned = 0
        var failed = false
        var saved = Set<Int>()
        for row in rows {
            let type = row.mealTypeId ?? mealType
            if let existing = row.existingId {
                // Already in the library (#367): planned on its day and meal, never saved again.
                guard let day = row.day else { saved.insert(row.index); continue }
                if let type, await plan.addRecipe(recipeId: existing, day: day, mealTypeId: type, servings: nil) {
                    saved.insert(row.index)
                    kept += 1
                    planned += 1
                } else {
                    failed = true
                }
                continue
            }
            let item = read[row.index]
            guard let recipe = await recipes.addManual(
                draft: RecipeDraft.of(item.recipe), language: item.recipe.language, notes: nil, fromChat: true
            ) else { failed = true; continue }
            // Id 0: the one upsert did not keep it (no room). It stays in the review.
            if recipe.id == 0 { continue }
            saved.insert(row.index)
            kept += 1
            if let day = row.day, let type,
               await plan.addRecipe(recipeId: recipe.id, day: day, mealTypeId: type, servings: nil) { planned += 1 }
        }
        let left = uiState.items.filter { !saved.contains($0.index) }
        uiState.saving = false
        uiState.saveFailed = failed
        uiState.items = left
        uiState.kept += kept
        uiState.planned += planned
        uiState.finished = !left.contains { $0.ticked } && !failed
        fit()
    }

    /// Unlock from the review (#107): a purchase makes room, which the limit and count then say.
    /// The message that asks the AI for the rest of a cut-off week (#352): the days the answer's
    /// whole recipes leave out (`CutOffMessage`), in the UI language's `words`. The screen copies it.
    func followUp(_ words: CutOffMessage.Words) -> String {
        meals
            ? CutOffMessage.forPlanMeals(read.map { (day: $0.day, meal: $0.meal) }, words)
            : CutOffMessage.forPlan(read.map(\.day), words)
    }

    /// The screen copied `followUp`: the note says to paste it into the chat.
    func onCopied() { uiState.copied = true }

    func onUnlock() {
        Task {
            let outcome = await entitlements.purchase()
            if outcome.needsNotice { uiState.unlockNotice = outcome }
            refreshRoom()
        }
    }
}
