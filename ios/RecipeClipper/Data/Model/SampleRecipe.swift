import Foundation

/// The sample recipe (#151), added quietly on a new user's first launch (#190), from
/// `shared/sample/recipe.json` (one copy, both apps, bundled as `sample/`). It is saved like a
/// typed-in recipe (MANUAL, never fetched, no "Update from source") under the fixed link
/// `sourceUrl`, which is how the library leaves it out of every count (#107): it never takes one
/// of the free tier's places. Deleting it works like any other recipe. Android's `SampleRecipe`.
enum SampleRecipe {
    /// A `manual:` link, so everything that treats a typed-in recipe's link applies.
    static let sourceUrl = ManualRecipe.scheme + "sample"

    static func isSample(_ sourceUrl: String) -> Bool { sourceUrl == Self.sourceUrl }

    private struct Entry: Decodable {
        let name: String
        let yield: String?
        let prepTime: String?
        let cookTime: String?
        let totalTime: String?
        let ingredients: [String]
        let instructions: [String]
    }

    private struct File: Decodable { let recipes: [String: Entry] }

    private static let entries: [String: Entry] = {
        guard let url = Bundle.main.url(forResource: "sample/recipe", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let file = try? JSONDecoder().decode(File.self, from: data)
        else { fatalError("sample/recipe.json is missing from the bundle or malformed") }
        return file.recipes
    }()

    /// The languages the sample is written in.
    static var languages: [String] { Array(entries.keys) }

    /// The sample in `language` (a tag like "de" or "pt-BR"), or in English if it isn't written in it.
    static func forLanguage(_ language: String?) -> Recipe {
        let code = language?.split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map { $0.lowercased() }
        let chosen = code.flatMap { entries[$0] != nil ? $0 : nil } ?? "en"
        let entry = entries[chosen]!
        var recipe = Recipe(
            name: entry.name, image: nil, ingredients: entry.ingredients, instructions: entry.instructions,
            prepTime: formatTime(entry.prepTime, language: chosen), cookTime: formatTime(entry.cookTime, language: chosen),
            totalTime: formatTime(entry.totalTime, language: chosen), yield: entry.yield,
            sourceUrl: sourceUrl
        )
        recipe.language = chosen
        recipe.origin = .manual
        return recipe
    }

    /// A time as the sample shows it (#179): the file's ISO duration ("PT10M") formatted the way
    /// a parsed recipe's is, in `language`'s words ("10m", "10min"). Formatting one already
    /// formatted changes nothing, which is what lets a sample saved before #179 be fixed at every
    /// launch (`RecipeRepository.formatSampleTimes`).
    static func formatTime(_ time: String?, language: String?) -> String? {
        time.flatMap { Durations.format($0, words: LanguageWords.forTag(language)) }
    }
}
