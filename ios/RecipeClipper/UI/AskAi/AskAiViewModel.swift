import Combine
import Foundation
import Observation

/// Which prompt Copy and Share send (#351, #353): the cook's own words, or one of the ready ones
/// filled from the app's data. Choosing a ready one leaves the field for extra wishes.
enum AskAiKind: Equatable {
    case describe, whatCanIMake, planMyWeek
}

/// A list the plan can take its recipes from (#367): its id and name as the cook sees it.
struct PlanListChoice: Equatable {
    let id: Int64
    let name: String
}

/// Why "Paste the answer" opened nothing (#372): the clipboard was empty, or held no recipe.
enum PasteProblem: Equatable { case empty, noRecipe }

/// `request` is the field's text. `pantryCount` counts what is in stock, `useUpCount` the items to
/// use up first, `favouriteCount` and `cookedCount` what "Plan my week" draws on. `copied` is true
/// after Copy until anything changes. Android's `AskAiUiState`.
struct AskAiUiState: Equatable {
    var request = ""
    var kind: AskAiKind = .describe
    var pantryCount = 0
    var useUpCount = 0
    var favouriteCount = 0
    var cookedCount = 0
    var copied = false
    /// What "Paste the answer" found wrong (#372), until anything changes; nil when nothing.
    var pasteProblem: PasteProblem?
    /// Plan my week with choices (#392, #367), only with the `planMyWeekPlus` flag (`plan` false: none of
    /// what follows is shown or used, and the plan is today's week of dinners).
    var plan = false
    /// The cook's meal types, in their order, and which are ticked (by `MealType.key`).
    var mealTypes: [MealType] = []
    var chosenMeals: Set<String> = []
    /// The start-day strip: today and the next 13, and the one chosen (default today).
    var days: [Int64] = []
    var today: Int64 = 0
    var startDay: Int64 = 0
    var source: AskAiPrompt.PlanSource = .new
    /// A mix: how many of the cook's recipes at most, 1 to the week's meals.
    var mix = 1
    /// The lists holding a recipe, to take the plan's recipes from instead of all of them.
    var planLists: [PlanListChoice] = []
    var listId: Int64?
    /// How many recipes the chosen scope offers (`recipesSent` of them are named in the prompt).
    var recipeCount = 0
    var useFavourites = true
    var skipCooked = true
    /// Many meals to write out: chat apps may cut the answer off.
    var longAnswer = false

    /// The week's meals: every chosen meal on each of `AskAiPrompt.planDays` days.
    var planSlots: Int { AskAiPrompt.planDays * chosenMeals.count }
    /// How many of `recipeCount` the prompt names (the most recently viewed).
    var recipesSent: Int { min(recipeCount, AskAiPrompt.maxCandidates) }
    /// Plan my week asks the AI to name the cook's recipes (From my recipes, or a mix).
    var namesRecipes: Bool { source != .new }
    /// Plan my week has everything it needs: a meal, and recipes to name where it names them.
    var canPlan: Bool { !plan || (!chosenMeals.isEmpty && (!namesRecipes || recipeCount > 0)) }

    /// "What can I make?" needs something in the pantry to make it from.
    var canAskWhatCanIMake: Bool { pantryCount > 0 }

    /// Copy and Share are on: the cook's own words need words; a ready prompt needs its data.
    var canSend: Bool {
        switch kind {
        case .describe: !request.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .whatCanIMake: canAskWhatCanIMake
        case .planMyWeek: canPlan
        }
    }

