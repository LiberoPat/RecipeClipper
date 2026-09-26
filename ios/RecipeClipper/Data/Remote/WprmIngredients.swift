import Foundation

/// WP Recipe Maker's ingredient markup (#118), read only to refine the ingredient lines a
/// JSON-LD recipe already has. WPRM marks each ingredient's parts (`wprm-recipe-ingredient-amount`,
/// `-unit`, `-name`, `-notes`) and names its groups, but writes its JSON-LD lines by wrapping the
/// notes in brackets: "2 garlic cloves (, minced)". From the parts, the line reads as the card
/// shows it: "2 garlic cloves, minced". The notes stay on the line, after the name, so they never
/// change what scales.
///
/// Used only when a card's ingredients line up one-to-one with JSON-LD's `recipeIngredient` (same
/// count, each part's name found in its JSON-LD line); otherwise the JSON-LD lines stay. A named
/// group adds a heading line ("Batter:"). Pure: markup in, lines out. A port of Android's
/// `WprmIngredients`, pinned to it by the differential corpus.
enum WprmIngredients {

    private typealias Item = CardIngredients.Item
    private typealias Group = CardIngredients.Group

    /// `lines`, refined by the first WPRM ingredient list in `html` that lines up with them.
    static func refine(html: String, lines: [String]) -> [String] {
        guard html.contains("wprm-recipe-ingredient") else { return lines }
        let tree = HtmlTree(html)
        let containers = tree.elements.indices.filter { tree.elements[$0].hasClass("wprm-recipe-ingredients-container") }
        for container in containers {
            guard let groups = read(tree, container),
                  let refined = CardIngredients.lineUp(groups, lines, normalize: normalize) else { continue }
            return refined
        }
        return lines
    }

    /// The container's groups, or nil if any ingredient has no name.
    private static func read(_ tree: HtmlTree, _ container: Int) -> [Group]? {
        let groupElements = within(tree, container) { $0.hasClass("wprm-recipe-ingredient-group") }
        if groupElements.isEmpty {
            guard let items = items(tree, container) else { return nil }
            return [Group(name: "", items: items)]
        }
        var groups: [Group] = []
        for g in groupElements {
            let name = within(tree, g) { $0.hasClass("wprm-recipe-ingredient-group-name") }.first.map { text(tree, $0) } ?? ""
            guard let items = items(tree, g) else { return nil }
            groups.append(Group(name: name, items: items))
        }
        return groups
    }

    private static func items(_ tree: HtmlTree, _ scope: Int) -> [Item]? {
        var items: [Item] = []
        for li in within(tree, scope, { $0.name == "li" && $0.hasClass("wprm-recipe-ingredient") }) {
            guard let item = item(tree, li) else { return nil }
            items.append(item)
        }
        return items
    }

    private static func item(_ tree: HtmlTree, _ li: Int) -> Item? {
        func part(_ name: String) -> String {
            within(tree, li) { $0.hasClass("wprm-recipe-ingredient-" + name) }.first.map { text(tree, $0) } ?? ""
        }
        let name = part("name")
        if name.isEmpty { return nil }
        let line = [part("amount"), part("unit"), name].filter { !$0.isEmpty }.joined(separator: " ")
        return Item(key: name, line: line + noteSuffix(tree, li, part("notes")))
    }

    /// The notes as the card shows them after the name: a comma when the page puts one there.
    private static func noteSuffix(_ tree: HtmlTree, _ li: Int, _ notes: String) -> String {
        if notes.isEmpty { return "" }
        if notes.hasPrefix(",") { return notes }
        let commaBetween = tree.elements[li].children.contains {
            if case let .text(start, end) = $0 { return JsonLdRecipeParser.stripHtml(tree.source(start, end)).contains(",") }
            return false
        }
        return (commaBetween ? ", " : " ") + notes
    }

    private static func within(_ tree: HtmlTree, _ index: Int, _ match: (HtmlTree.Element) -> Bool) -> [Int] {
        CardIngredients.within(tree, index, match)
    }

    private static func text(_ tree: HtmlTree, _ index: Int) -> String { CardIngredients.text(tree, index) }

    /// The name is found in its JSON-LD line with case and spacing ignored.
    private static func normalize(_ s: String) -> String {
        s.lowercased().split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }
}
