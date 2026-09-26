import Foundation

/// Ingredient lines as the reading view shows them: each scaled by `factor`, then converted to
/// `system`, so a converted amount always matches the chosen servings. The conversion keeps
/// the decimal separator the line was written with ("2,5 lb" doubled is "5 lb", which no longer
/// shows its comma). Shared by every screen that shows or gathers a recipe's lines (#46).
/// `words` are the recipe's language's (#14); nil leaves every line as written.
enum IngredientRendering {

    /// `decisions`: the model's definite answers about count brackets (#104); `.none` is as before.
    static func render(
        _ lines: [String], factor: Double, system: UnitSystem, convertLiquids: Bool, words: LanguageWords? = .english,
        decisions: Decisions = .none
    ) -> [String] {
        lines.map {
            let bracket = decisions.countBracket($0, language: words?.language)
            return UnitConverter.convert(
                IngredientScaler.scale($0, factor: factor, words: words, bracket: bracket), system: system,
                includeLiquids: convertLiquids, separatorFrom: $0, words: words
            )
        }
    }
}
