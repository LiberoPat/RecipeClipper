import Foundation

/// One row of the clip's Text view (#213): a Reddit post as a list of its lines, each with a
/// checkbox since #286 ("Text view, line by line"). Pure (Android's `ClipTextRow` builds the same
/// rows). A line is one block of the post as written (a paragraph, a list item), so the ticked
/// lines go in one item a line, as a selection on the post does. Labels (the comments' heading
/// and note, each author) have no checkbox, so they never end up in a field.
enum ClipTextRow: Equatable, Identifiable {
    /// A line that can be ticked: the post's `title`, a line of its body, or of a comment `depth`
    /// deep (nil for the post). `index` numbers the lines that can be ticked, from 0.
    case line(index: Int, text: String, title: Bool = false, depth: Int? = nil)
    /// The comments' heading, with the note under it (only the comments Reddit has loaded).
    case comments
    /// Comment number `comment`'s author ("u/name"), `depth` deep.
    case author(comment: Int, name: String, depth: Int)

    /// Unique across the whole list (a `ForEach` id).
    var id: String {
        switch self {
        case .line(let index, _, _, _): return "line-\(index)"
        case .comments: return "comments"
        case .author(let comment, _, _): return "author-\(comment)"
        }
    }

    /// How deep a reply is drawn indented, at most: deeper ones line up with it.
    static let maxDepth = 6

    /// The post's rows: its title, its body, then each loaded comment under its author.
    static func of(_ text: RedditPageText) -> [ClipTextRow] {
        var rows: [ClipTextRow] = []
        var index = 0
        func add(_ text: String, title: Bool = false, depth: Int? = nil) {
            rows.append(.line(index: index, text: text, title: title, depth: depth))
            index += 1
        }
        if !text.title.kTrimmed.isEmpty { add(text.title, title: true) }
        text.body.forEach { add($0) }
        if !text.comments.isEmpty { rows.append(.comments) }
        for (n, comment) in text.comments.enumerated() {
            let depth = min(max(comment.depth, 0), maxDepth)
            rows.append(.author(comment: n, name: comment.author, depth: depth))
            comment.lines.forEach { add($0, depth: depth) }
        }
        return rows
    }

    /// The line's text, for a line; nil for a label.
    var lineText: String? {
        if case .line(_, let text, _, _) = self { return text }
        return nil
    }
}

/// A line of the Text view an add took (#286): `mark` is that add; the `first` line carries its tag.
struct ClipTextMark: Equatable {
    let mark: ClipMark
    let first: Bool
}

/// An add made in the Text view (#286): its mark's id and the lines it took, for its tag. Kept in
/// order (Android's map keeps insertion order), so a later add's mark wins on a shared line.
struct ClipTextAdd: Equatable {
    let markId: String
    let lines: [Int]
}
