import Foundation

/// Turns what was typed or pasted into a link to fetch, or nil if it isn't one. Pure.
enum UrlInput {

    private static let whitespace = JRegex(#"\s+"#)
    private static let sharedLink = JRegex(#"https?://\S+"#)

    static func normalize(_ text: String) -> String? {
        let token = whitespace.split(text.kTrimmed).first ?? ""
        if token.isEmpty { return nil }
        let lower = token.lowercased()
        if lower.hasPrefix("http://") || lower.hasPrefix("https://") {
            return token.u16Count > 8 ? token : nil
        }
        // "seriouseats.com/recipe": a host and path with the scheme left off.
        if token.contains(".") && !token.hasPrefix(".") && !token.hasSuffix(".") {
            return "https://" + token
        }
        return nil
    }

    /// Finds the first http(s) link in shared text (the share extension / URL scheme path).
    /// Mirrors Android's `MainActivity.extractUrl`: apps often share "Look at this! <link>",
    /// so the link is picked out of the text rather than expected to be all of it.
    static func extractSharedUrl(_ text: String) -> String? {
        sharedLink.find(text)?.value
    }
}
