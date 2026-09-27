import Foundation

/// Reddit `.json` listings in Reddit's real `raw_json=1` shape, read from `shared/fixtures/reddit`
/// (the files Android's `RedditFixtures` reads), so both suites test the same input. The posts,
/// people and recipes are made up.
enum RedditFixtures {
    /// A photo on r/recipes, the recipe in the poster's comment.
    static var imageWithOpRecipe: String { read("recipes-image-op-comment") }
    /// A text post whose body is the recipe, with bold "Ingredients:" headers.
    static var selfPost: String { read("recipes-self-post") }
    /// A gallery of a recipe card, transcribed in a reply to AutoModerator.
    static var cardWithTranscription: String { read("old-recipes-card-transcription") }
    /// A photo of a finished dish: only chatter.
    static var photoOnly: String { read("food-photo-chatter") }
    /// A crosspost of `imageWithOpRecipe`, with no recipe of its own.
    static var crosspost: String { read("crosspost") }
    /// A request answered by a comment whose ingredients have no header above them.
    static var replyWithoutHeaders: String { read("request-reply-no-headers") }

    private final class Anchor {}

    private static func read(_ name: String) -> String {
        let bundle = Bundle(for: Anchor.self)
        guard let url = bundle.url(forResource: name, withExtension: "json", subdirectory: "fixtures/reddit"),
              let text = try? String(contentsOf: url, encoding: .utf8)
        else { fatalError("missing fixture fixtures/reddit/\(name).json") }
        return text
    }
}
