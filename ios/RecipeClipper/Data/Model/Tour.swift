import Foundation

/// Where the first-run welcome stands (#151), stored by name under `tour_welcome` (Android's
/// `WelcomeState`). An unknown name reads as `.undecided`, which the next launch decides from
/// the library.
enum WelcomeState: String {
    /// Never decided: a fresh install, or an app from before the tour.
    case undecided = "UNDECIDED"
    /// A new user who hasn't finished the welcome: it shows at the next plain launch.
    case pending = "PENDING"
    /// Finished, skipped, or never needed (someone who already had recipes).
    case seen = "SEEN"

    init(storedName: String?) {
        self = storedName.flatMap(WelcomeState.init(rawValue:)) ?? .undecided
    }
}

/// The one-time tips (#151): one small callout the first time a screen is reached, dismissed by
/// a tap. `key` is its UserDefaults key, true once dismissed; the same in Android's
/// `unit_preferences`. `mealPlan` tips belong to the tabs behind the `mealPlan` flag and hide
/// while it is off.
enum Tip: String, CaseIterable {
    /// The first recipe opened: the Serves and units row, and the bookmark.
    case recipe = "RECIPE"
    case cookMode = "COOK_MODE"
    case week = "WEEK"
    case groceries = "GROCERIES"
    case pantry = "PANTRY"

    var key: String {
        switch self {
        case .recipe: "tour_tip_recipe"
        case .cookMode: "tour_tip_cook_mode"
        case .week: "tour_tip_week"
        case .groceries: "tour_tip_groceries"
        case .pantry: "tour_tip_pantry"
        }
    }

    var mealPlan: Bool { self == .week || self == .groceries || self == .pantry }
}

/// The tour's sample recipe (#151), from `shared/sample/recipe.json` (one copy, both apps,
/// bundled as `sample/`). It is saved like a typed-in recipe (MANUAL, never fetched, no "Update
/// from source") under the fixed link `sourceUrl`, which is how the library leaves it out of
/// every count (#107): it never takes one of the free tier's places. Deleting it works like any
/// other recipe. Android's `SampleRecipe`.
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
            prepTime: entry.prepTime, cookTime: entry.cookTime, totalTime: entry.totalTime, yield: entry.yield,
            sourceUrl: sourceUrl
        )
        recipe.language = chosen
        recipe.origin = .manual
        return recipe
    }
}
