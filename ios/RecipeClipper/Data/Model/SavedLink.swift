import Foundation

/// Finding the saved recipe a plan names by its link (#367): an AI told to name a recipe of the cook's
/// by the link it was given (`AskAiPrompt.PlanCandidate.link`, the recipe's `sourceUrl`) writes it
/// back as `isBasedOn` or `url`. The match is the same as a version's original (#354): the link as
/// written, else, for an `http` link, cleaned by `UrlCleaner` (an AI may keep or drop tracking).
/// Nothing else matches: a link that names nothing here is no recipe. Ported from Android's `SavedLink`.
enum SavedLink {

    /// The recipe of `saved` that `link` names, or nil.
    static func find(_ link: String, in saved: [RecipeSummary]) -> RecipeSummary? {
        let written = link.kTrimmed
        if written.isEmpty { return nil }
        if let match = saved.first(where: { $0.sourceUrl == written }) { return match }
        guard written.lowercased().hasPrefix("http") else { return nil }
        let cleaned = UrlCleaner.clean(written)
        return saved.first { $0.sourceUrl == cleaned }
    }
}
