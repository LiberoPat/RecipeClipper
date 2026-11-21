import Foundation

/// Finds a recipe written inside an article (#435): no recipe data and no headings, just a list of
/// amounts followed by the method in paragraphs. **Only when the page has a list of amounts**,
/// never from prose alone. Pure: HTML in, data out, over the same `HtmlTree` the microdata parser
/// reads. Android's `ArticleRecipeParser`, rule for rule (its comment has the rules); pinned by the
/// differential corpus's `Article` rows and `ArticleRecipeParserTests`.
enum ArticleRecipeParser {

    /// At least this many items must read as an amount.
    static let minAmounts = 3

    /// An item longer than this is a sentence, not an ingredient line.
    static let maxItem = 200

    /// At most this many steps are taken.
    static let maxSteps = 40

    /// At most this many paragraphs and items are read to tell the article's language.
    private static let detectionBlocks = 300

    /// Lists inside these are the page's furniture, never ingredients.
    private static let furniture: Set<String> = ["nav", "header", "footer", "aside", "form"]

    /// Passed over between the list and the end of the steps: never steps.
    private static let passedOver: Set<String> = [
        "nav", "header", "footer", "aside", "form", "figure", "figcaption", "table", "blockquote",
        "script", "style", "noscript", "template", "iframe", "button", "select", "textarea", "svg", "picture",
    ]

    private static let headings: Set<String> = ["h1", "h2", "h3", "h4", "h5", "h6"]

    static func parse(html: String, url: String) -> ArticleRecipe? {
        let tree = HtmlTree(html)
        let elements = tree.elements
        func first(_ name: String, in range: Range<Int>) -> Int? { range.first { elements[$0].name == name } }
        let all = 1..<elements.count
        let body = first("body", in: all)
        let bodyRange = body.map { tree.descendants(of: $0) } ?? all
        guard let name = title(tree) else { return nil }
        let root = first("article", in: bodyRange) ?? first("main", in: bodyRange) ?? body ?? 0
        let inRoot = tree.descendants(of: root)

        let detection = inRoot.filter { elements[$0].name == "p" || elements[$0].name == "li" }
            .prefix(detectionBlocks).map { tree.text(of: $0) }.joined(separator: "\n")
        let language = LanguageWords.resolve(declared: nil, page: JsonLdRecipeParser.pageLanguage(html: html)) { detection }
        guard let words = LanguageWords.forTag(language) else { return nil }

        guard let list = inRoot.first(where: { (elements[$0].name == "ul" || elements[$0].name == "ol") &&
            isAmountList($0, root: root, tree: tree, words: words) })
        else { return nil }
        let ingredients = items(list, tree).map { tree.text(of: $0) }.filter { !$0.isEmpty }
        let steps = SiteRules.steps(url: url, steps: stepsAfter(list, root: root, tree: tree, words: words)).filter { !$0.isEmpty }
        if steps.isEmpty { return nil }
        let image = meta("og:image", tree).flatMap { WebImageUrl.of(URL(string: $0, relativeTo: URL(string: url))?.absoluteString) }
        return ArticleRecipe(
            name: name, image: image, ingredients: ingredients, steps: steps,
            language: String(language.split(separator: "-", omittingEmptySubsequences: false).first ?? ""), sourceUrl: url
        )
    }

    private static func meta(_ property: String, _ tree: HtmlTree) -> String? {
        tree.elements.first { $0.name == "meta" && $0.attributes["property"] == property }
            .flatMap { $0.attributes["content"]?.kTrimmed }
            .flatMap { $0.isEmpty ? nil : $0 }
    }

    /// The page's headline, as `PageTextReader` reads its title.
    private static func title(_ tree: HtmlTree) -> String? {
        func text(_ name: String) -> String? {
            tree.elements.indices.first { tree.elements[$0].name == name }
                .map { tree.text(of: $0) }.flatMap { $0.isEmpty ? nil : $0 }
        }
        return text("h1") ?? meta("og:title", tree) ?? text("title")
    }

    /// The element children of `index`, in order.
    private static func children(_ index: Int, _ tree: HtmlTree) -> [Int] {
        tree.elements[index].children.compactMap { if case .element(let c) = $0 { c } else { nil } }
    }

    private static func items(_ list: Int, _ tree: HtmlTree) -> [Int] {
        children(list, tree).filter { tree.elements[$0].name == "li" }
    }

    /// Whether `list` is the article's list of amounts (see Android's class comment).
    private static func isAmountList(_ list: Int, root: Int, tree: HtmlTree, words: LanguageWords) -> Bool {
        var parent = tree.elements[list].parent
        while let p = parent, p != root {
            let name = tree.elements[p].name
            if furniture.contains(name) || name == "li" { return false }
            parent = tree.elements[p].parent
        }
        let items = items(list, tree)
        let amounts = items.filter { item in
            let text = tree.text(of: item)
            return !text.isEmpty && text.utf16.count <= maxItem && !linkOnly(item, text: text, tree: tree) &&
                RecipeTextSplitter.isAmount(text, words: words)
        }.count
        return amounts >= minAmounts && amounts * 2 > items.count
    }

    /// An item that is nothing but a link.
    private static func linkOnly(_ item: Int, text: String, tree: HtmlTree) -> Bool {
        tree.descendants(of: item).contains { tree.elements[$0].name == "a" && tree.text(of: $0) == text }
    }

    /// The section `text` is a header of, in the splitter's words; nil for an ordinary line.
    private static func section(_ text: String, _ words: LanguageWords) -> RecipeTextSplitter.Section? {
        text.isEmpty ? nil : RecipeTextSplitter.header(RecipeTextSplitter.Line(text: text), words: words)?.section
    }

    /// The method after `list`: see Android's class comment.
    private static func stepsAfter(_ list: Int, root: Int, tree: HtmlTree, words: LanguageWords) -> [String] {
        var steps: [String] = []
        // False once the steps end.
        func visit(_ element: Int) -> Bool {
            if steps.count >= maxSteps { return false }
            let name = tree.elements[element].name
            // A steps heading ("Preparación") is passed over; any other ends the steps.
            if headings.contains(name) { return section(tree.text(of: element), words) == .instructions }
            if name == "ul" { return false }
            if passedOver.contains(name) { return true }
            if name == "ol" {
                for item in items(element, tree) {
                    let text = tree.text(of: item)
                    if !text.isEmpty && steps.count < maxSteps { steps.append(text) }
                }
                return true
            }
            if name == "p" {
                let text = tree.text(of: element)
                if text.isEmpty { return true }
                switch section(text, words) {
                case nil: steps.append(text); return true
                case .instructions: return true
                default: return false
                }
            }
            for child in children(element, tree) where !visit(child) { return false }
            return true
        }
        var cursor = list
        while cursor != root {
            guard let parent = tree.elements[cursor].parent else { break }
            let siblings = children(parent, tree)
            if let at = siblings.firstIndex(of: cursor) {
                for next in siblings[(at + 1)...] where !visit(next) { return steps }
            }
            cursor = parent
        }
        return steps
    }
}
