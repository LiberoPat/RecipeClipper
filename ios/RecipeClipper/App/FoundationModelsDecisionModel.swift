import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Typed decisions by Apple's on-device Foundation Model (#104): iOS 26 or later, Apple
/// Intelligence on. Guided generation constrains each reply to the kind's options and a
/// confidence, so nothing is parsed; `DecisionRule` still checks it. A fresh session per ask,
/// temperature 0. Any failure (a guardrail, a language it won't read) is nil: asked again later.
final class FoundationModelsDecisionModel: DecisionModel {

    private static let recipeLanguages: Set<String> = ["en", "de", "es", "fr", "it", "pt", "ja"]

    func supports(language: String) async -> Bool {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            let model = SystemLanguageModel.default
            return model.isAvailable && Self.recipeLanguages.contains(language)
                && model.supportedLanguages.contains(where: { $0.languageCode?.identifier == language })
        }
        #endif
        return false
    }

    func ask(_ prompt: DecisionPrompt) async -> DecisionReply? {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            guard SystemLanguageModel.default.isAvailable else { return nil }
            let session = LanguageModelSession(instructions: prompt.instructions)
            let options = GenerationOptions(temperature: 0)
            do {
                switch prompt.kind {
                case .countBracket:
                    let r = try await session.respond(to: prompt.text, generating: CountReply.self, options: options).content
                    return DecisionReply(answer: "\(r.answer)", confidence: "\(r.confidence)")
                case .sameIngredient:
                    let r = try await session.respond(to: prompt.text, generating: SameReply.self, options: options).content
                    return DecisionReply(answer: "\(r.answer)", confidence: "\(r.confidence)")
                case .aisle:
                    let r = try await session.respond(to: prompt.text, generating: AisleReply.self, options: options).content
                    return DecisionReply(answer: "\(r.answer)", confidence: "\(r.confidence)")
                case .sameGrocery:
                    let r = try await session.respond(to: prompt.text, generating: SameReply.self, options: options).content
                    return DecisionReply(answer: "\(r.answer)", confidence: "\(r.confidence)")
                case .trailingText:
                    let r = try await session.respond(to: prompt.text, generating: TrailingReply.self, options: options).content
                    return DecisionReply(answer: "\(r.answer)", confidence: "\(r.confidence)")
                case .ingredientName:
                    let r = try await session.respond(to: prompt.text, generating: NameReply.self, options: options).content
                    return DecisionReply(answer: r.answer, confidence: "\(r.confidence)")
                }
            } catch {
                return nil
            }
        }
        #endif
        return nil
    }
}

#if canImport(FoundationModels)
// The kinds' options as generable enums: the model can only pick one of them. Their case
// names are the option strings `DecisionKind.options` lists, plus unsure.
@available(iOS 26, *) @Generable enum ReplyConfidence { case high, medium, low }
@available(iOS 26, *) @Generable enum CountAnswer { case total, each, unsure }
@available(iOS 26, *) @Generable enum SameAnswer { case same, different, unsure }
@available(iOS 26, *) @Generable enum AisleAnswer {
    case produce, meat, seafood, dairy, bakery, baking, grains, canned, condiments, spices, frozen, snacks, drinks, other, unsure
}

@available(iOS 26, *) @Generable struct CountReply {
    var answer: CountAnswer
    var confidence: ReplyConfidence
}

@available(iOS 26, *) @Generable struct SameReply {
    var answer: SameAnswer
    var confidence: ReplyConfidence
}

// Case names are the option strings, so "second_amount" keeps its underscore.
@available(iOS 26, *) @Generable enum TrailingAnswer { case note, second_amount, junk, unsure }

@available(iOS 26, *) @Generable struct TrailingReply {
    var answer: TrailingAnswer
    var confidence: ReplyConfidence
}

// Free text: the name as written in the line, or "unsure". `GroceryDecisions.nameSplit` checks it.
@available(iOS 26, *) @Generable struct NameReply {
    @Guide(description: "The ingredient's name, copied exactly from the line, or unsure")
    var answer: String
    var confidence: ReplyConfidence
}

@available(iOS 26, *) @Generable struct AisleReply {
    var answer: AisleAnswer
    var confidence: ReplyConfidence
}
#endif
