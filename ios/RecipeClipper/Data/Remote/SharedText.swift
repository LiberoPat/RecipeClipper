import Foundation

/// Text shared into the app (#306): a note, an email, a message. Where it goes, and what the
/// editor opens with. Ported line for line from Android's `SharedText`; its doc comment has the
/// rules: a recipe (the text splits) wins over a link in it; else a link imports; else bulleted
/// lines are a list (#149); else the editor opens with the text as written.
enum SharedText {

    enum Kind: Equatable { case recipe, link, list, text }

    /// Longer text isn't offered to the editor (Android carries it in the route's saved state).
    static let maxLength = 50_000

    private static let linkPattern = JRegex(#"https?://\S+"#)

    /// The first http(s) link in `text`, as shared (the repository cleans it).
    static func link(_ text: String) -> String? { linkPattern.find(text)?.value }

    static func kind(_ text: String) -> Kind {
        if text.utf16.count <= maxLength && read(text).sorted { return .recipe }
        if link(text) != nil { return .link }
        if ReceivedList.bulleted(text) && !ReceivedList.lines(text).isEmpty { return .list }
        return .text
    }

    /// The split recipe when the text splits, else every non-blank line, trimmed and otherwise as
    /// written, as the steps (never scaled, converted, bought or used up). No name either way.
    static func read(_ text: String) -> Reading {
        if let split = RecipeTextSplitter.detectAndSplit(text),
           !split.ingredients.isEmpty || !split.instructions.isEmpty {
            return Reading(recipe: split, sorted: true)
        }
        let lines = ReceivedList.allLines(text)
        let language = RecipeTextSplitter.languageOf(text)
        return Reading(
            recipe: SplitRecipe(
                ingredients: [], instructions: lines, yield: nil, prepTime: nil, cookTime: nil,
                totalTime: nil, language: language
            ),
            sorted: false
        )
    }

    /// `recipe` is what the editor's fields start with; its language, the text's (#208).
    struct Reading: Equatable {
        let recipe: SplitRecipe
        let sorted: Bool
    }
}
