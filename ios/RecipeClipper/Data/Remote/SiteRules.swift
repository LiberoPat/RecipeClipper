import Foundation

/// Where a page says more than its JSON-LD (#120), from `shared/tables/site-rules.json`: data
/// only, read by both apps, so a site's quirk is a table edit, not code. `cards` are ingredient
/// cards any site may have (recipe plugins, #119); a site's own rules, by host without "www.",
/// come first. A rule can only add the group headings JSON-LD drops (`CardHeadings`, and only
/// when the card lines up with JSON-LD's lines) or drop known noise from the end of the last
/// step. Pure. A port of Android's `SiteRules` (its site-check `check` is Android's alone),
/// pinned to it by the differential corpus.
enum SiteRules {

    struct Site { let cards: [CardHeadings.Card]; let stepNoise: [String] }

    private static let table = SharedTables.read("site-rules")

    /// Raised on every edit of the table, so a copy fetched later can tell which is newer.
    static let version: Int = {
        guard let version = table["version"] as? Int else { fatalError("site-rules.json has no version") }
        return version
    }()

    /// The ingredient cards any site may have: Tasty Recipes', Mediavine Create's.
    static let cards: [CardHeadings.Card] = SharedTables.objects(table, "cards").map(card)

    private static let sites: [String: Site] = (table["sites"] as? [String: SharedTables.Table] ?? [:]).mapValues {
        Site(cards: SharedTables.objects($0, "cards").map(card), stepNoise: SharedTables.strings($0, "stepNoise"))
    }

    /// The rules for `url`'s site, if it has any.
    static func site(url: String) -> Site? { SourceDomain.of(url).flatMap { sites[$0] } }

    /// `lines`, with the headings of `url`'s site's cards, else of any site's; as they are if none lines up.
    static func ingredients(html: String, url: String, lines: [String]) -> [String] {
        CardHeadings.refine(html: html, lines: lines, cards: (site(url: url)?.cards ?? []) + cards)
    }

    /// `steps`, without `url`'s site's known noise at the end of the last one.
    static func steps(url: String, steps: [String]) -> [String] {
        site(url: url).map { dropNoise(steps, phrases: $0.stepNoise) } ?? steps
    }

    /// `steps`, with the last one cut where one of `phrases` first starts it or follows a space
    /// in it (an editor's note after the method). A last step that was all noise goes, unless it
    /// was the only one.
    static func dropNoise(_ steps: [String], phrases: [String]) -> [String] {
        guard let last = steps.last, let at = phrases.compactMap({ noiseStart(last, $0) }).min() else { return steps }
        let kept = last.u16Substring(0, at).kTrimmed
        if !kept.isEmpty { return Array(steps.dropLast()) + [kept] }
        if steps.count > 1 { return Array(steps.dropLast()) }
        return steps
    }

    /// Where `phrase` first starts `step` or follows a space in it, in UTF-16 units (Kotlin's `indexOf`).
    private static func noiseStart(_ step: String, _ phrase: String) -> Int? {
        if phrase.isEmpty { return nil }
        let ns = step as NSString
        var from = 0
        while from <= ns.length {
            let found = ns.range(of: phrase, options: .literal, range: NSRange(location: from, length: ns.length - from))
            if found.location == NSNotFound { return nil }
            if found.location == 0 || ns.character(at: found.location - 1) == 0x20 { return found.location }
            from = found.location + 1
        }
        return nil
    }

    /// A card from the table. A selector outside the subset `CardSelector` reads is a mistake in
    /// the table, not a runtime condition, so it traps (Android throws).
    private static func card(_ o: SharedTables.Table) -> CardHeadings.Card {
        func selector(_ key: String) -> CardSelector? {
            guard let text = o[key] as? String, !text.isEmpty else { return nil }
            guard let selector = CardSelector(text) else { fatalError("Unsupported selector \"\(text)\" in site-rules.json") }
            return selector
        }
        func required(_ key: String) -> CardSelector {
            guard let selector = selector(key) else { fatalError("A card in site-rules.json has no \"\(key)\"") }
            return selector
        }
        return CardHeadings.Card(
            list: required("list"), item: required("item"), heading: required("heading"),
            title: selector("title"), amount: selector("amount")
        )
    }
}
