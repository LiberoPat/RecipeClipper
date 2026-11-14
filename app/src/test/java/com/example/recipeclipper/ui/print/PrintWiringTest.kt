package com.example.recipeclipper.ui.print

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every route that shows a recipe or a list offers "Print or save as PDF" or "Make a cookbook"
 * (#318): the Week tab's recipe route once missed it (#331). Read from the navigation sources,
 * as `ViewModelImportsTest` reads the ViewModels, so a new route can't leave it out.
 */
class PrintWiringTest {

    @Test fun `every recipe and list route passes the print ViewModel`() {
        // Gradle runs unit tests in the module directory.
        val files = File("src/main/java/com/example/recipeclipper/ui/navigation").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.toList()
        val calls = files.flatMap { file -> calls(file.readText()).map { (name, call) -> "${file.name}: $name" to call } }
        assertTrue("no recipe or list routes found", calls.size >= 4)
        val missing = calls.filter { (_, call) -> WIRED !in call }.map { it.first }
        assertEquals("pass printViewModel = printViewModelIfOn()", emptyList<String>(), missing)
    }

    /** Each `RecipeScreen(...)` or `ListDetailScreen(...)` call in [source], to its closing bracket. */
    private fun calls(source: String): List<Pair<String, String>> = CALL.findAll(source).map { match ->
        var depth = 0
        var end = match.range.last
        while (end < source.length) {
            when (source[end]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            end++
        }
        match.groupValues[1] to source.substring(match.range.first, end + 1)
    }.toList()

    private companion object {
        val CALL = Regex("""(?<!fun )\b(RecipeScreen|ListDetailScreen)\(""")
        const val WIRED = "printViewModel = printViewModelIfOn()"
    }
}
