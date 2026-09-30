import Foundation

/// One loaded comment: who wrote it, how deep in its thread, and its text, a block a line.
struct RedditPageComment: Equatable {
    let author: String
    let depth: Int
    let lines: [String]
}

/// A Reddit post as plain text, read from its page for the clip's Text view (#213): the title,
/// the text body a block a line, and the comments the page has loaded, in page order. Android's
/// `RedditPageText`, tested against the same fixtures (`shared/fixtures/reddit/page-text`).
struct RedditPageText: Equatable {
    let title: String
    let body: [String]
    let comments: [RedditPageComment]

    var isEmpty: Bool { title.kTrimmed.isEmpty && body.isEmpty && comments.isEmpty }

    /// Reads `html`, the markup `reddit-reader.js`'s `RCReddit.text()` hands over (the
    /// `shreddit-post` and the comment tree, or the whole page), the way www.reddit.com renders it
    /// in September 2026. Pure; nothing is guessed: a line is one block (a paragraph, a list item,
    /// a heading) as written. The title is the post's `post-title`, else its title heading; the
    /// body is the post's text body, however much of it the page shows; each comment is its own
    /// text, not its replies', which follow it with a greater depth.
    static func parse(_ html: String) -> RedditPageText {
        let tree = HtmlTree(html)
        let post = tree.elements.indices.first { tree.elements[$0].name == "shreddit-post" }
        var title = ""
        var body: [String] = []
        if let post {
            title = clean(tree.elements[post].attributes["post-title"] ?? "")
            let inside = tree.descendants(of: post)
            if title.isEmpty, let heading = inside.first(where: { tree.elements[$0].attributes["slot"] == "title" }) {
                title = clean(tree.text(of: heading))
            }
            let root = inside.first { tree.elements[$0].attributes["property"] == "schema:articleBody" }
                ?? inside.first { (tree.elements[$0].attributes["id"] ?? "").hasSuffix("-post-rtjson-content") }
                ?? inside.first { tree.elements[$0].attributes["slot"] == "text-body" }
            if let root { body = lines(tree, root) }
        }
        let comments: [RedditPageComment] = tree.elements.indices.compactMap { index in
            let element = tree.elements[index]
            guard element.name == "shreddit-comment" else { return nil }
            let content = tree.descendants(of: index).first {
                tree.elements[$0].attributes["slot"] == "comment" && nearestComment(tree, $0) == index
            }
            guard let content else { return nil }
            let lines = lines(tree, content)
            guard !lines.isEmpty else { return nil }
            return RedditPageComment(
                author: element.attributes["author"] ?? "",
                depth: Int(element.attributes["depth"] ?? "") ?? 0,
                lines: lines
            )
        }
        return RedditPageText(title: title, body: body, comments: comments)
    }

    private static let blocks: Set<String> = [
        "p", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "div",
        "table", "thead", "tbody", "tr", "td", "th", "hr", "br", "figure", "section",
    ]

    private static func clean(_ text: String) -> String {
        text.replacingOccurrences(of: "[\\s\u{00A0}\u{2007}\u{202F}]+", with: " ", options: .regularExpression).kTrimmed
    }

    private static func nearestComment(_ tree: HtmlTree, _ index: Int) -> Int? {
        var p = tree.elements[index].parent
        while let current = p, tree.elements[current].name != "shreddit-comment" { p = tree.elements[current].parent }
        return p
    }

    private static func hasBlock(_ tree: HtmlTree, _ index: Int) -> Bool {
        tree.descendants(of: index).contains { blocks.contains(tree.elements[$0].name) }
    }

    /// `root`'s text, one line per block; a preformatted block keeps its own lines.
    static func lines(_ tree: HtmlTree, _ root: Int) -> [String] {
        var out: [String] = []
        collect(tree, root, &out)
        return out
    }

    private static func collect(_ tree: HtmlTree, _ index: Int, _ out: inout [String]) {
        let element = tree.elements[index]
        if element.name == "pre" {
            let inner = tree.source(element.innerStart, element.innerEnd)
            out += inner.components(separatedBy: "\n").map { clean(JsonLdRecipeParser.stripHtml($0)) }.filter { !$0.isEmpty }
            return
        }
        // A run of text and inline elements, as source, stripped as one line when a block ends it.
        var runStart: Int?
        var runEnd = 0
        func flush() {
            if let start = runStart {
                let line = clean(JsonLdRecipeParser.stripHtml(tree.source(start, runEnd)))
                if !line.isEmpty { out.append(line) }
            }
            runStart = nil
        }
        for child in element.children {
            switch child {
            case .text(let start, let end):
                if runStart == nil { runStart = start }
                runEnd = end
            case .element(let c):
                let childElement = tree.elements[c]
                if blocks.contains(childElement.name) || hasBlock(tree, c) {
                    flush()
                    collect(tree, c, &out)
                } else {
                    if runStart == nil { runStart = childElement.outerStart }
                    runEnd = childElement.outerEnd
                }
            }
        }
        flush()
    }
}
