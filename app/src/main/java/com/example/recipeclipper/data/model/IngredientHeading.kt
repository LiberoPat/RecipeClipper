package com.example.recipeclipper.data.model

/**
 * A group heading among a recipe's ingredient lines ("For the sauce:", "Für die Füllung:",
 * "Pour la garniture :"): a line ending in a colon, the form the parsers, cards and splitter
 * write group headings in (#118, #119, #208), in every language. It is shown as a subheading,
 * and never scaled, converted, ticked, bought or used up. Ticks stay keyed by line index, so a
 * heading keeps its index; a tick stored on one is ignored.
 */
object IngredientHeading {

    fun isHeading(line: String): Boolean = line.trim().endsWith(":")
}
