import Foundation

/// What the recipe-card plugin adapters share (WP Recipe Maker #118, Tasty Recipes and Mediavine
/// Create #119): a card's ingredients, read as groups, refine JSON-LD's `recipeIngredient` lines
/// only when they line up one-to-one with them. A named group adds a heading line ("Batter:")
/// before its ingredients, the colon form the app reads as a heading everywhere else. Pure. A
/// port of Android's `CardIngredients`.
enum CardIngredients {

    struct Group { let name: String; let items: [Item] }

    /// One ingredient on the card: `key`, text its JSON-LD line must hold; `line`, the line to show (nil: JSON-LD's).
    struct Item { let key: String; let line: String? }

    /// `groups` as lines, with their headings, if their items line up with `lines`: the same count,
    /// and each item's key found in its JSON-LD line once both are normalized. Otherwise nil.
    static func lineUp(_ groups: [Group], _ lines: [String], normalize: (String) -> String) -> [String]? {
        let items = groups.flatMap(\.items)
        if items.isEmpty || items.count != lines.count { return nil }
        let linesUp = items.indices.allSatisfy { i in
            let key = normalize(items[i].key)
            return !key.isEmpty && normalize(lines[i]).contains(key)
        }
        if !linesUp { return nil }
        var index = 0
        return groups.flatMap { g -> [String] in
            var heading = g.name
            if heading.hasSuffix(":") { heading.removeLast() }
            heading = heading.kTrimmed
            let body = g.items.map { item -> String in
                defer { index += 1 }
                return item.line ?? lines[index]
            }
            return (heading.isEmpty ? [] : [heading + ":"]) + body
        }
    }

    /// An element's text, runs of whitespace (a no-break space too) as one space.
    static func text(_ tree: HtmlTree, _ index: Int) -> String {
        tree.text(of: index).split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    /// Elements inside `index` (itself included, as Jsoup's `select` does) that match, in document order.
    static func within(_ tree: HtmlTree, _ index: Int, _ match: (HtmlTree.Element) -> Bool) -> [Int] {
        ([index] + Array(tree.descendants(of: index))).filter { match(tree.elements[$0]) }
    }
}
