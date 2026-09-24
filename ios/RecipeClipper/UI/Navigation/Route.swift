import Foundation

/// Every destination above Home. Mirrors Android's nav graph: `recipe/{recipeId}`,
/// `recipe/import?url={url}` (the share target), `history`, `settings` (from Home's gear), `lists`,
/// `lists/{listId}`.
enum Route: Hashable {
    case recipe(id: Int64)
    /// From a timer notification: the recipe, opened in cook mode (Android's `recipe/{id}?cook=true`).
    case cookRecipe(id: Int64)
    case importUrl(String)
    case history
    case settings
    case lists
    case listDetail(id: Int64)
}

/// The share extension opens the app with `recipeclipper://import?url=<percent-encoded>`.
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
