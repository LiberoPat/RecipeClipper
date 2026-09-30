import Foundation

/// Recognises Cloudflare's bot check (#220): the "Just a moment..." page a site behind Cloudflare
/// shows while it decides whether the visitor is a browser, and the "Verify you are human" box it
/// shows when it wants a person. Pure: text in, a yes or no out. Android's `CloudflareChallenge`;
/// both read the pages in `shared/fixtures/cloudflare`.
///
/// Only Cloudflare's own challenge markers count: `window._cf_chl_opt`, a `__cf_chl_` token, the
/// challenge's `orchestrate` script under `/cdn-cgi/challenge-platform/`, or the title
/// "Just a moment..." on a page that names Cloudflare. Not a challenge: the "jsd" script
/// Cloudflare adds to ordinary pages (`/cdn-cgi/challenge-platform/scripts/jsd/`), a Turnstile
/// widget on a comment form, or the firewall block ("Sorry, you have been blocked").
enum CloudflareChallenge {
    private static let orchestrate = try! NSRegularExpression(
        pattern: #"/cdn-cgi/challenge-platform/(?:[\w.-]+/)*orchestrate/"#
    )
    private static let title = try! NSRegularExpression(
        pattern: #"<title[^>]*>\s*Just a moment(?:\.\.\.|…)\s*</title>"#, options: [.caseInsensitive]
    )
    private static let challengeStatuses: Set<Int> = [403, 429, 503]

    /// Whether `html` is Cloudflare's challenge page rather than the page asked for.
    static func isChallengePage(_ html: String) -> Bool {
        if html.contains("_cf_chl_opt") || html.contains("__cf_chl_") { return true }
        let range = NSRange(html.startIndex..., in: html)
        if orchestrate.firstMatch(in: html, range: range) != nil { return true }
        return title.firstMatch(in: html, range: range) != nil
            && html.range(of: "cloudflare", options: .caseInsensitive) != nil
    }

    /// Whether a refused plain fetch was Cloudflare's challenge: its `cf-mitigated: challenge`
    /// header, or a challenge page as the body of a 403, 429 or 503.
    static func isChallengeResponse(status: Int, cfMitigated: String?, body: String?) -> Bool {
        if cfMitigated?.trimmingCharacters(in: .whitespaces).lowercased() == "challenge" { return true }
        guard challengeStatuses.contains(status), let body else { return false }
        return isChallengePage(body)
    }
}
