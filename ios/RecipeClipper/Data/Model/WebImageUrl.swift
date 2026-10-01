import Foundation

/// A photo's address taken from a web page (#235): the photo tapped in "Clip it yourself", a
/// recipe's JSON-LD or microdata `image`, `og:image`, a Reddit post's picture. Only a web image
/// is kept: `https` with a host, `http` upgraded as `UrlCleaner` upgrades a link. Anything else
/// (`file:`, `content:`, `data:`, `javascript:`, a relative or blank address) is no image, so a
/// page can never make `ImageLoader` (which reads a file URL where it is) load something from
/// the device. Pure: Android's `WebImageUrl`, the same rule, pinned by the differential corpus.
enum WebImageUrl {

    static func of(_ address: String?) -> String? {
        guard let address else { return nil }
        // UTF-16 offsets throughout, as Kotlin's indexOf/substring use them.
        let trimmed = address.kTrimmed as NSString
        let schemeEnd = trimmed.range(of: "://").location
        guard schemeEnd != NSNotFound else { return nil }
        let scheme = trimmed.substring(to: schemeEnd).lowercased()
        guard scheme == "https" || scheme == "http" else { return nil }
        let rest = trimmed.substring(from: schemeEnd + 3) as NSString
        let authorityEnd = rest.rangeOfCharacter(from: CharacterSet(charactersIn: "/?#")).location
        let authority = (authorityEnd == NSNotFound ? rest as String : rest.substring(to: authorityEnd)) as NSString
        let at = authority.range(of: "@", options: .backwards).location
        let hostAndPort = (at == NSNotFound ? authority as String : authority.substring(from: at + 1)) as NSString
        let colon = hostAndPort.range(of: ":").location
        let host = colon == NSNotFound ? hostAndPort as String : hostAndPort.substring(to: colon)
        guard !host.isEmpty else { return nil }
        return "https://" + (rest as String)
    }
}
