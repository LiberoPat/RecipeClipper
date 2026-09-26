import Foundation

/// Tasty Recipes' and Mediavine Create's ingredient cards (#119), read only for the group headings
/// their JSON-LD drops. Unlike WP Recipe Maker, neither marks an ingredient's parts: each card item
/// is the whole line, as in JSON-LD, so JSON-LD's lines stay exactly as they are, and a named group
/// adds a heading line ("For the crust:") before its ingredients.
///
/// Headings are what the card shows between its lists: a heading element, or a paragraph that ends
/// in a colon or is wholly bold (Tasty's own test for what to leave out of its JSON-LD). Used only
/// when the card's items line up one-to-one with JSON-LD's `recipeIngredient`: the same count, and
/// each item's letters and digits found in its JSON-LD line. Pure. A port of Android's
/// `CardHeadings`, pinned to it by the differential corpus.
enum CardHeadings {

    /// Each plugin's ingredient list, and the element inside it that titles the whole list.
    private static let cards = [
        ("tasty-recipes-ingredients", "tasty-recipes-ingredients-header"),
        ("mv-create-ingredients", "mv-create-ingredients-title"),
    ]

    private static let headingTags: Set<String> = ["h1", "h2", "h3", "h4", "h5", "h6"]

    /// `lines`, with the headings of the first Tasty Recipes or Mediavine Create list in `html` that lines up with them.
    static func refine(html: String, lines: [String]) -> [String] {
        guard cards.contains(where: { html.contains($0.0) }) else { return lines }
        let tree = HtmlTree(html)
        for (list, title) in cards {
            for container in tree.elements.indices where tree.elements[container].hasClass(list) {
                guard let groups = read(tree, container, title),
                      let refined = CardIngredients.lineUp(groups, lines, normalize: letters) else { continue }
                return refined
            }
        }
        return lines
    }

    /// The list's groups, in page order, or nil if an item is empty.
    private static func read(_ tree: HtmlTree, _ container: Int, _ title: String) -> [CardIngredients.Group]? {
        var names = [""]
        var items: [[CardIngredients.Item]] = [[]]
        for e in CardIngredients.within(tree, container, { $0.name == "li" || $0.name == "p" || headingTags.contains($0.name) }) {
            // Not the list's title, and not a paragraph inside an item.
            var node: Int? = e
            var skip = false
            while let n = node, n != container, !skip {
                skip = tree.elements[n].hasClass(title) || (n != e && tree.elements[n].name == "li")
                node = tree.elements[n].parent
            }
            if skip { continue }
            if tree.elements[e].name == "li" {
                let key = CardIngredients.text(tree, e)
                if key.isEmpty { return nil }
                items[items.count - 1].append(CardIngredients.Item(key: key, line: nil))
            } else if let heading = heading(tree, e) {
                names.append(heading)
                items.append([])
            }
        }
        var groups = names.indices.map { CardIngredients.Group(name: names[$0], items: items[$0]) }
        // A heading after the last ingredient heads nothing.
        while groups.last?.items.isEmpty == true { groups.removeLast() }
        return groups
    }

    private static func heading(_ tree: HtmlTree, _ e: Int) -> String? {
        let text = CardIngredients.text(tree, e)
        let element = tree.elements[e]
        let children = element.children.compactMap { child -> Int? in
            if case let .element(i) = child { return i }
            return nil
        }
        if text.isEmpty { return nil }
        if headingTags.contains(element.name) || text.hasSuffix(":") { return text }
        if children.count == 1, ["strong", "b"].contains(tree.elements[children[0]].name),
           CardIngredients.text(tree, children[0]) == text {
            return text
        }
        return nil
    }

    private static let letterOrDigit: Set<Unicode.GeneralCategory> = [
        .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber,
    ]

    /// Lowercased letters and digits only (Java's `isLetterOrDigit`), so curled quotes, dashes and spacing don't count.
    private static func letters(_ s: String) -> String {
        var scalars = String.UnicodeScalarView()
        for u in s.lowercased().unicodeScalars where letterOrDigit.contains(u.properties.generalCategory) {
            scalars.append(u)
        }
        return String(scalars)
    }
}
