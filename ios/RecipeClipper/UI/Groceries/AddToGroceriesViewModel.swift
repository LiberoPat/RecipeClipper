import Foundation
import Observation

/// One line in the add sheet: `source`'s line number `index`.
struct SourceLine: Hashable {
    let source: String
    let index: Int
}

/// The "Add to groceries" sheet (#50): one recipe's lines or every planned recipe's, each
/// ticked to start unless the pantry has it (#51). `sources` is nil while the week's are loading. `added` is set once the
/// ticked lines are written; the sheet closes on it.
struct AddToGroceriesUiState: Equatable {
    var sources: [GrocerySource]?
    var unticked: Set<SourceLine> = []
    var added = false

    var tickedCount: Int {
        (sources ?? []).reduce(0) { total, s in
            total + s.lines.indices.filter { !unticked.contains(SourceLine(source: s.key, index: $0)) }.count
        }
    }
}

/// Backs the "Add to groceries" sheet (Android's AddToGroceriesViewModel). Adding is one
/// deliberate act with a button: the cook first unticks what's already in the cupboard.
@MainActor
@Observable
final class AddToGroceriesViewModel {
    private(set) var uiState = AddToGroceriesUiState()

    @ObservationIgnored private let repository: GroceryRepository
    @ObservationIgnored private let preferences: AppPreferences
    @ObservationIgnored private let pantry: PantryRepository
    @ObservationIgnored private let decisions: DecisionRepository?

    /// `decisions`: the model's answers (#104); none without it.
    init(
        repository: GroceryRepository, preferences: AppPreferences, pantry: PantryRepository,
        decisions: DecisionRepository? = nil
    ) {
        self.repository = repository
        self.preferences = preferences
        self.pantry = pantry
        self.decisions = decisions
    }

    /// One recipe, its `rendered` lines exactly as the reading view shows them.
    func setRecipe(_ recipeId: Int64, title: String, language: String?, rendered: [String]) {
        let sources = [GrocerySources.fromRecipe(recipeId: recipeId, title: title, language: language, rendered: rendered)]
        uiState = AddToGroceriesUiState(sources: sources)
        loading?.cancel()
        loading = Task { await untickCovered(sources) }
    }

    /// Lines whose ingredient the pantry has (in stock, or a staple) start unticked (#51), so
    /// the cook only reviews them. Matching is by name, never amount; a tick already changed stays.
    private func untickCovered(_ sources: [GrocerySource]) async {
        let items = await pantry.items()
        guard !Task.isCancelled, !items.isEmpty, uiState.sources == sources else { return }
        // Answers already cached count now (#104); new questions are asked for next time, so
        // ticks never change under the cook while the sheet is open.
        let decided = await decisions?.current() ?? .none
        var questions: [DecisionQuestion] = []
        for source in sources {
            for (index, line) in source.lines.enumerated()
            where PantryMatch.covered(line, language: source.language, pantry: items, decisions: decided) {
                uiState.unticked.insert(SourceLine(source: source.key, index: index))
            }
            if let words = LanguageWords.forTag(source.language) {
                let names = source.lines.compactMap { IngredientName.of($0, words: words) }
                questions += DecisionCandidates.samePairs(names, language: source.language, pantry: items)
            }
        }
        if let decisions, !questions.isEmpty { Task { await decisions.decide(questions) } }
    }

    /// Every recipe planned from `start` for seven days, at its planned servings, in the
    /// user's units.
    /// Starts empty at once (the sheet shows a spinner), then fills in; `loading` is that fetch
    /// (and, for one recipe too, the pantry read that unticks what's there).
    func loadWeek(_ start: Int64) {
        uiState = AddToGroceriesUiState()
        loading?.cancel()
        loading = Task { [repository, preferences] in
            let planned = await repository.plannedIngredients(start: start, end: start + 6)
            guard !Task.isCancelled else { return }
            let settings = preferences.current
            let decided = await self.decisions?.current() ?? .none
            let sources = GrocerySources.fromPlan(
                planned, system: settings.unitSystem, convertLiquids: settings.convertLiquids, decisions: decided
            )
            uiState.sources = sources
            await untickCovered(sources)
        }
    }

    @ObservationIgnored private(set) var loading: Task<Void, Never>?

    func onToggle(_ line: SourceLine) {
        if uiState.unticked.contains(line) { uiState.unticked.remove(line) } else { uiState.unticked.insert(line) }
    }

    func onAdd() {
        guard !uiState.added else { return }
        let lines: [NewGroceryLine] = (uiState.sources ?? []).flatMap { source in
            source.lines.enumerated().compactMap { index, text in
                uiState.unticked.contains(SourceLine(source: source.key, index: index)) ? nil
                    : NewGroceryLine(text: text, language: source.language, recipeId: source.recipeId, plannedDay: source.day)
            }
        }
        guard !lines.isEmpty else { return }
        uiState.added = true
        Task { await repository.add(lines) }
    }
}

extension AddToGroceriesViewModel: Identifiable {}
