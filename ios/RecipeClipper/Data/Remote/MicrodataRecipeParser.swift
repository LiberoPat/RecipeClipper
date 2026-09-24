import Foundation

/// The fallback for a page with no JSON-LD recipe: schema.org Recipe *microdata*, the
/// `itemscope`/`itemprop` attributes on the visible markup. Tried only when
/// `JsonLdRecipeParser` finds nothing, so a site that works today is never read this way.
///
/// Written for WordPress's Jetpack recipe block, which Smitten Kitchen uses. It marks up the
/// name, ingredients, yield and total time as microdata, but puts the steps in
/// `<div class="jetpack-recipe-directions">` with no `recipeInstructions` itemprop. That div is
/// read as the steps when the microdata has none. Its first step is bare text before any `<p>`
/// (followed by a stray `</p>`), so steps are split at block boundaries rather than taken one
/// per `<p>`, which would lose step 1.
///
/// Pure: page text in, recipe out, no network I/O. A port of Android's `MicrodataRecipeParser`,
/// which leans on Jsoup; `HtmlTree` below stands in for Jsoup's DOM, and both are tested
/// against the same pages.
enum MicrodataRecipeParser {

    /// How deep `steps` will recurse through nested blocks before giving up.
    private static let maxDepth = 50

    /// Elements that end one step and start the next.
    private static let blocks: Set<String> = [
        "p", "li", "div", "ol", "ul", "section", "article", "blockquote", "br", "table", "tr",
        "h1", "h2", "h3", "h4", "h5", "h6",
    ]

    static func parse(html: String, sourceUrl: String) -> Recipe? {
        let tree = HtmlTree(html)
        guard let root = tree.elements.indices.first(where: {
            tree.elements[$0].attributes["itemscope"] != nil
                && isRecipeType(tree.elements[$0].attributes["itemtype"] ?? "")
        }) else { return nil }
        let reader = Reader(tree: tree, sourceUrl: sourceUrl)

        guard let name = reader.props(root, "name").map(reader.value).first(where: { !$0.isEmpty }) else {
            return nil
        }
        var ingredientElements = reader.props(root, "recipeIngredient")
        if ingredientElements.isEmpty { ingredientElements = reader.props(root, "ingredients") }
        let ingredients = ingredientElements.map(reader.value).filter { !$0.isEmpty }

        var instructions = reader.props(root, "recipeInstructions").flatMap { reader.steps($0, depth: 0) }
        if instructions.isEmpty, let directions = tree.descendants(of: root).first(where: {
            tree.elements[$0].hasClass("jetpack-recipe-directions")
        }) {
            instructions = reader.steps(directions, depth: 0)
        }
        if ingredients.isEmpty && instructions.isEmpty { return nil }

        let language = LanguageWords.resolve(
            declared: reader.props(root, "inLanguage").map(reader.value).first(where: { !$0.isEmpty }),
            page: JsonLdRecipeParser.pageLanguage(html: html)
        ) { LanguageWords.detectionText(name: name, ingredients: ingredients) }
        let words = LanguageWords.forTag(language)

        let ogImage = tree.elements.first {
            $0.name == "meta" && $0.attributes["property"] == "og:image"
        }.flatMap { reader.absolute($0.attributes["content"] ?? "") }

        return Recipe(
            name: name,
            image: reader.props(root, "image").map(reader.value).first(where: { !$0.isEmpty }) ?? ogImage,
            ingredients: ingredients,
            instructions: instructions,
            prepTime: reader.duration(root, "prepTime", words),
            cookTime: reader.duration(root, "cookTime", words),
            totalTime: reader.duration(root, "totalTime", words),
            yield: Servings.pickYield(reader.props(root, "recipeYield").map(reader.value).filter { !$0.isEmpty }, words: words),
            sourceUrl: sourceUrl,
            language: language
        )
    }

