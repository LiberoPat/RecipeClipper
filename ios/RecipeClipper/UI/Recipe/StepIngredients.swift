import Foundation

/// Cook mode's ingredients bar, opened (#286; Android's `StepIngredients`): the lines the
/// current step names first, under "In this step" and washed in paprika (`now`), then the rest
/// under "Later" (`later`). Each is line indexes into the shown ingredients, in the order they
/// show; every line, headings included, is in exactly one of them, so ticks stay keyed by line
/// index.
///
/// Which lines a step names is the amounts-in-steps matching (`StepAmounts.named`, #254), read
/// whether "Amounts in steps" is on or off; the steps show the same either way.
///
/// A group heading ("For the sauce:") moves to `now` only when every line under it does, and its
/// lines sit under it there, after the step's other lines; otherwise it stays in `later` over
/// the lines still to come, and the step's lines from its group show without it.
struct StepIngredients: Equatable {
    let now: [Int]
    let later: [Int]

    /// The bar for cook mode's current step, or nil to list every line as written: not cooking,
    /// no recipe, or a step that names no line.
    static func of(_ content: RecipeContent, cook: CookState, asWritten: Set<Int>) -> StepIngredients? {
        guard let content = content.success, cook.active,
              content.instructions.indices.contains(cook.currentStep) else { return nil }
        // The step as cook mode shows it: Chef mode's short version, unless asked for as written.
        let shown = content.shownStep(cook.currentStep, asWritten: asWritten)
        return group(content.ingredients, named: StepAmounts.named(shown, lines: content.ingredients, words: content.words))
    }

    /// `lines` split by the `named` ones, as above; nil when no line that isn't a heading is named.
    static func group(_ lines: [String], named: [Int]) -> StepIngredients? {
        let heading = lines.map(IngredientHeading.isHeading)
        let used = Set(named.filter { lines.indices.contains($0) && !heading[$0] })
        if used.isEmpty { return nil }
        // Each line's group heading, or nil before the first one.
        var current: Int?
        let groupOf: [Int?] = lines.indices.map { i in
            if heading[i] { current = i; return nil }
            return current
        }
        let members = Dictionary(grouping: lines.indices.filter { !heading[$0] }, by: { groupOf[$0] ?? -1 })
        // A heading whose every line is in the step moves with them.
        let whole = lines.indices.filter { h in
            heading[h] && members[h].map { !$0.isEmpty && $0.allSatisfy(used.contains) } == true
        }
        let loose = used.sorted().filter { line in groupOf[line].map { !whole.contains($0) } ?? true }
        let now = loose + whole.flatMap { [$0] + (members[$0] ?? []) }
        let placed = Set(now)
        return StepIngredients(now: now, later: lines.indices.filter { !placed.contains($0) })
    }
}
