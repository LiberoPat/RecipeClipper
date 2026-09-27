import Foundation

/// The site a recipe came from, as the reading view credits it: "smittenkitchen.com" for
/// `https://www.smittenkitchen.com/2024/01/some-recipe/`. Pure: string in, string out.
/// Port of Android's `SourceDomain`.
///
/// Only the host is kept: no scheme, user info, port, path, query or fragment. It is
/// lowercased, and one leading "www." is dropped because it tells a reader nothing. Every other
/// subdomain ("cooking.nytimes.com", "m.allrecipes.com") stays, since it can be the part that
/// names the site. Nil when there is no recognisable host, so the screen shows no credit rather
/// than a wrong one.
enum SourceDomain {

    static func of(_ url: String) -> String? {
        let trimmed = url.kTrimmed
        guard let schemeEnd = trimmed.range(of: "://"), schemeEnd.lowerBound > trimmed.startIndex else {
            return nil
        }

        let authority = before(
            before(before(String(trimmed[schemeEnd.upperBound...]), "/"), "?"),
            "#"
        )
        let hostAndPort = authority.lastIndex(of: "@").map { String(authority[authority.index(after: $0)...]) }
            ?? authority
        // An IPv6 literal carries colons of its own; its port, if any, follows the bracket.
        let host = hostAndPort.hasPrefix("[")
            ? before(hostAndPort, "]") + (hostAndPort.contains("]") ? "]" : "")
            : before(hostAndPort, ":")

        var domain = host.lowercased()
        if domain.hasPrefix("www.") { domain.removeFirst(4) }
        if domain.hasSuffix(".") { domain.removeLast() }
        return domain.isEmpty ? nil : domain
    }

    /// Kotlin's `substringBefore`: everything before the first `separator`, or all of it.
    private static func before(_ string: String, _ separator: Character) -> String {
        string.firstIndex(of: separator).map { String(string[..<$0]) } ?? string
    }
}
