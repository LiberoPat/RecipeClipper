import Combine
import Foundation
import Observation

/// `needs` is nil while loading. `added` is set once the Buy lines are on the grocery list.
struct WhatINeedUiState: Equatable {
    let weekStart: Int64
    var needs: WeekNeeds?
    var added = false
}

/// The week's "What I need" (#51; Android's WhatINeedViewModel): every planned recipe's lines,
/// rendered at the planned servings in the user's units (`GrocerySources.fromPlan`, as the
/// grocery sheet does), grouped by ingredient and marked Have or Buy against the pantry
/// (`PantryMatch`). Follows the pantry live.
@MainActor
@Observable
final class WhatINeedViewModel {
    private(set) var uiState: WhatINeedUiState

    @ObservationIgnored private let groceries: GroceryRepository
    @ObservationIgnored private var subscription: AnyCancellable?
    /// Loading the week, then following the pantry. Tests await it.
    @ObservationIgnored private(set) var loading: Task<Void, Never>?

    /// `decisions`: the model's "same ingredient?" answers (#104); none without it.
    init(
        weekStart: Int64, groceries: GroceryRepository, pantry: PantryRepository, preferences: AppPreferences,
        decisions: DecisionRepository? = nil
    ) {
        uiState = WhatINeedUiState(weekStart: weekStart)
        self.groceries = groceries
        loading = Task { [weak self] in
            let planned = await groceries.plannedIngredients(start: weekStart, end: weekStart + 6)
            let settings = preferences.current
            let decided = await decisions?.current() ?? .none
            let sources = GrocerySources.fromPlan(
                planned, system: settings.unitSystem, convertLiquids: settings.convertLiquids, decisions: decided
            )
            guard let self, !Task.isCancelled else { return }
            let answers = decisions?.observe() ?? Just(Decisions.none).eraseToAnyPublisher()
            self.subscription = pantry.observeItems().combineLatest(answers)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] items, answers in
                    let needs = PantryMatch.weekNeeds(sources, pantry: items, decisions: answers)
                    self?.uiState.needs = needs
                    // Close pantry names are asked about in the background (#104); a "same" moves
                    // the row to Have once it lands, anything else leaves it.
                    guard let decisions else { return }
                    var questions: [DecisionQuestion] = []
                    for row in needs.buy {
                        guard let name = row.name else { continue }
                        questions += DecisionCandidates.samePairs([name], language: row.lines[0].language, pantry: items)
                    }
                    if !questions.isEmpty { Task { await decisions.decide(questions) } }
                }
        }
    }

    /// Every Buy line onto the grocery list, through the same path as the grocery sheet (#50).
    func onAddBuyToGroceries() {
        guard !uiState.added else { return }
        let lines = (uiState.needs?.buy ?? []).flatMap { row in
            row.lines.map { NewGroceryLine(text: $0.text, language: $0.language, recipeId: $0.recipeId, plannedDay: $0.day) }
        }
        guard !lines.isEmpty else { return }
        uiState.added = true
        Task { await groceries.add(lines) }
    }
}
