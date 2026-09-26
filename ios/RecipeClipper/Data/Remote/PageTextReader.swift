import Foundation

/// A page's HTML as `PageText` (#103): one line per block, each the Jsoup-compatible text of
/// what lies between block boundaries, walking the same `HtmlTree` the microdata parser reads.
/// Pure. Android's `PageTextReader`, element list for element list.
enum PageTextReader {

    /// Elements that end one line and start the next.
    private static let blocks: Set<String> = [
        "address", "article", "aside", "blockquote", "br", "dd", "details", "div", "dl", "dt",
        "fieldset", "figcaption", "figure", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr",
        "li", "main", "ol", "p", "pre", "section", "summary", "table", "td", "th", "tr", "ul",
    ]

    /// Elements whose text is never recipe text.
    private static let skipped: Set<String> = [
        "head", "script", "style", "noscript", "template", "svg", "iframe", "nav", "footer",
        "select", "textarea", "button", "title",
    ]

    /// How deep the walk goes; deeper content is read as one line.
    private static let maxDepth = 200

    static func read(html: String, url: String) -> PageText {
        let tree = HtmlTree(html)
        var lines: [String] = []
        var run = ""
        func flush() {
            let line = JsonLdRecipeParser.stripHtml(run)
            if !line.isEmpty { lines.append(line) }
            run = ""
        }
        func walk(_ index: Int, depth: Int) {
            for child in tree.elements[index].children {
                switch child {
                case .text(let start, let end):
                    run += tree.source(start, end)
                case .element(let c):
                    let name = tree.elements[c].name
                    if skipped.contains(name) { continue }
                    if depth >= maxDepth {
                        flush(); run = tree.text(of: c); flush()
                    } else if blocks.contains(name) {
                        flush(); walk(c, depth: depth + 1); flush()
                    } else {
                        walk(c, depth: depth + 1)
                    }
                }
            }
        }
        walk(0, depth: 0)
        flush()

        func first(_ test: (HtmlTree.Element) -> Bool) -> Int? { tree.elements.indices.first { test(tree.elements[$0]) } }
        func meta(_ property: String) -> String? {
            first { $0.name == "meta" && $0.attributes["property"] == property }
                .flatMap { tree.elements[$0].attributes["content"]?.kTrimmed }
                .flatMap { $0.isEmpty ? nil : $0 }
        }
        let h1 = first { $0.name == "h1" }.map { tree.text(of: $0) }.flatMap { $0.isEmpty ? nil : $0 }
        let titleElement = first { $0.name == "title" }.map { tree.text(of: $0) }.flatMap { $0.isEmpty ? nil : $0 }
        let image = meta("og:image").flatMap { URL(string: $0, relativeTo: URL(string: url))?.absoluteString }
        return PageText(
            title: h1 ?? meta("og:title") ?? titleElement,
            lines: lines,
            language: JsonLdRecipeParser.pageLanguage(html: html),
            image: image
        )
    }
}
