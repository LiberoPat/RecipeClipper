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
/// The words around the recipe ("Serves", "INGREDIENTS", "Prep") come in as `Labels`, which
/// the view reads from the string catalog (`Strings.shareTextLabels`), so a recipe shared
/// from a German phone reads "Portionen: 4 … ZUTATEN". This stays pure; `Labels.english` is
/// the default for tests. The recipe's own text is shared as written.
enum RecipeShareText {
    /// The translated words the message body uses. `serves` and `makes` turn a count into a
    /// line ("Serves 3"); `scaled` adds the original count to one ("Serves 3 (originally 6)").
    struct Labels {
        let serves: (Int) -> String
        let makes: (Int) -> String
        let scaled: (_ line: String, _ original: Int) -> String
        let prep: String
        let cook: String
        let total: String
        let ingredients: String
        let instructions: String

        static let english = Labels(
            serves: { "Serves \($0)" },
            makes: { "Makes \($0)" },
            scaled: { line, original in "\(line) (originally \(original))" },
            prep: "Prep",
            cook: "Cook",
            total: "Total",
            ingredients: "INGREDIENTS",
            instructions: "INSTRUCTIONS"
        )
    }

    static func format(
        recipe: Recipe,
        servings: ServingsScale?,
        ingredients: [String],
        instructions: [String],
        labels: Labels = .english
    ) -> String {
        var lines: [String] = []
        lines.append(recipe.name)
        lines.append("")

        let meta = [servesLine(servings, Servings.kind(recipe.yield), labels), timesLine(recipe, labels)]
            .compactMap { $0 }
        if !meta.isEmpty {
            lines += meta
            lines.append("")
        }

        lines.append(labels.ingredients)
        lines += ingredients
        lines.append("")

        lines.append(labels.instructions)
        lines += instructions.enumerated().map { "\($0.offset + 1). \($0.element)" }

        return lines.joined(separator: "\n")
    }

    private static func servesLine(_ servings: ServingsScale?, _ kind: YieldKind, _ labels: Labels) -> String? {
        guard let servings else { return nil }
        let word = kind == .makes ? labels.makes : labels.serves
        return servings.target != servings.base
            ? labels.scaled(word(servings.target), servings.base)
            : word(servings.base)
    }

    private static func timesLine(_ recipe: Recipe, _ labels: Labels) -> String? {
        let entries = [
            recipe.prepTime.map { "\(labels.prep) \($0)" },
            recipe.cookTime.map { "\(labels.cook) \($0)" },
            recipe.totalTime.map { "\(labels.total) \($0)" },
        ].compactMap { $0 }
        return entries.isEmpty ? nil : entries.joined(separator: " · ")
    }
}
