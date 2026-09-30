package com.example.recipeclipper.data.remote

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** One loaded comment: who wrote it, how deep in its thread, and its text, a block a line. */
data class RedditPageComment(val author: String, val depth: Int, val lines: List<String>)

/**
 * A Reddit post as plain text, read from its page for the clip's Text view (#213): the title,
 * the text body a block a line, and the comments the page has loaded, in page order.
 */
data class RedditPageText(
    val title: String,
    val body: List<String>,
    val comments: List<RedditPageComment>
) {
    val isEmpty: Boolean get() = title.isBlank() && body.isEmpty() && comments.isEmpty()

    companion object {
        /**
         * Reads [html], the markup `reddit-reader.js`'s `RCReddit.text()` hands over (the
         * `shreddit-post` and the comment tree, or the whole page), the way www.reddit.com
         * renders it in September 2026. Pure; nothing is guessed: a line is one block (a
         * paragraph, a list item, a heading) as written. The title is the post's `post-title`,
         * else its title heading; the body is the post's text body, however much of it the page
         * shows (a collapsed body is all in the markup); each comment is its own text, not its
         * replies', which follow it with a greater depth.
         */
        fun parse(html: String): RedditPageText {
            val doc = Jsoup.parse(html)
            val post = doc.selectFirst("shreddit-post")
            val title = post?.attr("post-title")?.let(::clean).orEmpty()
                .ifEmpty { post?.selectFirst("[slot=title]")?.text()?.let(::clean).orEmpty() }
            val bodyRoot = post?.let {
                it.selectFirst("[property=schema:articleBody]")
                    ?: it.selectFirst("[id$=-post-rtjson-content]")
                    ?: it.selectFirst("[slot=text-body]")
            }
            val body = bodyRoot?.let(::lines).orEmpty()
            val comments = doc.select("shreddit-comment").mapNotNull { comment ->
                val content = comment.select("[slot=comment]")
                    .firstOrNull { it.closest("shreddit-comment") === comment }
                val lines = content?.let(::lines).orEmpty()
                if (lines.isEmpty()) null
                else RedditPageComment(
                    author = comment.attr("author"),
                    depth = comment.attr("depth").toIntOrNull() ?: 0,
                    lines = lines
                )
            }
            return RedditPageText(title, body, comments)
        }

        private val BLOCKS = setOf(
            "p", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "pre", "blockquote", "div",
            "table", "thead", "tbody", "tr", "td", "th", "hr", "br", "figure", "section"
        )

        private val SPACES = Regex("[\\s   ]+")

        private fun clean(text: String) = text.replace(SPACES, " ").trim()

        /** [root]'s text, one line per block; a preformatted block keeps its own lines. */
        fun lines(root: Element): List<String> = mutableListOf<String>().also { collect(root, it) }

        private fun collect(element: Element, out: MutableList<String>) {
            if (element.normalName() == "pre") {
                element.wholeText().split('\n').map(::clean).filterTo(out) { it.isNotEmpty() }
                return
            }
            val run = StringBuilder()
            fun flush() {
                clean(run.toString()).takeIf { it.isNotEmpty() }?.let(out::add)
                run.clear()
            }
            for (node in element.childNodes()) {
                when {
                    node is TextNode -> run.append(node.wholeText)
                    node !is Element -> Unit
                    node.normalName() in BLOCKS || hasBlock(node) -> {
                        flush()
                        collect(node, out)
                    }
                    else -> run.append(node.wholeText())
                }
            }
            flush()
        }

        private fun hasBlock(element: Element): Boolean =
            element.children().any { it.normalName() in BLOCKS || hasBlock(it) }
    }
}
