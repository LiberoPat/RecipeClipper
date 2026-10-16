import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// A recipe picked from a page's text by Apple's on-device Foundation Model (#103): iOS 26 or
/// later, Apple Intelligence on. Guided generation (`@Generable`) returns the fields typed, so no
/// reply is parsed; `PageRecipeCheck` then keeps only what is on the page. A fresh session per
/// attempt. Any failure (a guardrail, a language it won't read, a page over its context) is nil,
/// and the page stays "no recipe found" as before; a reply that runs away or can't be read is
/// asked for once more (`PageReplyRetry`, #273).
final class FoundationModelsPageRecipeExtractor: PageRecipeExtractor {

    /// The recipe languages the app has words for (shared/tables).
    private static let recipeLanguages: Set<String> = ["en", "de", "es", "fr", "it", "pt", "ja"]

    /// Tokens kept for the instructions, the schema and the reply, out of `contextSize`
    /// (4,096 on iOS 26, 8,192 on 27).
    private static let reservedTokens = 1_800

    private static let instructions = """
        You pick a recipe out of a web page's text. Copy every value exactly as it is written on \
        the page, character for character: never write, fix, translate, shorten or summarise. One \
        ingredient line per item and one step per item, in page order. Leave out anything that \
        isn't on the page. If the page holds no recipe, leave the name and the lists empty.
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
            return await PageReplyRetry.pick { retry in await Self.ask(text, retry: retry) }
        }
        #endif
        return nil
    }

    #if canImport(FoundationModels)
    /// One reply, streamed so a runaway (`RunawayReply`, #273) is stopped as soon as it shows
    /// rather than at the context's end. A reply cut off or unreadable is `.unreadable`, worth
    /// one retry; any other error (a guardrail, rate limited, a language it won't read) is
    /// `.failed`, never retried.
    @available(iOS 26, *)
    private static func ask(_ text: String, retry: Bool) async -> PageReply {
        let session = LanguageModelSession(instructions: retry ? instructions + " " + PageReplyRetry.reminder : instructions)
        let options = GenerationOptions(
            temperature: 0, maximumResponseTokens: retry ? PageReplyRetry.retryReplyTokens : nil
        )
        do {
            var last: PageSelection?
            for try await snapshot in session.streamResponse(to: text, generating: PickedRecipe.self, options: options) {
                let picked = snapshot.content
                let selection = PageSelection(
                    name: picked.name, ingredients: picked.ingredients ?? [], steps: picked.steps ?? [],
                    yield: picked.yield ?? nil, prepTime: picked.prepTime ?? nil, cookTime: picked.cookTime ?? nil,
                    totalTime: picked.totalTime ?? nil
                )
                if RunawayReply.isRunaway(selection) { return .unreadable }
                last = selection
            }
            guard var selection = last else { return .failed }
            if selection.name?.isEmpty == true { selection.name = nil }
            return .picked(selection)
        } catch {
            return isUnreadable(error) ? .unreadable : .failed
        }
    }

    /// A reply that ran into the context's or the reply cap's end, or that couldn't be decoded.
    @available(iOS 26, *)
    private static func isUnreadable(_ error: Error) -> Bool {
        if #available(iOS 27, *) {
            if case LanguageModelError.contextSizeExceeded = error { return true }
            if error is GeneratedContent.ParsingError { return true }
        }
        guard let error = error as? LanguageModelSession.GenerationError else { return false }
        switch error {
        case .exceededContextWindowSize, .decodingFailure: return true
        default: return false
        }
    }
    #endif
}

#if canImport(FoundationModels)
/// What the model fills in: every field copied from the page.
@available(iOS 26, *)
@Generable
private struct PickedRecipe {
    @Guide(description: "The recipe's name, copied from the page")
    var name: String
    @Guide(description: "Each ingredient line, copied exactly from the page")
    var ingredients: [String]
    @Guide(description: "Each step of the method, copied exactly from the page")
    var steps: [String]
    @Guide(description: "The servings or yield as the page writes it, if it gives one")
    var yield: String?
    @Guide(description: "The preparation time as the page writes it, if it gives one")
    var prepTime: String?
    @Guide(description: "The cooking time as the page writes it, if it gives one")
    var cookTime: String?
    @Guide(description: "The total time as the page writes it, if it gives one")
    var totalTime: String?
}
#endif
