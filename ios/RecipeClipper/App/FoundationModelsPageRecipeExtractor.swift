import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// A recipe picked from a page's text by Apple's on-device Foundation Model (#103): iOS 26 or
/// later, Apple Intelligence on. It reads the window with each line numbered and answers, through
/// guided generation (`@Generable`), which lines are the ingredients and the steps as runs of line
/// numbers; the lines themselves come from the window (`PageLines`, #128), so the reply stays
/// short beside the page in the context however long the recipe. `PageRecipeCheck` then keeps
/// one recipe's lines. Any failure (a guardrail, a language it won't read, a page over its
/// context) is nil, and the page stays "no recipe found" as before.
final class FoundationModelsPageRecipeExtractor: PageRecipeExtractor {

    /// The recipe languages the app has words for (shared/tables).
    private static let recipeLanguages: Set<String> = ["en", "de", "es", "fr", "it", "pt", "ja"]

    /// Tokens kept for the instructions, the schema, the line numbers and the reply, out of
    /// `contextSize` (4,096 on iOS 26, 8,192 on 27).
    private static let reservedTokens = 1_800

    private static let instructions = """
        You find a recipe in a web page's text. Each line of the page starts with its number in \
        brackets, like [12]. Copy the name, the yield and the times exactly as the page writes \
        them. For the ingredients and the steps, give line numbers, not text: each run is the \
        first and last line of consecutive lines that are all the recipe's ingredient lines, or \
        all its method's steps, in page order. Leave out headings, notes, tips, ads and other \
        recipes. If the page holds no recipe, leave the name and the runs empty.
        """

    func windowChars(language: String) async -> Int? {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            let model = SystemLanguageModel.default
            guard model.isAvailable, Self.recipeLanguages.contains(language),
                  model.supportedLanguages.contains(where: { $0.languageCode?.identifier == language })
            else { return nil }
            let tokens = model.contextSize - Self.reservedTokens
            guard tokens > 0 else { return nil }
            // Characters per token: about 3 in spaced languages, about 1 in Japanese.
            return language == "ja" ? tokens : tokens * 3
        }
        #endif
        return nil
    }

    func extract(_ text: String, language: String) async -> PageSelection? {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            guard SystemLanguageModel.default.isAvailable else { return nil }
            let session = LanguageModelSession(instructions: Self.instructions)
            do {
                let picked = try await session.respond(
                    to: PageLines.numbered(text), generating: PickedLines.self, options: GenerationOptions(temperature: 0)
                ).content
                let pick = PagePick(
                    name: picked.name.isEmpty ? nil : picked.name,
                    ingredients: picked.ingredients.map { LineRun(first: $0.first, last: $0.last) },
                    steps: picked.steps.map { LineRun(first: $0.first, last: $0.last) },
                    yield: picked.yield, prepTime: picked.prepTime,
                    cookTime: picked.cookTime, totalTime: picked.totalTime
                )
                return PageLines.selection(text, pick)
            } catch {
                return nil
            }
        }
        #endif
        return nil
    }
}

#if canImport(FoundationModels)
/// What the model fills in, in this order: the name, yield and times copied from the page, then
/// the ingredients and the steps as runs of line numbers.
@available(iOS 26, *)
@Generable
private struct PickedLines {
    @Guide(description: "The recipe's name, copied from the page")
    var name: String
    @Guide(description: "The servings or yield as the page writes it, if it gives one")
    var yield: String?
    @Guide(description: "The preparation time as the page writes it, if it gives one")
    var prepTime: String?
    @Guide(description: "The cooking time as the page writes it, if it gives one")
    var cookTime: String?
    @Guide(description: "The total time as the page writes it, if it gives one")
    var totalTime: String?
    @Guide(description: "The runs of lines that are the recipe's ingredient lines, in page order")
    var ingredients: [PickedRun]
    @Guide(description: "The runs of lines that are the method's steps, in page order")
    var steps: [PickedRun]
}

/// A run of consecutive lines, by their numbers.
@available(iOS 26, *)
@Generable
private struct PickedRun {
    @Guide(description: "The number of the run's first line")
    var first: Int
    @Guide(description: "The number of the run's last line")
    var last: Int
}
#endif