    /// `itemtype` is a space-separated list of type URLs; any one naming schema.org's Recipe.
    private static func isRecipeType(_ itemtype: String) -> Bool {
        itemtype.split(whereSeparator: { $0 == " " || $0 == "\t" || $0 == "\n" || $0 == "\r" }).contains {
            var type = $0.lowercased()
            while type.hasSuffix("/") { type.removeLast() }
            return type.hasSuffix("schema.org/recipe")
        }
    }

    private struct Reader {
        let tree: HtmlTree
        let sourceUrl: String

        /// The elements carrying property `name` that belong to `scope` itself: not ones inside
        /// a nested item such as an author's Person, whose own `name` is not the recipe's.
        func props(_ scope: Int, _ name: String) -> [Int] {
            tree.descendants(of: scope).filter { index in
                guard let itemprop = tree.elements[index].attributes["itemprop"] else { return false }
                return itemprop.split(separator: " ").contains { $0 == name } && owner(of: index) == scope
            }
        }

        /// The nearest enclosing item: the closest ancestor with `itemscope`.
        func owner(of index: Int) -> Int? {
            var p = tree.elements[index].parent
            while let current = p, tree.elements[current].attributes["itemscope"] == nil {
                p = tree.elements[current].parent
            }
            return p
        }

        /// A property's value, as the microdata spec reads it: a `content` attribute when
        /// there is one, a URL for links and media, a `datetime` for `<time>`, `value` for
        /// `<data>` and `<meter>`, else the element's text.
        func value(_ index: Int) -> String {
            let el = tree.elements[index]
            let result: String
            if let content = el.attributes["content"] {
                result = content
            } else if ["a", "area", "link"].contains(el.name) {
                result = absolute(el.attributes["href"] ?? "") ?? ""
            } else if ["img", "audio", "video", "source", "embed", "iframe", "track"].contains(el.name) {
                result = absolute(el.attributes["src"] ?? "") ?? ""
            } else if el.name == "time", let datetime = el.attributes["datetime"] {
                result = datetime
            } else if el.name == "data" || el.name == "meter" {
                result = el.attributes["value"] ?? ""
            } else {
                result = tree.text(of: index)
            }
            return result.kTrimmed
        }

        /// Jsoup's `absUrl`: resolved against the page, empty when it can't be.
        func absolute(_ raw: String) -> String? {
            guard !raw.isEmpty, let url = URL(string: raw, relativeTo: URL(string: sourceUrl)) else { return nil }
            return url.absoluteString
        }

        func duration(_ root: Int, _ name: String, _ words: LanguageWords?) -> String? {
            props(root, name).first.flatMap { JsonLdRecipeParser.formatDuration(value($0), words: words) }
        }

        /// The steps in one instructions element. A nested HowToStep or HowToSection item gives
        /// its `text` (or its sub-steps); anything else is split at block boundaries: each `<p>`
        /// or `<li>` is a step, and so is a run of bare text between them.
        func steps(_ index: Int, depth: Int) -> [String] {
            let text = tree.text(of: index)
            if depth > MicrodataRecipeParser.maxDepth { return text.isEmpty ? [] : [text] }
            let el = tree.elements[index]
            if el.attributes["itemscope"] != nil {
                let nested = props(index, "itemListElement") + props(index, "step")
                if !nested.isEmpty { return nested.flatMap { steps($0, depth: depth + 1) } }
                let texts = props(index, "text").map(value).filter { !$0.isEmpty }
                if !texts.isEmpty { return texts }
                return text.isEmpty ? [] : [text]
            }

            var out: [String] = []
            var runStart: Int?
            var runEnd = 0
            func flush() {
                if let start = runStart {
                    let run = JsonLdRecipeParser.stripHtml(tree.source(start, runEnd)).kTrimmed
                    if !run.isEmpty { out.append(run) }
                }
                runStart = nil
            }
            for child in el.children {
                switch child {
                case .text(let start, let end):
                    if runStart == nil { runStart = start }
                    runEnd = end
                case .element(let c):
                    let childElement = tree.elements[c]
                    if MicrodataRecipeParser.blocks.contains(childElement.name) {
                        flush()
                        if childElement.name == "br" { continue }
                        let hasBlockChild = childElement.children.contains {
                            if case .element(let g) = $0 { return MicrodataRecipeParser.blocks.contains(tree.elements[g].name) }
                            return false
                        }
                        if hasBlockChild {
                            out += steps(c, depth: depth + 1)
                        } else {
                            let t = tree.text(of: c)
                            if !t.isEmpty { out.append(t) }
                        }
                    } else {
                        if runStart == nil { runStart = childElement.outerStart }
                        runEnd = childElement.outerEnd
                    }
                }
            }
            flush()
            return out
        }
    }
}

