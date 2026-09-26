import Foundation

/// Ingredient cards that hold whole lines, read only for the group headings their JSON-LD drops:
/// Tasty Recipes' and Mediavine Create's (#119), and the big sites' own (#120). Unlike WP Recipe
/// Maker, none marks an ingredient's parts: each card item is the whole line, as in JSON-LD, so
/// JSON-LD's lines stay exactly as they are, and a named group adds a heading line ("For the
/// crust:") before its ingredients.
///
/// What a card is comes from `shared/tables/site-rules.json` (`SiteRules`), as selectors. A
/// heading is what the card shows between its lists: one its selector finds that is a heading
/// element, ends in a colon or is wholly bold (Tasty's own test for what to leave out of its
/// JSON-LD). Used only when the card's items line up one-to-one with JSON-LD's
/// `recipeIngredient`: the same count, and each item's letters and digits found in its JSON-LD
/// line. Pure. A port of Android's `CardHeadings`, pinned to it by the differential corpus.
enum CardHeadings {

    /// One kind of ingredient card: `list` holds it; `item` is one ingredient and `heading` a
    /// group's name, both looked for only inside the list, never inside an item or the list's
    /// `title`; `amount` is left out of an item's text when matching it to its JSON-LD line.
    struct Card {
        let list: CardSelector
        let item: CardSelector
        let heading: CardSelector
        var title: CardSelector?
        var amount: CardSelector?
    }

    private static let headingTags: Set<String> = ["h1", "h2", "h3", "h4", "h5", "h6"]

    /// `lines`, with the headings of the first Tasty Recipes or Mediavine Create list in `html` that lines up with them.
    static func refine(html: String, lines: [String]) -> [String] {
        refine(html: html, lines: lines, cards: SiteRules.cards)
    }

    /// `lines`, with the headings of the first list of `cards` (in their order) in `html` that lines up with them.
    static func refine(html: String, lines: [String], cards: [Card]) -> [String] {
        lineUp(html: html, lines: lines, cards: cards) ?? lines
    }

    /// The first list of `cards` in `html` that lines up with `lines`, as lines with its headings; nil if none does.
    static func lineUp(html: String, lines: [String], cards: [Card]) -> [String]? {
        // A page that can't hold any of the lists is passed over before its tree is built.
        let candidates = cards.filter { card in card.list.needles.contains { $0.isEmpty || html.contains($0) } }
        if candidates.isEmpty { return nil }
        let tree = HtmlTree(html)
        for card in candidates {
            for container in card.list.select(tree, in: 0) {
                guard let groups = read(tree, container, card),
                      let refined = CardIngredients.lineUp(groups, lines, normalize: letters) else { continue }
                return refined
            }
        }
        return nil
    }

    /// The list's groups, in page order, or nil if an item is empty.
    private static func read(_ tree: HtmlTree, _ container: Int, _ card: Card) -> [CardIngredients.Group]? {
        var names = [""]
        var items: [[CardIngredients.Item]] = [[]]
        for e in CardIngredients.within(tree, container, { card.item.matches($0) || card.heading.matches($0) }) {
            // Not the list's title, and nothing inside an item.
            var node: Int? = e
            var skip = false
            while let n = node, n != container, !skip {
                let element = tree.elements[n]
                skip = card.title?.matches(element) == true || (n != e && card.item.matches(element))
                node = element.parent
            }
            if skip { continue }
            if card.item.matches(tree.elements[e]) {
                let key = key(tree, e, card.amount)
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

    /// The item's text, without the elements inside it that `amount` finds: its source with
    /// theirs cut out, then read as any element's text is.
    private static func key(_ tree: HtmlTree, _ item: Int, _ amount: CardSelector?) -> String {
        guard let amount else { return CardIngredients.text(tree, item) }
        let element = tree.elements[item]
        var source = ""
        var cursor = element.innerStart
        // In document order; one inside an element already cut starts before the cursor.
        for d in tree.descendants(of: item) where tree.elements[d].outerStart >= cursor && amount.matches(tree.elements[d]) {
            source += tree.source(cursor, tree.elements[d].outerStart)
            cursor = tree.elements[d].outerEnd
        }
        source += tree.source(cursor, element.innerEnd)
        return JsonLdRecipeParser.stripHtml(source).split(whereSeparator: \.isWhitespace).joined(separator: " ")
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
