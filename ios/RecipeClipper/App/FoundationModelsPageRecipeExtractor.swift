import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// A recipe picked from a page's text by Apple's on-device Foundation Model (#103): iOS 26 or
/// later, Apple Intelligence on. Guided generation (`@Generable`) returns the fields typed, so no
/// reply is parsed; `PageRecipeCheck` then keeps only what is on the page. Asked in two parts
/// (#128), the recipe and then its steps, each in a fresh session, so a long recipe's reply fits
/// beside the page in the context. Any failure (a guardrail, a language it won't read, a page
/// over its context) is nil, and the page stays "no recipe found" as before.
final class FoundationModelsPageRecipeExtractor: PageRecipeExtractor {

    /// The recipe languages the app has words for (shared/tables).
    private static let recipeLanguages: Set<String> = ["en", "de", "es", "fr", "it", "pt", "ja"]

    /// Tokens kept for the instructions, the schema and the reply, out of `contextSize`
    /// (4,096 on iOS 26, 8,192 on 27).
    private static let reservedTokens = 1_800

    private static let instructions = """
        You pick a recipe out of a web page's text. Copy every value exactly as it is written on \
        the page, character for character: never write, fix, translate, shorten or summarise. One \
        ingredient line per item, in page order. Leave out anything that isn't on the page. If the \
        page holds no recipe, leave the name and the ingredients empty.
        """

    private static func stepsInstructions(_ name: String) -> String {
        """
        You pick the steps of the recipe "\(name)" out of a web page's text. Copy each step exactly \
        as it is written on the page, character for character: never write, fix, translate, \
        shorten or summarise. One step per item, in page order. Leave out anything that isn't on \
        the page. If the page holds no steps for it, leave the list empty.
        """
    }

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
                    to: text, generating: PickedRecipe.self, options: GenerationOptions(temperature: 0)
                ).content
                return PageSelection(
                    name: picked.name.isEmpty ? nil : picked.name, ingredients: picked.ingredients,
                    steps: [], yield: picked.yield, prepTime: picked.prepTime,
                    cookTime: picked.cookTime, totalTime: picked.totalTime
                )
            } catch {
                return nil
            }
        }
        #endif
        return nil
    }

    func extractSteps(_ text: String, language: String, name: String) async -> [String]? {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            guard SystemLanguageModel.default.isAvailable else { return nil }
            let session = LanguageModelSession(instructions: Self.stepsInstructions(name))
            do {
                return try await session.respond(
                    to: text, generating: PickedSteps.self, options: GenerationOptions(temperature: 0)
                ).content.steps
            } catch {
                return nil
            }
        }
        #endif
        return nil
    }
}

#if canImport(FoundationModels)
/// What the model fills in first: every field but the steps, copied from the page.
@available(iOS 26, *)
@Generable
private struct PickedRecipe {
    @Guide(description: "The recipe's name, copied from the page")
    var name: String
    @Guide(description: "Each ingredient line, copied exactly from the page")
    var ingredients: [String]
    @Guide(description: "The servings or yield as the page writes it, if it gives one")
    var yield: String?
    @Guide(description: "The preparation time as the page writes it, if it gives one")
    var prepTime: String?
    @Guide(description: "The cooking time as the page writes it, if it gives one")
    var cookTime: String?
    @Guide(description: "The total time as the page writes it, if it gives one")
    var totalTime: String?
}

/// What the model fills in second: the recipe's steps, copied from the page.
@available(iOS 26, *)
@Generable
private struct PickedSteps {
    @Guide(description: "Each step of the method, copied exactly from the page")
    var steps: [String]
}
#endif