/// A small, forgiving HTML element tree: just enough structure for microdata. It knows void
/// elements, raw-text elements (`script`, `style`), comments, and the implied end tags that
/// matter for recipe markup (a new `<p>` or block closes an open `<p>`; a new `<li>` closes
/// the previous one). A stray end tag is ignored. Text is never decoded here: an element's
/// text is `JsonLdRecipeParser.stripHtml` of its source, the same Jsoup-compatible cleanup
/// the JSON-LD path uses.
///
/// Elements live in one flat array in document order and refer to each other by index, so
/// building and freeing a pathologically deep page never recurses.
struct HtmlTree {
    enum Child {
        case text(Int, Int)
        case element(Int)
    }

    struct Element {
        let name: String
        let attributes: [String: String]
        let parent: Int?
        let outerStart: Int
        let innerStart: Int
        var innerEnd: Int
        var outerEnd: Int
        /// The index of the last element inside this one (its own index if none).
        var lastDescendant: Int
        var children: [Child] = []

        func hasClass(_ name: String) -> Bool {
            (attributes["class"] ?? "").split(separator: " ").contains { $0 == name }
        }
    }

    /// Element 0 is the document itself.
    private(set) var elements: [Element]
    private let ns: NSString

    private static let voidElements: Set<String> = [
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
    ]
    private static let rawText: Set<String> = ["script", "style", "textarea", "title"]
    /// Start tags that close an open `<p>` (the HTML spec's list, trimmed to what pages use).
    private static let closesP: Set<String> = [
        "address", "article", "aside", "blockquote", "details", "div", "dl", "fieldset", "figcaption", "figure",
        "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "li", "main", "nav", "ol", "p", "pre",
        "section", "table", "ul",
    ]
    /// Where the search for an open `<p>` or `<li>` to close stops.
    private static let scopeBarriers: Set<String> = ["table", "td", "th", "button", "template", "caption", "object"]

