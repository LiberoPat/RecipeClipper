import Foundation

/// Every destination above Home. Mirrors Android's nav graph: `recipe/{recipeId}`,
/// `recipe/import?url={url}` (Paste a link and the deep link), `recipes`, `settings` (from Home's gear) and
/// its `settings/<section>` pages, `lists`,
/// `lists/{listId}`, `clip?url={url}`.
enum Route: Hashable {
    case recipe(id: Int64)
    /// From a timer notification or Home's "Resume cooking" (#286): the recipe, in cook mode
    /// (Android's `recipe/{id}?cook=true`).
    case cookRecipe(id: Int64)
    case importUrl(String)
    /// The library of every recipe (#102); it replaced History (`history`).
    case recipes
    /// One of the cookbook's automatic views (#345): Not in a list, Cooked or Typed by you, as a
    /// screen of its own (Android's `recipes/view/{view}`).
    case recipesView(RecipeFilter)
    case settings
    /// A section of Settings on its own page, from its row on the index (#286; Android's
    /// `settings/<section>`).
    case settingsSection(SettingsSection)
    /// Hidden: seven taps on the version in Settings (#87; Android's `settings/developer`).
    case developerSettings
    case lists
    case listDetail(id: Int64)
    /// Edit a recipe (#29), or with no id type a new one in (Android's `edit?recipeId=`).
    case editRecipe(id: Int64?)
    /// The Week tab's stack (#49): a planned recipe at its planned servings (Android's
    /// `week/recipe/{id}?servings=`), and the meal types screen (`week/meal-types`).
    case weekRecipe(id: Int64, servings: Int?)
    /// Home's Today card (#402): a planned recipe on the Week's stack, opened in cook mode at its
    /// planned servings (Android's `week/recipe/{id}?servings=&startCook=true`): a finished last
    /// run starts afresh, an unfinished one stays where it was.
    case startWeekRecipe(id: Int64, servings: Int?)
    case mealTypes
    /// The week's "What I need" (#51; Android's `week/need/{weekStart}`).
    case whatINeed(weekStart: Int64)
    /// "Clip it yourself" (#37) on a page with no recipe data, or opened by itself in the
    /// import's place when Reddit blocks the app's read of a post (#213: `blocked`, with a note),
    /// or for the cook to pass Cloudflare's check (#220: `check`, with a note).
    case clip(String, blocked: Bool = false, check: Bool = false)
    /// "Read the photo" (#198): the editor, filled from a Reddit post's photos read on the device
    /// (Android's `edit/photo?url=&title=&images=`).
    case photoRecipe(PhotoPost)
    /// "Scan a recipe" (#226): the same editor, filled from the cook's own pages, file URLs in
    /// order (Android's `edit/scan?pages=`).
    case scanRecipe([String])
    /// Text shared in (#306): the same editor, filled from its reading, done once in the share
    /// extension (#334), split or as written (Android's `edit/text?key=`).
    case textRecipe(SharedText.Reading)
    /// A week of recipes shared in (#352): the meal-plan review, over the text (Android's
    /// `mealplan?key=`). Saving replaces it with Week when any recipe was planned.
    case mealPlan(String)
    /// An AI's answer pasted in that was cut off before its recipe ended (#352): says so, and
    /// offers a message to ask for it again (Android's `cutoff`).
    case cutOff
}

/// `recipeclipper://import?url=<percent-encoded>` opens the import screen. The share extension
/// used to open the app this way; it now imports on its own (issue #19), so the scheme is
/// only an entry point for Shortcuts and links.
enum DeepLink {
    static let scheme = "recipeclipper"
    static let importHost = "import"

    /// The shared link carried by a deep link, or nil if the URL isn't one of ours or doesn't
    /// carry a web link. Any app or web page can open `recipeclipper://`, so the value is held
    /// to what Android's ACTION_SEND handling accepts: the first `https?://\S+` run in it. A
    /// `file:`, `javascript:` or bare-word value is refused here rather than left to fail as a
    /// fetch error on the recipe screen.
    static func sharedUrl(from url: URL) -> String? {
        guard url.scheme?.lowercased() == scheme, url.host?.lowercased() == importHost,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let value = components.queryItems?.first(where: { $0.name == "url" })?.value
        else { return nil }
        return firstWebLink(in: value)
    }

    /// The first `http(s)://…` run of non-whitespace in `text`, with at least one character
    /// after the scheme (Android's `https?://\S+`).
    static func firstWebLink(in text: String) -> String? {
        guard let range = text.range(of: #"https?://\S+"#, options: [.regularExpression, .caseInsensitive]) else {
            return nil
        }
        return String(text[range])
    }

    /// The deep link for a shared recipe link. The value is encoded strictly (only RFC 3986
    /// unreserved characters pass), so a recipe URL's own `&`, `=`, `+` and `#` survive.
    static func importUrl(for shared: String) -> URL? {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let encoded = shared.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
        return URL(string: "\(scheme)://\(importHost)?url=\(encoded)")
    }
}
