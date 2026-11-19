package com.example.recipeclipper.ui.askai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every route that shows a recipe offers "Change this recipe" (#353), as `PrintWiringTest` checks
 * for print: read from the navigation sources, so a new route can't leave it out.
 */
class AskAiWiringTest {

    @Test fun `every recipe route passes the Ask an AI ViewModel`() {
        // Gradle runs unit tests in the module directory.
        val files = File("src/main/java/com/example/recipeclipper/ui/navigation").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.toList()
        val calls = files.flatMap { file -> calls(file.readText()).map { call -> file.name to call } }
        assertTrue("no recipe routes found", calls.size >= 3)
        val missing = calls.filter { (_, call) -> WIRED !in call }.map { it.first }
        assertEquals("pass askAiViewModel = askAiViewModelIfOn()", emptyList<String>(), missing)
    }

    /** Each `RecipeScreen(...)` call in [source], to its closing bracket. */
    private fun calls(source: String): List<String> = CALL.findAll(source).map { match ->
        var depth = 0
        var end = match.range.last
        while (end < source.length) {
            when (source[end]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            end++
        }
        source.substring(match.range.first, end + 1)
    }.toList()

    private companion object {
        val CALL = Regex("""(?<!fun )\bRecipeScreen\(""")
        const val WIRED = "askAiViewModel = askAiViewModelIfOn()"
    }
}