    private static let openTag = JRegex(#"<([A-Za-z][^\s/>]*)((?:[^>"']|"[^"]*"|'[^']*')*)>"#)
    private static let closeTag = JRegex(#"</([A-Za-z][^\s/>]*)[^>]*>"#)
    private static let attribute = JRegex(#"([^\s"'>/=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?"#)

    init(_ html: String) {
        ns = html as NSString
        let n = ns.length
        elements = [Element(name: "#document", attributes: [:], parent: nil, outerStart: 0, innerStart: 0,
                            innerEnd: n, outerEnd: n, lastDescendant: 0)]
        var stack = [0]
        var cursor = 0
        var textStart = 0

        func flushText(_ end: Int) {
            if end > textStart { elements[stack.last!].children.append(.text(textStart, end)) }
        }
        func close(_ index: Int, innerEnd: Int, outerEnd: Int) {
            elements[index].innerEnd = innerEnd
            elements[index].outerEnd = outerEnd
            elements[index].lastDescendant = elements.count - 1
        }
        /// Closes everything above `position` in the stack, then the element there, at `at`.
        func closeDown(to position: Int, at: Int) {
            while stack.count > position {
                close(stack.removeLast(), innerEnd: at, outerEnd: at)
            }
        }
        func findOpen(_ name: String, barriers: Set<String>) -> Int? {
            var i = stack.count - 1
            while i > 0 {
                let open = elements[stack[i]].name
                if open == name { return i }
                if barriers.contains(open) { return nil }
                i -= 1
            }
            return nil
        }

        while cursor < n {
            let lt = ns.range(of: "<", range: NSRange(location: cursor, length: n - cursor)).location
            if lt == NSNotFound { break }
            let rest = n - lt

            if rest >= 4, ns.substring(with: NSRange(location: lt, length: 4)) == "<!--" {
                flushText(lt)
                let end = ns.range(of: "-->", range: NSRange(location: lt + 4, length: n - lt - 4))
                cursor = end.location == NSNotFound ? n : NSMaxRange(end)
                textStart = cursor
                continue
            }
            let next = rest > 1 ? ns.character(at: lt + 1) : 0
            if next == 0x21 || next == 0x3F { // <! or <?
                flushText(lt)
                let end = ns.range(of: ">", range: NSRange(location: lt, length: rest))
                cursor = end.location == NSNotFound ? n : NSMaxRange(end)
                textStart = cursor
                continue
            }
            if next == 0x2F, let m = Self.closeTag.find(html, from: lt), m.start == lt { // </name>
                flushText(lt)
                let name = m[1].lowercased()
                if let position = findOpen(name, barriers: []) {
                    closeDown(to: position + 1, at: lt)
                    close(stack.removeLast(), innerEnd: lt, outerEnd: m.end)
                }
                cursor = m.end
                textStart = cursor
                continue
            }
            guard let m = Self.openTag.find(html, from: lt), m.start == lt else {
                cursor = lt + 1 // a literal '<' in text
                continue
            }
            flushText(lt)
            let name = m[1].lowercased()
            var attributes: [String: String] = [:]
            for a in Self.attribute.findAll(m[2]) {
                let key = a[1].lowercased()
                // An HTML parser keeps the first of duplicate attributes.
                if attributes[key] == nil {
                    let raw = a[2].isEmpty ? (a[3].isEmpty ? a[4] : a[3]) : a[2]
                    attributes[key] = raw.isEmpty ? "" : JsonLdRecipeParser.stripHtml(raw)
                }
            }

            if name == "li", let position = findOpen("li", barriers: Self.scopeBarriers.union(["ul", "ol"])) {
                closeDown(to: position, at: lt)
            }
            if Self.closesP.contains(name), let position = findOpen("p", barriers: Self.scopeBarriers) {
                closeDown(to: position, at: lt)
            }

            let index = elements.count
            elements.append(Element(name: name, attributes: attributes, parent: stack.last!, outerStart: lt,
                                    innerStart: m.end, innerEnd: m.end, outerEnd: m.end, lastDescendant: index))
            elements[stack.last!].children.append(.element(index))
            cursor = m.end
            textStart = cursor

            if Self.voidElements.contains(name) || m[2].hasSuffix("/") {
                continue
            }
            if Self.rawText.contains(name) {
                let end = JRegex("</\(NSRegularExpression.escapedPattern(for: name))\\s*>", ignoreCase: true)
                    .find(html, from: m.end)
                close(index, innerEnd: end?.start ?? n, outerEnd: end?.end ?? n)
                cursor = end?.end ?? n
                textStart = cursor
                continue
            }
            stack.append(index)
        }
        flushText(n)
        closeDown(to: 1, at: n)
        elements[0].lastDescendant = elements.count - 1
    }

    /// Every element inside `index`, in document order.
    func descendants(of index: Int) -> Range<Int> {
        (index + 1)..<(max(index, elements[index].lastDescendant) + 1)
    }

    func source(_ start: Int, _ end: Int) -> String {
        ns.substring(with: NSRange(location: start, length: max(0, end - start)))
    }

    /// An element's text, as Jsoup's `text()` gives it.
    func text(of index: Int) -> String {
        JsonLdRecipeParser.stripHtml(source(elements[index].innerStart, elements[index].innerEnd)).kTrimmed
    }
}
