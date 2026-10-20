package com.example.recipeclipper.ui.clip

import com.example.recipeclipper.data.model.ClipMark
import com.example.recipeclipper.data.remote.RedditPageText

/**
 * One row of the clip's Text view (#213): a Reddit post as a list of its lines, each with a
 * checkbox since #286 ("Text view, line by line"). Pure (iOS's `ClipTextRow` builds the same
 * rows). A line is one block of the post as written (a paragraph, a list item), so the ticked
 * lines go in one item a line, as a selection on the post does. Labels (the comments' heading
 * and note, each author) have no checkbox, so they never end up in a field.
 */
sealed class ClipTextRow {
    /** Unique across the whole list (a LazyColumn key). */
    abstract val key: String

    /**
     * A line that can be ticked: the post's [title], a line of its body, or of a comment
     * [depth] deep (null for the post). [index] numbers the lines that can be ticked, from 0.
     */
    data class Line(val index: Int, val text: String, val title: Boolean = false, val depth: Int? = null) : ClipTextRow() {
        override val key: String get() = "line-$index"
    }

    /** The comments' heading, with the note under it (only the comments Reddit has loaded). */
    object Comments : ClipTextRow() {
        override val key: String = "comments"
    }

    /** Comment number [comment]'s author ("u/name"), [depth] deep. */
    data class Author(val comment: Int, val name: String, val depth: Int) : ClipTextRow() {
        override val key: String get() = "author-$comment"
    }

    companion object {
        /** How deep a reply is drawn indented, at most: deeper ones line up with it. */
        const val MAX_DEPTH = 6

        /** The post's rows: its title, its body, then each loaded comment under its author. */
        fun of(text: RedditPageText): List<ClipTextRow> = buildList {
            var index = 0
            if (text.title.isNotBlank()) add(Line(index++, text.title, title = true))
            text.body.forEach { add(Line(index++, it)) }
            if (text.comments.isNotEmpty()) add(Comments)
            text.comments.forEachIndexed { n, comment ->
                val depth = comment.depth.coerceIn(0, MAX_DEPTH)
                add(Author(n, comment.author, depth))
                comment.lines.forEach { add(Line(index++, it, depth = depth)) }
            }
        }
    }
}

/** A line of the Text view an add took (#286): [mark] is that add; the [first] line carries its tag. */
data class ClipTextMark(val mark: ClipMark, val first: Boolean)
