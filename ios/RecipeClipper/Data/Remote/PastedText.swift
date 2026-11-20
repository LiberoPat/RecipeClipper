import Foundation

/// Where a pasted text leads (#372). Android's `PasteTarget`.
enum PasteTarget: Equatable {
    /// A link: today's import.
    case link(String)
    /// The editor, on the text's reading, done once.
    case review(SharedText.Reading)
    /// The meal-plan review (#352), over the text.
    case plan(String)
    /// A bulleted list (#149): offered on Groceries' "Add this list" sheet.
    case list(String)
    /// An AI's answer cut off before its recipe ended (#352): the cut-off screen.
    case cutOff
}

/// Where text goes when it is pasted (#372) into "Paste a link" or "Paste the answer": one
/// decision for both, the one a share makes (Android's `TextIntent.target`, #306 text, #350 JSON,
/// #352 a week, #354 versions), so a paste lands exactly where the same text shared would. Pure,
/// and nothing is saved here: the editor's and the review's Save are the only writes. Android's
/// `PastedTextRouter`.
struct PastedTextRouter {
    var shareTextOn: () -> Bool = { true }
    var mealPlanOn: () -> Bool = { true }

    /// One token: a link, or a host and path with the scheme left off ("seriouseats.com/x"),
    /// never JSON or "1.5".
    private static let linkAlone = JRegex(
        #"https?://\S+|[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.[a-z]{2,}(?::\d+)?(?:[/?#]\S*)?"#, ignoreCase: true
    )

    /// Where `text` leads, as a share of it would: with shareText on, a week of recipes with days
    /// (when mealPlanImport is on) opens the meal-plan review; a recipe, or text that is neither
    /// a link nor a list, opens the editor; a link imports; a list is offered. Otherwise, as
    /// before #306: the first link imports, and lines are offered as a list. Nil: nowhere.
    func route(_ text: String) -> PasteTarget? {
        if shareTextOn() {
            if mealPlanOn(), MealPlanImport.read(text) != nil { return .plan(text) }
            switch SharedText.route(text) {
            case .editor(let reading):
                // An AI's answer that stopped inside its first recipe (#352): a fragment is no recipe.
                if !reading.fromChat && SharedText.isCutOff(text) { return .cutOff }
                return .review(reading)
            case .link(let url): return .link(url)
            case .list: return .list(text)
            case .none: return nil
            }
        }
        if text.kIsBlank { return nil }
        if let url = SharedText.link(text) { return .link(url) }
        return ReceivedList.lines(text).isEmpty ? nil : .list(text)
    }

    /// What "Paste a link" does with `text` (#372): a lone link, with or without its scheme,
    /// imports as it always has; anything else goes where the same text shared would.
    func open(_ text: String) -> PasteTarget? {
        let trimmed = text.kTrimmed
        if trimmed.isEmpty { return nil }
        if Self.linkAlone.matchEntire(trimmed) != nil, let url = UrlInput.normalize(trimmed) { return .link(url) }
        return route(text)
    }
}
