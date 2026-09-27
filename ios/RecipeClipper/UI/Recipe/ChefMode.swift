import Combine
import Foundation

/// The on-device model's part of one recipe screen (#169; Android's `ChefMode`): Chef mode's
/// short steps (#100) and the decided count brackets (#104), two of `RecipeRenderer`'s inputs.
/// It owns their repositories and flags, and says when either input changed; the ViewModel then
/// renders again. Every repository is optional, so a screen (or a test) without the model shows
/// the recipe as today. Nothing it starts holds it strongly, and it cancels its writing when it
/// goes, so it leaves with its screen.
@MainActor
final class ChefMode {

    /// The loaded recipe's short steps as saved, before rendering; empty while Chef mode is off.
    private(set) var shortSteps: [String?] = []
    /// The model's decided count brackets (#104); `.none` (today's rendering) until one lands.
    private(set) var decisions = Decisions.none

    /// The recipe on screen if it is saved, else nil: short steps are cached per saved recipe, so
    /// a recipe the free tier didn't keep (#107) gets none. Set by the ViewModel.
    var keptRecipe: () -> Recipe? = { nil }
    /// `shortSteps` changed. Set by the ViewModel, which renders them.
    var onShortSteps: () -> Void = {}
    /// `decisions` changed. Set by the ViewModel, which renders the ingredients again.
    var onDecisions: () -> Void = {}

    private let shortStepRepository: ShortStepRepository?
    private let flags: FeatureFlags?
    private let decisionRepository: DecisionRepository?
    // On when both its flag and its setting are.
    private var on: Bool
    private var task: Task<Void, Never>?
    private var subscription: AnyCancellable?
    private var decisionSubscription: AnyCancellable?

    init(shortSteps: ShortStepRepository?, flags: FeatureFlags?, decisions: DecisionRepository?, setting: Bool) {
        self.shortStepRepository = shortSteps
        self.flags = flags
        self.decisionRepository = decisions
        on = (flags?.isOn(.chefMode) ?? false) && setting
    }

    deinit { task?.cancel() }

    /// Follows the model's decisions for as long as this lives.
    func observeDecisions() {
        decisionSubscription = decisionRepository?.observe()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] answers in
                guard let self, answers != self.decisions else { return }
                self.decisions = answers
                self.onDecisions()
            }
    }

    /// The Chef mode setting, as Settings has it now.
    func onSetting(_ setting: Bool) {
        let next = (flags?.isOn(.chefMode) ?? false) && setting
        guard next != on else { return }
        on = next
        start()
    }

    /// (Re)starts the loaded recipe's short steps: the saved ones show at once, and the missing
    /// ones are written one by one, the steps showing as written meanwhile. Nothing happens on a
    /// phone or recipe language the model can't do; Chef mode off clears them.
    func start() {
        task?.cancel()
        task = nil
        subscription = nil
        shortSteps = []
        onShortSteps()
        guard on, let shortStepRepository, let recipe = keptRecipe() else { return }
        let language = LanguageWords.forRecipe(recipe)?.language
        // Weak, and self is never held across the writing, so a popped screen goes at once.
        task = Task { [weak self] in
            let support = await shortStepRepository.support()
            guard !Task.isCancelled, support.covers(language) else { return }
            self?.subscription = shortStepRepository.observe(recipe)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] shorts in
                    self?.shortSteps = shorts
                    self?.onShortSteps()
                }
            await shortStepRepository.fill(recipe)
        }
    }

    /// Asks the model about `content`'s count brackets (#104), in the background: lines show as
    /// today until an answer lands. Only a recipe with a servings stepper can scale, and only
    /// with `aiCountBrackets` on (#127).
    func askCountBrackets(_ content: RecipeSuccess) {
        guard flags?.isOn(.aiCountBrackets) == true else { return }
        guard let decisionRepository, content.servings != nil else { return }
        let questions = DecisionCandidates.countBrackets(content.recipe.ingredients, words: content.words)
        if !questions.isEmpty { Task { await decisionRepository.decide(questions) } }
    }
}
