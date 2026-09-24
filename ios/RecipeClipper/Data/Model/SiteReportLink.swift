import Foundation

/// "Report this site": a prefilled new-issue link on the project's GitHub, offered only when a
/// page gave `.noRecipeFound`. The app has no analytics, so this is how the owner learns which
/// sites fail. Nothing is sent: the link only opens a draft in the browser, and the user
/// decides whether to submit it. Pure: strings in, string out. Port of Android's `SiteReportLink`,
/// and produces the same bytes.
///
/// The body is English by design, like `RecipeShareText`: it is read by the maintainer, not
/// shown as UI. The link is run through `UrlCleaner` first, so tracking tags never end up in a
/// public issue. The title names the site through `SourceDomain`, the same helper the reading
/// view uses for its source credit.
enum SiteReportLink {

    static let newIssueUrl = "https://github.com/LiberoPat/RecipeClipper/issues/new"
    static let label = "site-report"

    /// `platform` is e.g. "iOS 17.5" and `appVersion` e.g. "1.0 (1)"; both come from the
    /// platform through `AppInfo`, so this stays testable.
    static func issueUrl(link: String, platform: String, appVersion: String) -> String {
        let cleaned = UrlCleaner.clean(link)
        let title = "Site not supported: \(SourceDomain.of(cleaned) ?? cleaned)"
        let body = [
            "Recipe Clipper found no recipe on this page.",
            "",
            "Link: \(cleaned)",
            "Platform: \(platform)",
            "App version: \(appVersion)",
        ].joined(separator: "\n")
        return newIssueUrl
            + "?title=" + percentEncode(title)
            + "&body=" + percentEncode(body)
            + "&labels=" + percentEncode(label)
    }

    /// RFC 3986 percent-encoding of UTF-8 bytes: everything but the unreserved characters
    /// (A–Z, a–z, 0–9, "-", ".", "_", "~") is escaped, so a space is "%20" (never "+"), and
    /// "&", "=", "#" and "?" in the link can't break out of their query parameter. Written out
    /// rather than `addingPercentEncoding`, whose allowed sets differ from Android's rule.
    static func percentEncode(_ value: String) -> String {
        let hex = Array("0123456789ABCDEF")
        var out = ""
        for byte in value.utf8 {
            switch byte {
            case UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "a")...UInt8(ascii: "z"),
                 UInt8(ascii: "0")...UInt8(ascii: "9"),
                 UInt8(ascii: "-"), UInt8(ascii: "."), UInt8(ascii: "_"), UInt8(ascii: "~"):
                out.append(Character(UnicodeScalar(byte)))
            default:
                out.append("%")
                out.append(hex[Int(byte >> 4)])
                out.append(hex[Int(byte & 0x0F)])
            }
        }
        return out
    }
}
