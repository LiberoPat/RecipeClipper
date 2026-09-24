import Foundation

/// Turns a link into the form the app saves it under, so the same recipe reached through
/// different tracking tags is one recipe, not several. Pure: string in, string out.
///
/// Only things that cannot change which page you get are removed: well-known tracking
/// parameters, the #fragment (never sent to the server), and capital letters in the scheme
/// and host (which are case-insensitive). Every other parameter is kept, in its original
/// order and spelling, because some sites use them to say which recipe you mean.
enum UrlCleaner {

    private static let trackingParameters: Set<String> = [
        "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "yclid", "twclid", "ttclid",
        "igshid", "mc_cid", "mc_eid", "mkt_tok", "li_fat_id", "vero_id", "_ga", "_gl",
        "oly_enc_id", "oly_anon_id", "wickedid",
    ]

    static func clean(_ url: String) -> String {
        let trimmed = url.kTrimmed
        let withoutFragment = trimmed.firstIndex(of: "#").map { String(trimmed[..<$0]) } ?? trimmed
        // UTF-16 offsets throughout, exactly as Kotlin's indexOf/substring use them.
        let ns = withoutFragment as NSString
        let schemeEnd = ns.range(of: "://").location
        if schemeEnd == NSNotFound { return withoutFragment } // not a link we understand; leave it alone

        let queryStart = ns.range(of: "?").location
        let beforeQuery = queryStart == NSNotFound ? withoutFragment : ns.substring(to: queryStart)
        let query = queryStart == NSNotFound ? "" : ns.substring(from: queryStart + 1)

        // Lowercase "https://Host.com" but not the path after it, which is case-sensitive.
        let bq = beforeQuery as NSString
        let searchFrom = schemeEnd + 3
        let pathStart = searchFrom >= bq.length
            ? NSNotFound
            : bq.range(of: "/", range: NSRange(location: searchFrom, length: bq.length - searchFrom)).location
        let origin = pathStart == NSNotFound ? beforeQuery : bq.substring(to: pathStart)
        let path = pathStart == NSNotFound ? "" : bq.substring(from: pathStart)

        // Upgrading the scheme (rather than allowing cleartext) also means one recipe is one
        // row whichever scheme it arrived under.
        let lowerOrigin = origin.lowercased()
        let upgradedOrigin = lowerOrigin.hasPrefix("http://")
            ? "https://" + lowerOrigin.dropFirst("http://".count)
            : lowerOrigin

        let kept = query.split(separator: "&", omittingEmptySubsequences: false)
            .map(String.init)
            .filter { part in
                let name = part.firstIndex(of: "=").map { String(part[..<$0]) } ?? part
                return !part.isEmpty && !isTracking(name)
            }
        return upgradedOrigin + path + (kept.isEmpty ? "" : "?" + kept.joined(separator: "&"))
    }

    private static func isTracking(_ name: String) -> Bool {
        let lower = name.lowercased()
        return lower.hasPrefix("utm_") || trackingParameters.contains(lower)
    }
}
