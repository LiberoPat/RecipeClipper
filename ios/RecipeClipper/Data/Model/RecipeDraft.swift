import Foundation

/// What the Edit screen saves (#29): a recipe's content as the user typed it, before it is
/// stored. Pure, like the parsers (Android's RecipeDraft.kt). Nothing here is guessed or
/// converted; the user's words are kept as written, only trimmed.
struct RecipeDraft: Equatable {
    var name = ""
    var yield = ""
    var prepTime = ""
    var cookTime = ""
    var totalTime = ""
    /// One ingredient per line, as typed in the ingredients box.
    var ingredientsText = ""
    /// One step per line, as typed in the steps box.
    var instructionsText = ""
    var image = ""

    var ingredients: [String] { Self.lines(ingredientsText) }
    var instructions: [String] { Self.lines(instructionsText) }

    /// The parsers' rule for a recipe: a name, plus ingredients or steps.
    var isValid: Bool {
        !Self.trimmed(name).isEmpty && (!ingredients.isEmpty || !instructions.isEmpty)
    }

    /// `base`'s identity and user state with this draft's content. A blank optional field is
    /// absent (nil), never an empty string.
    func apply(to base: Recipe) -> Recipe {
        var recipe = base
        recipe.name = Self.trimmed(name)
        recipe.image = Self.optional(image)
        recipe.ingredients = ingredients
        recipe.instructions = instructions
        recipe.prepTime = Self.optional(prepTime)
        recipe.cookTime = Self.optional(cookTime)
        recipe.totalTime = Self.optional(totalTime)
        recipe.yield = Self.optional(yield)
        return recipe
    }

    /// A recipe's content as the Edit screen first shows it.
    static func of(_ recipe: Recipe) -> RecipeDraft {
        RecipeDraft(
            name: recipe.name,
            yield: recipe.yield ?? "",
            prepTime: recipe.prepTime ?? "",
            cookTime: recipe.cookTime ?? "",
            totalTime: recipe.totalTime ?? "",
            ingredientsText: recipe.ingredients.joined(separator: "\n"),
            instructionsText: recipe.instructions.joined(separator: "\n"),
            image: recipe.image ?? ""
        )
    }

    /// One entry per non-blank line, trimmed. Split on "\n" only, like Kotlin's `split('\n')`
    /// (a "\r\n" pair is one Character in Swift, so split on the scalar instead).
    static func lines(_ text: String) -> [String] {
        text.unicodeScalars.split(separator: "\n", omittingEmptySubsequences: false)
            .map { trimmed(String(String.UnicodeScalarView($0))) }
            .filter { !$0.isEmpty }
    }

    /// Kotlin's `trim()`: drops leading and trailing whitespace and control characters.
    private static func trimmed(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func optional(_ s: String) -> String? {
        let t = trimmed(s)
        return t.isEmpty ? nil : t
    }
}
