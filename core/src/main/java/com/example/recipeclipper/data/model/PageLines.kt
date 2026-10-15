package com.example.recipeclipper.data.model

/** Lines [first] to [last] of the numbered window, counted from 1, both included (#128). */
data class LineRun(val first: Int, val last: Int)

/**
 * What the model picked, by line number (#128): the name, yield and times copied as the page
 * writes them (short), the ingredients and steps as runs of the numbered window's lines.
 */
data class PagePick(
    val name: String?,
    val ingredients: List<LineRun>,
    val steps: List<LineRun>,
    val yield: String? = null,
    val prepTime: String? = null,
    val cookTime: String? = null,
    val totalTime: String? = null
)

/**
 * The model names lines, never copies them (#128): the window goes to it with each line
 * numbered ([numbered]), it answers runs of line numbers, and [selection] takes those lines
 * from the window as written. A long recipe's reply stays a few dozen tokens, and a kept line
 * can't be altered or mixed with another. Pure; the iOS app's `PageLines` is the same, pinned by
 * the differential corpus's `Lines` rows.
 */
object PageLines {

    /** Stripped from the start of a line: list bullets and the checkbox a recipe card prints. */
    private const val BULLETS = "•◦▪▫‣⁃●○■□▢☐☑✓✔*·-–—"

    /** [window]'s lines, each after its number in brackets: "[1] Banana Bread\n[2] Ingredients". */
    fun numbered(window: String): String =
        window.split('\n').withIndex().joinToString("\n") { (i, line) -> "[${i + 1}] $line" }

    /**
     * The lines [pick] names in [window], in page order, for [PageRecipeCheck] (which still keeps
     * only one recipe card's). A run outside the window or backwards is no answer, never a guess;
     * a line named as both an ingredient and a step is neither; a leading bullet or checkbox is
     * dropped, and so is a line that is only an ingredients or steps heading ("Ingredients:").
     */
    fun selection(window: String, pick: PagePick): PageSelection {
        val lines = window.split('\n')
        fun numbers(runs: List<LineRun>): Set<Int> = runs
            .filter { it.first in 1..it.last && it.last <= lines.size }
            .flatMap { it.first..it.last }
            .toSortedSet()
        val ingredients = numbers(pick.ingredients)
        val steps = numbers(pick.steps)
        fun text(numbers: Set<Int>): List<String> = numbers
            .filter { it !in ingredients || it !in steps }
            .map { lines[it - 1].trimStart { c -> c.isWhitespace() || c in BULLETS }.trimEnd() }
            .filter { it.isNotEmpty() && !RecipeTextWindow.isBareHeading(it) }
        return PageSelection(
            pick.name, text(ingredients), text(steps),
            pick.yield, pick.prepTime, pick.cookTime, pick.totalTime
        )
    }
}
