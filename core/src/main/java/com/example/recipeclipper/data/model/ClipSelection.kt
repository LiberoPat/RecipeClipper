package com.example.recipeclipper.data.model

/**
 * How text selected on a web page becomes recipe fields in "Clip it yourself" (#37). Pure:
 * the page hands over `window.getSelection().toString()`, which puts a line break between
 * blocks (list items, paragraphs, table rows), and this splits it.
 *
 * Nothing is guessed. A line is one item exactly as written; nothing is split inside a line,
 * joined across lines, renumbered or stripped of bullets.
 */
object ClipSelection {

    private val lineBreak = Regex("\r\n|[\r\n  \u0085]")

    // Every Unicode space (a no-break space is common in recipe markup) and the tab.
    private val spaces = Regex("[\\s   ]+")

    /** One item per line: split on line breaks, collapse runs of spaces, drop blank lines. */
    fun lines(text: String): List<String> =
        text.split(lineBreak)
            .map { it.replace(spaces, " ").trim() }
            .filter { it.isNotEmpty() }

    /** A name is one line: the selection's lines joined with a space. */
    fun name(text: String): String = lines(text).joinToString(" ")
}
