import Foundation

/// Turns the recipe as currently shown on screen — scaled servings, converted units — into
/// plain text for sharing outside the app (Messages, WhatsApp, Mail: none render Markdown, so
/// this is plain text only). Pure: takes the already-rendered ingredient and instruction
/// strings, so no scaling or unit-conversion logic is duplicated here.
///
/// `servings` is nil when the recipe has no usable yield, in which case there's nothing to
/// report a serves line for. The line reads "Makes 16" instead of "Serves 16" when the
/// recipe's yield counts things made rather than people (`Servings.kind`).
///
/// The source link is deliberately left out: what's shared is the recipe as clipped, not a
/// pointer back to the page — the story and ads this app exists to skip.
///
/// Deliberately keeps its own English wording ("Serves 3 (originally 6)", "Prep 10m · Cook
/// 30m") rather than reading it from the string catalog like the rest of the UI text: this
/// isn't UI, it's the body of a message the user sends elsewhere, and it stays English-only
/// by design, same as the whole app for now.
enum RecipeShareText {
    static func format(recipe: Recipe, servings: ServingsScale?, ingredients: [String], instructions: [String]) -> String {
        var lines: [String] = []
        lines.append(recipe.name)
        lines.append("")

        let meta = [servesLine(servings, Servings.kind(recipe.yield)), timesLine(recipe)].compactMap { $0 }
        if !meta.isEmpty {
            lines += meta
            lines.append("")
        }

        lines.append("INGREDIENTS")
        lines += ingredients
        lines.append("")

        lines.append("INSTRUCTIONS")
        lines += instructions.enumerated().map { "\($0.offset + 1). \($0.element)" }

        return lines.joined(separator: "\n")
    }

    private static func servesLine(_ servings: ServingsScale?, _ kind: YieldKind) -> String? {
        guard let servings else { return nil }
        let word = kind == .makes ? "Makes" : "Serves"
        return servings.target != servings.base
            ? "\(word) \(servings.target) (originally \(servings.base))"
            : "\(word) \(servings.base)"
    }

    private static func timesLine(_ recipe: Recipe) -> String? {
        let entries = [
            recipe.prepTime.map { "Prep \($0)" },
            recipe.cookTime.map { "Cook \($0)" },
            recipe.totalTime.map { "Total \($0)" },
        ].compactMap { $0 }
        return entries.isEmpty ? nil : entries.joined(separator: " · ")
    }
}
