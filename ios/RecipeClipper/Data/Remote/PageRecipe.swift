import Foundation

/// The pure ends of reading a recipe out of a page's text (#103); the model call sits between
/// them, in the repository. Android's `PageRecipe`.
enum PageRecipe {

    /// The lines language detection reads before the model is asked.
    private static let detectionLines = 300

    /// The page's language, primary subtag only ("en"): `<html lang>`, unless its words clearly say another.
    static func language(_ page: PageText) -> String {
        let tag = LanguageWords.resolve(declared: nil, page: page.language) {
            page.lines.prefix(detectionLines).joined(separator: "\n")
        }
        return String(tag.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
    }

    /// The recipe, from what the model `picked` out of `window` (the text it was given), keeping
    /// only what `PageRecipeCheck` finds there; nil if that leaves no recipe. Marked `.extracted`,
    /// with the page's photo and times read as the parsers read them.
    static func recipe(window: String, picked: PageSelection, page: PageText, url: String) -> Recipe? {
        guard let kept = PageRecipeCheck.verify(window, picked), let name = kept.name else { return nil }
        let language = LanguageWords.resolve(declared: nil, page: page.language) {
            LanguageWords.detectionText(name: name, ingredients: kept.ingredients)
        }
        let words = LanguageWords.forTag(language)
        func time(_ text: String?) -> String? { text.flatMap { JsonLdRecipeParser.formatDuration($0, words: words) } }
        var recipe = Recipe(
            name: name,
            image: page.image,
            ingredients: kept.ingredients,
            instructions: kept.steps,
            prepTime: time(kept.prepTime),
            cookTime: time(kept.cookTime),
            totalTime: time(kept.totalTime),
            yield: kept.yield,
            sourceUrl: url,
            language: language
        )
        recipe.origin = .extracted
        return recipe
    }
}
