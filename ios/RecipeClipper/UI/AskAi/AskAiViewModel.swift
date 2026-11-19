import Combine
import Foundation
import Observation

/// Which prompt Copy and Share send (#351, #353): the cook's own words, or one of the ready ones
/// filled from the app's data. Choosing a ready one leaves the field for extra wishes.
enum AskAiKind: Equatable {
    case describe, whatCanIMake, planMyWeek
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

    /// "What can I make?" needs something in the pantry to make it from.
    var canAskWhatCanIMake: Bool { pantryCount > 0 }

    /// Copy and Share are on: the cook's own words need words; a ready prompt needs its data.
    var canSend: Bool {
        switch kind {
        case .describe: !request.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .whatCanIMake: canAskWhatCanIMake
        case .planMyWeek: true
        }
    }

    /// "Change this recipe" needs the cook's request.
    var canSendChange: Bool { !request.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

/// "Ask an AI" (#351, #353): builds the prompt to copy or share. No AI runs in the app. The prompt
/// is `AskAiPrompt`'s; the screen hands over its words (read from the string catalog), and the
/// Settings units and the data come from here. Everything a prompt reads is followed by this
/// ViewModel itself, so `prompt(words:)` is right whenever it is called. Android's `AskAiViewModel`.
@MainActor
@Observable
final class AskAiViewModel {
    private(set) var uiState = AskAiUiState()

    @ObservationIgnored private let clock: Clock
    // "Paste the answer" (#372) goes where a shared text would; nil opens nothing.
    @ObservationIgnored private let pasted: PastedTextRouter?
    @ObservationIgnored private var cancellables = Set<AnyCancellable>()
    @ObservationIgnored private var pantry: [PantryItem] = []
    @ObservationIgnored private var favourites: [String] = []
    @ObservationIgnored private var cooked: [String] = []
    @ObservationIgnored private var settings: AppSettings

    init(
        pantry: PantryRepository, lists: ListRepository, recipes: RecipeRepository, preferences: AppPreferences,
        clock: Clock = SystemClock(), pasted: PastedTextRouter? = nil
    ) {
        self.clock = clock
        self.pasted = pasted
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

    /// The sheet closed: the next one opens empty.
    func onReset() {
        let counts = uiState
        uiState = AskAiUiState(
            pantryCount: counts.pantryCount, useUpCount: counts.useUpCount,
            favouriteCount: counts.favouriteCount, cookedCount: counts.cookedCount
        )
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
            return AskAiPrompt.planMyWeek(
                days: (0 ..< Int64(AskAiPrompt.planDays)).map { today + $0 }, favourites: favourites, cooked: cooked,
                request: request, setup: setup
            )
        }
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
    }
}
