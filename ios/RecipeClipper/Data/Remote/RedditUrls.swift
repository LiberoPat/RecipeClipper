import Foundation

/// Which links are Reddit's, and the `.json` address of a post. Pure string work, ported from
/// Android's `RedditUrls`: a post is a `/comments/<id>` path on reddit.com or a subdomain, or
/// the `redd.it/<id>` and `reddit.com/gallery/<id>` short forms. The Reddit app's
/// `/r/<sub>/s/<code>` share links (`/u/<name>/s/<code>` for a profile post) only redirect to
/// the post, with `?share_id=…&utm_…` added; `isShareLink` marks them and `jsonUrl` drops that
/// query.
enum RedditUrls {

    static let defaultBase = "https://www.reddit.com"

    /// `raw_json=1` returns the Markdown as written rather than HTML-escaped; `limit` bounds
    /// the comment tree for a very busy thread.
    private static let query = "?raw_json=1&limit=200"

    private static let sharePath = JRegex(#"^/(?:r|u|user)/[^/]+/s/[^/]+/?$"#)
    private static let commentsPath = JRegex(#"^(.*?/comments/[A-Za-z0-9]+)(/.*)?$"#)
    private static let id = JRegex(#"^[A-Za-z0-9]+$"#)

    private static func parts(_ url: String) -> (host: String, path: String)? {
        guard let components = URLComponents(string: url.kTrimmed),
              let host = components.host?.lowercased(), !host.isEmpty else { return nil }
        return (host, components.percentEncodedPath)
    }

    static func isReddit(_ url: String) -> Bool {
        guard let host = parts(url)?.host else { return false }
        return host == "reddit.com" || host.hasSuffix(".reddit.com") || host == "redd.it"
    }

    /// Whether an import of `url` that ended in `error` opens "Clip it yourself" on the post in
    /// place of the error screen (#213). Reddit's network-security wall answers the app's `.json`
    /// read with a 403 that waiting doesn't lift, while the clip view, a real browser engine, is
    /// let through. Only a block, and only for a link the Reddit source reads (`redditOn`, the
    /// `reddit` flag). A link saved before never gets here: the repository opens its saved copy
    /// after any failure.
    static func clipsWhenBlocked(_ url: String, error: ParseError, redditOn: Bool) -> Bool {
        guard redditOn, case .blocked = error else { return false }
        return isReddit(url)
    }

    static func isShareLink(_ url: String) -> Bool {
        guard let (host, path) = parts(url) else { return false }
        return host != "redd.it" && sharePath.matchEntire(path) != nil
    }

    /// The post's JSON listing on `base`, or nil when `url` isn't a link to one post. The query
    /// and fragment are dropped; a path to one comment keeps that comment's thread.
    static func jsonUrl(_ url: String, base: String = defaultBase) -> String? {
        guard let (host, rawPath) = parts(url) else { return nil }
        let segments = rawPath.split(separator: "/").map(String.init)
        let path: String?
        if host == "redd.it" {
            path = segments.count == 1 && id.matchEntire(segments[0]) != nil ? "/comments/\(segments[0])" : nil
        } else if segments.count == 2, segments[0] == "gallery", id.matchEntire(segments[1]) != nil {
            path = "/comments/\(segments[1])"
        } else if let m = commentsPath.matchEntire(rawPath) {
            var p = m[1] + m[2]
            while p.hasSuffix("/") { p.removeLast() }
            if p.hasSuffix(".json") { p.removeLast(5) }
            path = p
        } else {
            path = nil
        }
        guard let path else { return nil }
        var trimmedBase = base
        while trimmedBase.hasSuffix("/") { trimmedBase.removeLast() }
        return trimmedBase + path + ".json" + query
    }
}
