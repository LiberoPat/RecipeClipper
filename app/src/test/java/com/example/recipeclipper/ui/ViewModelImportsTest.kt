package com.example.recipeclipper.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CLAUDE.md: ViewModels never import Compose. Found by what a file declares, not its name, so a
 * ViewModel kept beside its views (as `TooltipsViewModel` once was) is caught. iOS's twin is
 * `ViewModelImportsTests`.
 */
class ViewModelImportsTest {

    @Test fun `no file declaring a ViewModel imports Compose`() {
        // Gradle runs unit tests in the module directory.
        val files = File("src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && VIEW_MODEL.containsMatchIn(it.readText()) }
            .toList()
        assertTrue("no ViewModels found", files.size > 5)
        val offenders = files.flatMap { file ->
            file.readLines().filter { FORBIDDEN.matches(it.trim()) }.map { "${file.name}: ${it.trim()}" }
        }
        assertEquals("move the ViewModel to its own file", emptyList<String>(), offenders)
    }

    private companion object {
        val VIEW_MODEL = Regex("""\bclass\s+\w+ViewModel\b""")
        val FORBIDDEN = Regex("""import\s+androidx\.compose\..*""")
    }
}
