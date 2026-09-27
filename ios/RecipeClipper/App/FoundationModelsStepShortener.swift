import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Chef mode's short steps from Apple's on-device Foundation Model (#100): iOS 26 or later, on an
/// Apple Intelligence device with it turned on. Each step gets a fresh session (a session keeps a
/// transcript, and steps don't need each other), one at a time. Any failure (a guardrail, a
/// language it won't write, the model busy) is "not now": nil, never an error on screen.
final class FoundationModelsStepShortener: StepShortener {

    /// The recipe languages the app has words for (shared/tables).
    private static let recipeLanguages: Set<String> = ["en", "de", "es", "fr", "it", "pt", "ja"]

    private static let instructions = """
        You shorten one step of a recipe for someone cooking it right now. Reply with the short \
        step only, in the same language as the step. Keep every number, amount, time, temperature \
        and unit exactly as written. Add nothing that isn't in the step, and don't number it.
        """

    func support() async -> ChefSupport {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            let model = SystemLanguageModel.default
            let availability = model.availability
            if case .available = availability {
                let languages = Set(model.supportedLanguages.compactMap { $0.languageCode?.identifier })
                return .available(languages.intersection(Self.recipeLanguages))
            }
            if case .unavailable(.appleIntelligenceNotEnabled) = availability { return .notEnabled }
            if case .unavailable(.modelNotReady) = availability { return .notReady }
        }
        #endif
        return .unsupported
    }

    func shorten(_ step: String, language: String) async -> String? {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            guard SystemLanguageModel.default.isAvailable else { return nil }
            let session = LanguageModelSession(instructions: Self.instructions)
            do {
                return try await session.respond(to: step, options: GenerationOptions(temperature: 0)).content
            } catch {
                return nil
            }
        }
        #endif
        return nil
    }
}
