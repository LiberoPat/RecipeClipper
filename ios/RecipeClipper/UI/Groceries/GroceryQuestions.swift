import Foundation

/// The on-device model's questions about the grocery list (#99, #104, #234; Android's
/// `GroceryQuestions`), asked in the background, each once per visit. The list shows today's
/// grouping until an answer lands; then the decisions publisher regroups it, and an answer may
/// file items out of Other (`GroceryRepository.fileFromOther`).
@MainActor
final class GroceryQuestions {
    /// The list as it now stands. Set by the ViewModel.
    var latestItems: () -> [GroceryItem] = { [] }

    private let decisions: DecisionRepository
    private let repository: GroceryRepository
    // Grocery questions and items already asked about in this visit, so each is asked once.
    private var askedGrocery = Set<DecisionQuestion>()
    private var askedAisles = Set<Int64>()

    init(decisions: DecisionRepository, repository: GroceryRepository) {
        self.decisions = decisions
        self.repository = repository
    }

    /// Asks what `items` raise that `current` hasn't answered and this visit hasn't asked.
    func ask(_ items: [GroceryItem], _ current: Decisions) {
        askAisles(items)
        askGroceryQuestions(items, current)
    }

    /// Asks the model about close names and trailing text (#99), in the background. The list
    /// shows today's grouping until an answer lands; the decisions publisher then regroups it,
    /// and a fresh answer may file a line out of Other beside its partner.
    private func askGroceryQuestions(_ items: [GroceryItem], _ current: Decisions) {
        let unchecked = items.filter { !$0.checked }
        let open = (GroceryDecisions.ingredientNames(unchecked) + GroceryDecisions.trailingTexts(unchecked, decisions: current)
            + GroceryDecisions.samePairs(unchecked, decisions: current))
            .filter { !current.isAnswered($0) && askedGrocery.insert($0).inserted }
        if open.isEmpty { return }
        Task { [decisions, repository] in
            await decisions.decide(open)
            let after = await decisions.current()
            let fresh = Set(open.filter { after.isAnswered($0) })
            for (aisle, ids) in GroceryDecisions.filing(self.latestItems(), fresh: fresh, decisions: after) {
                await repository.fileFromOther(ids, aisle: aisle)
            }
        }
    }

    /// Asks the model the aisle of each item in Other whose name the keyword table doesn't know
    /// (#104), in the background. Only an answer that lands now files the items, and only those
    /// still in Other: an item in Other whose aisle was already decided was put there by the user.
    private func askAisles(_ items: [GroceryItem]) {
        var byQuestion: [DecisionQuestion: [Int64]] = [:]
        for item in items where item.aisle == .other && !item.checked && askedAisles.insert(item.id).inserted {
            if let q = DecisionCandidates.aisle(item.text, language: item.language) { byQuestion[q, default: []].append(item.id) }
        }
        if byQuestion.isEmpty { return }
        Task { [decisions, repository] in
            let before = await decisions.current()
            let open = byQuestion.filter { !before.isAnswered($0.key) }
            if open.isEmpty { return }
            await decisions.decide(Array(open.keys))
            let after = await decisions.current()
            for (question, ids) in open {
                guard let aisle = after.aisle(question.input, language: question.language) else { continue }
                await repository.fileFromOther(ids, aisle: aisle)
            }
        }
    }
}
