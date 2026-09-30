import Foundation

/// The clip's Text view (#213): a Reddit post as a plain page, built from `RedditPageText` and
/// loaded into a second web view with `clipper.js`, so text is selected and assigned exactly as
/// on the post. Pure; Android's `RedditTextPage` builds the same page. Every string is escaped;
/// the page has no links, scripts or images of its own. A line is a paragraph, so a selection
/// splits one item a line as it does on the post. Labels (the comment heading, the note, each
/// author) can't be selected, so they never end up in a field.
enum RedditTextPage {

    /// `commentsHeading` heads the comments, with `loadedNote` (only the comments Reddit has
    /// loaded are here) under it; `author` labels a comment ("u/name").
    static func html(
        _ text: RedditPageText, commentsHeading: String, loadedNote: String, author: (String) -> String
    ) -> String {
        var out = "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
        out += "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        out += "<style>\(style)</style></head><body>"
        if !text.title.kTrimmed.isEmpty { out += "<h1>\(escape(text.title))</h1>" }
        for line in text.body { out += "<p>\(escape(line))</p>" }
        if !text.comments.isEmpty {
            out += "<h2 class=\"label\">\(escape(commentsHeading))</h2>"
            out += "<p class=\"label note\">\(escape(loadedNote))</p>"
            for comment in text.comments {
                let indent = min(max(comment.depth, 0), maxIndent) * indentPx
                out += "<section style=\"margin-left:\(indent)px\">"
                out += "<p class=\"label author\">\(escape(author(comment.author)))</p>"
                for line in comment.lines { out += "<p>\(escape(line))</p>" }
                out += "</section>"
            }
        }
        return out + "</body></html>"
    }

    private static let indentPx = 12
    private static let maxIndent = 6

    // The app's tokens: ground, ink, muted and hairline; on ink in dark mode.
    private static let style =
        "body{margin:0;padding:12px 16px 32px;background:#FBF9F6;color:#1C1917;"
        + "font:17px/1.45 -apple-system,system-ui,sans-serif;-webkit-text-size-adjust:100%}"
        + "h1{font-size:22px;line-height:1.25;margin:4px 0 12px}"
        + "p{margin:0 0 10px}"
        + "h2{font-size:15px;margin:24px 0 2px;padding-top:14px;border-top:1px solid #E7E1D9}"
        + ".note,.author{font-size:13px;color:#6B6259}"
        + ".author{font-weight:600;margin:14px 0 4px}"
        + "section{border-left:2px solid #E7E1D9;padding-left:10px}"
        + ".label{-webkit-user-select:none;user-select:none}"
        + "@media (prefers-color-scheme:dark){body{background:#1C1917;color:#FBF9F6}"
        + ".note,.author{color:#A39A90}h2,section{border-color:#3A342F}}"

    static func escape(_ text: String) -> String {
        var out = ""
        out.reserveCapacity(text.count)
        for c in text {
            switch c {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&#39;"
            default: out.append(c)
            }
        }
        return out
    }
}