    /// "Change this recipe" needs the cook's request.
    var canSendChange: Bool { !request.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

/// "Ask an AI" (#351, #353): builds the prompt to copy or share. No AI runs in the app. The prompt
/// is `AskAiPrompt`'s; the screen hands over its words (read from the string catalog), and the
/// Settings units and the data come from here. Everything a prompt reads is followed by this
/// ViewModel itself, so `prompt(words:)` is right whenever it is called. Android's `AskAiViewModel`.
///
/// With the `planMyWeekPlus` flag (#392, #367) "Plan my week" takes choices: the meal types (ticked by
/// `MealType.key`, the last choice remembered in `PlanChoices`, Dinner at first), a start day (today,
/// never remembered), where the recipes come from (new, the cook's own, a mix), and which of
/// favourites and cooked-lately go in. A choice that names a deleted type or list falls back, so
/// the sheet never offers what is gone.
@MainActor
@Observable
final class AskAiViewModel {
    private(set) var uiState = AskAiUiState()

    @ObservationIgnored private let clock: Clock
    // "Paste the answer" (#372) goes where a shared text would; nil opens nothing.
    @ObservationIgnored private let pasted: PastedTextRouter?
    // Plan my week with choices (#392, #367): what was chosen last, and the flag.
    @ObservationIgnored private let choices: PlanChoices?
    @ObservationIgnored private let planPlus: () -> Bool
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()
    @ObservationIgnored private var planInput = PlanInput()
    @ObservationIgnored private var mealTypes: [MealType] = []
    @ObservationIgnored private var everyRecipe: [AskAiPrompt.PlanCandidate] = []
    @ObservationIgnored private var allLists: [RecipeList] = []
    @ObservationIgnored private var listRecipes: [AskAiPrompt.PlanCandidate] = []
    @ObservationIgnored private let listChoice = CurrentValueSubject<Int64?, Never>(nil)

    /// What the cook chose in the plan part of the sheet; nil where nothing was touched yet.
    private struct PlanInput {
        var meals: Set<String>?
        var startDay: Int64?
        var source: AskAiPrompt.PlanSource = .new
        var mix = AskAiViewModel.defaultMix
        var listId: Int64?
        var useFavourites = true
        var skipCooked = true
    }

    /// A mix starts with this many of the cook's recipes (kept to the week's meals).
    static let defaultMix = 3
    /// At most this many meal types can be ticked: the reader keeps `MealPlanImport.maxRecipes` recipes.
    static let maxMeals = MealPlanImport.maxRecipes / AskAiPrompt.planDays
    @ObservationIgnored private var pantry: [PantryItem] = []
    @ObservationIgnored private var favourites: [String] = []
    @ObservationIgnored private var cooked: [String] = []
    @ObservationIgnored private var settings: AppSettings

    init(
        pantry: PantryRepository, lists: ListRepository, recipes: RecipeRepository, preferences: AppPreferences,
        clock: Clock = SystemClock(), pasted: PastedTextRouter? = nil,
        mealPlan: MealPlanRepository? = nil, choices: PlanChoices? = nil, planPlus: @escaping () -> Bool = { false }
    ) {
        self.clock = clock
        self.pasted = pasted
        self.choices = choices
        self.planPlus = planPlus
        settings = preferences.current
        pantry.observeItems()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] items in
                self?.pantry = items
                self?.refreshCounts()
            }
            .store(in: &cancellables)
        // The favourites list's recipes, by title as the list orders them (most recently added first).
        lists.observeLists()
            .map { all in all.first(where: \.isFavorites)?.id }
            .removeDuplicates()
            .map { id -> AnyPublisher<[String], Never> in
                guard let id else { return Just([]).eraseToAnyPublisher() }
                return lists.observeRecipesIn(listId: id).map { $0.map(\.title) }.eraseToAnyPublisher()
            }
            .switchToLatest()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] titles in
                self?.favourites = titles
                self?.refreshCounts()
            }
            .store(in: &cancellables)
        // The recipes the cook marked cooked (#116, #173), the latest first.
        // Every recipe, as the plan can name it: most recently viewed first, never the tour's sample.
        recipes.observeHistory(query: "")
            .map { AskAiPrompt.candidates($0) }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] candidates in
                self?.everyRecipe = candidates
                self?.refreshCounts()
            }
            .store(in: &cancellables)
        lists.observeLists()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] all in
                self?.allLists = all
                self?.refreshCounts()
            }
            .store(in: &cancellables)
        // The recipes of the list the cook chose to plan from, the same way; none while none is chosen.
        listChoice.removeDuplicates()
            .map { id -> AnyPublisher<[AskAiPrompt.PlanCandidate], Never> in
                guard let id else { return Just([]).eraseToAnyPublisher() }
                return lists.observeRecipesIn(listId: id).map { AskAiPrompt.candidates($0) }.eraseToAnyPublisher()
            }
            .switchToLatest()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] candidates in
                self?.listRecipes = candidates
                self?.refreshCounts()
            }
            .store(in: &cancellables)
        if let mealPlan {
            mealPlan.observeMealTypes()
                .receive(on: DispatchQueue.main)
                .sink { [weak self] types in
                    self?.mealTypes = types
                    self?.refreshCounts()
                }
                .store(in: &cancellables)
        }
        recipes.observeHistory(query: "")
            .map { rows in
                rows.filter { $0.lastCookedDay != nil }
                    .sorted { ($0.lastCookedDay ?? 0) > ($1.lastCookedDay ?? 0) }
                    .prefix(AskAiPrompt.maxTitles)
                    .map(\.title)
            }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] titles in
                self?.cooked = titles
                self?.refreshCounts()
            }
            .store(in: &cancellables)
        preferences.settings
            .receive(on: DispatchQueue.main)
            .sink { [weak self] settings in self?.settings = settings }
            .store(in: &cancellables)
    }

    /// The types ticked last time: until a choice is made now (`PlanInput.meals`), and again once the sheet is closed.
    private var remembered: [String]? { choices?.planMealKeys }

    /// Ticks or unticks the meal type `key` (`MealType.key`) for Plan my week (#392). The choice is
    /// remembered at once, in the order of the cook's types; no more than `maxMeals` can be ticked.
    /// Unticking the last one is allowed (the sheet then asks for a meal).
    func onMeal(_ key: String) {
        let now = MealType.chosenKeys(mealTypes, planInput.meals.map(Array.init) ?? remembered)
        let next: Set<String>
        if now.contains(key) {
            next = now.subtracting([key])
        } else if now.count >= Self.maxMeals {
            next = now
        } else {
            next = now.union([key])
        }
        planInput.meals = next
        choices?.planMealKeys = mealTypes.map(\.key).filter { next.contains($0) }
        planChanged()
    }

    /// The first day of the plan: one of the strip's days, today by default; never remembered.
    func onStartDay(_ day: Int64) {
        planInput.startDay = day
        planChanged()
    }

    /// Where the plan's recipes come from: new ones, the cook's own, or a mix (#367).
    func onSource(_ source: AskAiPrompt.PlanSource) {
        planInput.source = source
        planChanged()
    }

    /// A mix: how many of the cook's recipes at most; the stepper keeps it between 1 and the week's meals.
    func onMix(_ count: Int) {
        planInput.mix = max(count, 1)
        planChanged()
    }

    /// The list the plan's recipes come from; nil is all of them.
    func onList(_ id: Int64?) {
        planInput.listId = id
        listChoice.send(id)
        planChanged()
    }

    func onUseFavourites(_ on: Bool) {
        planInput.useFavourites = on
        planChanged()
    }

    func onSkipCooked(_ on: Bool) {
        planInput.skipCooked = on
        planChanged()
    }

    private func planChanged() {
        uiState.copied = false
        uiState.pasteProblem = nil
        refreshCounts()
    }

    func onRequestChange(_ text: String) {
        uiState.request = text
        uiState.copied = false
        uiState.pasteProblem = nil
    }

    /// The prompt to send: the cook's own words, or a ready one.
    func onKind(_ kind: AskAiKind) {
        uiState.kind = kind
        uiState.copied = false
        uiState.pasteProblem = nil
    }

    /// The prompt was copied: the button says so until something changes.
    func onCopied() {
        uiState.copied = true
    }

    /// "Paste the answer" (#372): `clip` is what the view layer read from the clipboard, once, on
    /// the tap (nil: nothing there). It goes exactly where the same text shared would: the editor
    /// for a recipe, the review for a week. Returns where; with nothing to open the sheet says why
    /// (`PasteProblem`). Nothing is saved here.
    func onPasteAnswer(_ clip: String?) -> PasteTarget? {
        guard let clip, !clip.kIsBlank else {
            uiState.pasteProblem = .empty
            return nil
        }
        guard let target = pasted?.open(clip) else {
            uiState.pasteProblem = .noRecipe
            return nil
        }
        return target
    }

    /// The sheet closed: the next one opens empty (the meals come back as last chosen).
    func onReset() {
        planInput = PlanInput()
        listChoice.send(nil)
        uiState = AskAiUiState()
        refreshCounts()
    }

    /// The prompt for the chosen kind, or nil when it can't be sent yet (`AskAiUiState.canSend`).
    func prompt(words: AskAiPrompt.Words) -> String? {
        let setup = setup(words)
        let request = uiState.request
        let today = self.today
        switch uiState.kind {
        case .describe:
            return request.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ? nil : AskAiPrompt.describe(request, setup: setup)
        case .whatCanIMake:
            return pantry.contains(where: \.inStock)
                ? AskAiPrompt.whatCanIMake(pantry: pantry, today: today, request: request, setup: setup) : nil
        case .planMyWeek:
            if !uiState.plan {
                return AskAiPrompt.planMyWeek(
                    days: (0 ..< Int64(AskAiPrompt.planDays)).map { today + $0 }, favourites: favourites, cooked: cooked,
                    request: request, setup: setup
                )
            }
            return uiState.canSend ? planPrompt(request: request, setup: setup) : nil
        }
    }

    /// Plan my week with choices: the meals in the cook's order, the recipes by source, the toggled lists.
    private func planPrompt(request: String, setup: AskAiPrompt.Setup) -> String {
        let state = uiState
        let names = state.mealTypes.filter { state.chosenMeals.contains($0.key) }.map(\.name)
        let own = state.listId != nil ? listRecipes : everyRecipe
        let choice = AskAiPrompt.PlanChoice(
            days: (0 ..< Int64(AskAiPrompt.planDays)).map { state.startDay + $0 }, meals: names, source: state.source,
            candidates: state.namesRecipes ? own : [], mix: state.mix
        )
        // Ideas and "don't repeat" make no sense when the week is the cook's own recipes only.
        let ideas = state.source != .mine
        return AskAiPrompt.planMyWeekPlus(
            choice, favourites: ideas && state.useFavourites ? favourites : [],
            cooked: ideas && state.skipCooked ? cooked : [], request: request, setup: setup
        )
    }

    /// "Change this recipe" (#353): the request and `recipe`, or nil without a request.
    func changePrompt(recipe: Recipe, words: AskAiPrompt.Words) -> String? {
        uiState.canSendChange ? AskAiPrompt.changeRecipe(recipe, request: uiState.request, setup: setup(words)) : nil
    }

    private func setup(_ words: AskAiPrompt.Words) -> AskAiPrompt.Setup {
        AskAiPrompt.Setup(words: words, unitSystem: settings.unitSystem, temperatureUnit: settings.temperatureUnit)
    }

    private var today: Int64 { PlanDays.today(millis: clock.now()) }

    private func refreshCounts() {
        let today = self.today
        let inStock = pantry.filter { $0.inStock && !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        uiState.pantryCount = inStock.count
        uiState.useUpCount = inStock.filter { UseItUp.soon($0, today: today) }.count
        uiState.favouriteCount = favourites.count
        uiState.cookedCount = cooked.count
        refreshPlan(today)
    }

    /// The plan's choices resolved against the data: a choice that names a deleted type or list falls back.
    private func refreshPlan(_ today: Int64) {
        guard planPlus() else {
            uiState.plan = false
            return
        }
        let plan = planInput
        let chosen = MealType.chosenKeys(mealTypes, plan.meals.map(Array.init) ?? remembered)
        let planLists = allLists.filter { $0.recipeCount > 0 }.map { PlanListChoice(id: $0.id, name: $0.name) }
        let listId = plan.listId.flatMap { id in planLists.contains { $0.id == id } ? id : nil }
        let slots = AskAiPrompt.planDays * chosen.count
        let mix = min(max(plan.mix, 1), max(1, slots))
        uiState.plan = true
        uiState.mealTypes = mealTypes
        uiState.chosenMeals = chosen
        uiState.days = (0 ..< Int64(PlanDays.sheetDays)).map { today + $0 }
        uiState.today = today
        uiState.startDay = max(plan.startDay ?? today, today)
        uiState.source = plan.source
        uiState.mix = mix
        uiState.planLists = planLists
        uiState.listId = listId
        uiState.recipeCount = listId == nil ? everyRecipe.count : listRecipes.count
        uiState.useFavourites = plan.useFavourites
        uiState.skipCooked = plan.skipCooked
        uiState.longAnswer = AskAiPrompt.recipesToWrite(plan.source, slots, mix) > AskAiPrompt.longAnswer
    }
}
