package com.example.recipeclipper.ui.clip

import com.example.recipeclipper.data.remote.RedditPageText

/**
 * The clip's Text view (#213): a Reddit post as a plain page, built from [RedditPageText] and
 * loaded into a second web view with `clipper.js`, so text is selected and assigned exactly as on
 * the post. Pure (iOS's `RedditTextPage` builds the same page). Every string is escaped; the page
 * has no links, scripts or images of its own. A line is a paragraph, so a selection splits one
 * item a line as it does on the post. Labels (the comment heading, the note, each author) can't
 * be selected, so they never end up in a field.
 */
internal object RedditTextPage {

    /**
     * [commentsHeading] heads the comments, with [loadedNote] (only the comments Reddit has
     * loaded are here) under it; [author] labels a comment ("u/name").
     */
    fun html(
        text: RedditPageText,
        commentsHeading: String,
        loadedNote: String,
        author: (String) -> String
    ): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<style>").append(STYLE).append("</style></head><body>")
        if (text.title.isNotBlank()) append("<h1>").append(escape(text.title)).append("</h1>")
        text.body.forEach { append("<p>").append(escape(it)).append("</p>") }
        if (text.comments.isNotEmpty()) {
            append("<h2 class=\"label\">").append(escape(commentsHeading)).append("</h2>")
            append("<p class=\"label note\">").append(escape(loadedNote)).append("</p>")
            text.comments.forEach { comment ->
                val indent = comment.depth.coerceIn(0, MAX_INDENT) * INDENT_PX
                append("<section style=\"margin-left:").append(indent).append("px\">")
                append("<p class=\"label author\">").append(escape(author(comment.author))).append("</p>")
                comment.lines.forEach { append("<p>").append(escape(it)).append("</p>") }
                append("</section>")
            }
        }
        append("</body></html>")
    }

    private const val INDENT_PX = 12
    private const val MAX_INDENT = 6

    // The app's tokens: ground, ink, muted and hairline; on ink in dark mode.
    private const val STYLE =
        "body{margin:0;padding:12px 16px 32px;background:#FBF9F6;color:#1C1917;" +
            "font:17px/1.45 -apple-system,system-ui,sans-serif;-webkit-text-size-adjust:100%}" +
            "h1{font-size:22px;line-height:1.25;margin:4px 0 12px}" +
            "p{margin:0 0 10px}" +
            "h2{font-size:15px;margin:24px 0 2px;padding-top:14px;border-top:1px solid #E7E1D9}" +
            ".note,.author{font-size:13px;color:#6B6259}" +
            ".author{font-weight:600;margin:14px 0 4px}" +
            "section{border-left:2px solid #E7E1D9;padding-left:10px}" +
            ".label{-webkit-user-select:none;user-select:none}" +
            "@media (prefers-color-scheme:dark){body{background:#1C1917;color:#FBF9F6}" +
            ".note,.author{color:#A39A90}h2,section{border-color:#3A342F}}"

    fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
