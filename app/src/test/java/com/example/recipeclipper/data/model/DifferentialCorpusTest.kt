package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Keeps iOS's `DifferentialCorpusTests.swift` generated from this Kotlin, and regenerates it.
 *
 * Every `Ing(...)` and `Ins(...)` row in the Swift file is recomputed here from its input
 * string alone, and the whole file is rewritten with the results to
 * `app/build/differential-corpus/DifferentialCorpusTests.swift`. The test fails when that
 * differs from the committed file: a change to the scaler, converters or timers that forgot
 * to regenerate. To regenerate, or to add a row, write just the input (`Ing("1,5 kg flour"),`
 * or `Ins("Bake 1,5 hours."),`) in the Swift file, run this test, and copy the generated file
 * over the Swift one. Never type an expected value by hand.
 *
 * Only these two sections are generated here, plus the Swift test's `systems` list and the
 * header comment naming it, both written from [systems] below. The other sections of the
 * Swift file (stripHtml, yields, URLs, formatting, clocks, JSON-LD) are left exactly as they are.
 */
class DifferentialCorpusTest {

    private val swiftFile = File("../ios/RecipeClipperTests/Model/DifferentialCorpusTests.swift")
    private val outFile = File("build/differential-corpus/DifferentialCorpusTests.swift")

    // Must match the Swift test's `factors`. Its `systems` is written from this list.
    private val factors = listOf(0.5, 1.5, 2.0, 1.0 / 3.0)
    private val systems = listOf(
        UnitSystem.OUNCES to false, UnitSystem.OUNCES to true,
        UnitSystem.METRIC to false, UnitSystem.METRIC to true
    )

    private val row = Regex("""^(\s*)(Ing|Ins)\("((?:[^"\\]|\\.)*)"""")

    // The header comment's "// [ounces, ounces+liquids, ...], then".
    private val systemsComment = Regex("""^// \[[a-z+, ]*], then$""")

    // The body of the Swift `systems` array: "(.ounces, false), (.ounces, true), ...,".
    private val systemsArray = Regex("""^(\s*)(\(\.\w+, (?:true|false)\),\s*)+$""")

    @Test fun `the iOS differential corpus matches the Kotlin`() {
        assumeTrue("no iOS project beside app/", swiftFile.exists())
        val original = swiftFile.readText()
        val regenerated = original.lines().joinToString("\n") { regenerate(it) }
        outFile.parentFile?.mkdirs()
        outFile.writeText(regenerated)
        assertEquals(
            "The corpus is stale. Copy app/${outFile.path} over ios/RecipeClipperTests/Model/" +
                    "DifferentialCorpusTests.swift (it was just generated from the Kotlin).",
            original, regenerated
        )
    }

    private fun regenerate(line: String): String {
        if (systemsComment.matches(line)) {
            return systems.joinToString(", ", "// [", "], then") { (system, liquids) ->
                system.name.lowercase() + if (liquids) "+liquids" else ""
            }
        }
        systemsArray.find(line)?.let { a ->
            return a.groupValues[1] + systems.joinToString(" ") { (system, liquids) ->
                "(.${swiftCase(system)}, $liquids),"
            }
        }
        val m = row.find(line) ?: return line
        val indent = m.groupValues[1]
        val input = unescape(m.groupValues[3])
        return indent + when (m.groupValues[2]) {
            "Ing" -> ingredientRow(input)
            else -> instructionRow(input)
        } + ","
    }

    private fun ingredientRow(line: String): String {
        val scaled = factors.map { IngredientScaler.scale(line, it) }
        val converted = systems.map { (system, liquids) -> UnitConverter.convert(line, system, liquids) }
        // As the reading view renders: scale, then convert with the original line's separator.
        val scaledMetric = IngredientRendering.render(listOf(line), 2.0, UnitSystem.METRIC, false).single()
        val halfOunces = IngredientRendering.render(listOf(line), 0.5, UnitSystem.OUNCES, true).single()
        val name = IngredientName.of(line)?.let { q(it) } ?: "nil"
        return "Ing(${q(line)}, ${list(scaled)}, ${list(converted)}, ${q(scaledMetric)}, ${q(halfOunces)}, $name)"
    }

    private fun instructionRow(line: String): String {
        val celsius = TemperatureConverter.convert(line, TemperatureUnit.CELSIUS)
        val fahrenheit = TemperatureConverter.convert(line, TemperatureUnit.FAHRENHEIT)
        val timer = StepTimers.parse(line)?.toString() ?: "nil"
        return "Ins(${q(line)}, ${q(celsius)}, ${q(fahrenheit)}, $timer)"
    }

    /** AS_WRITTEN is `.asWritten` in Swift. */
    private fun swiftCase(system: UnitSystem): String =
        system.name.lowercase().split('_')
            .mapIndexed { i, word -> if (i == 0) word else word.replaceFirstChar(Char::uppercase) }
            .joinToString("")

    private fun list(items: List<String>) = items.joinToString(", ", "[", "]") { q(it) }

    /** A Swift string literal. */
    private fun q(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\t' -> append("\\t")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                Character.isISOControl(c) || (c != ' ' && Character.isSpaceChar(c)) ||
                        Character.getType(c) == Character.FORMAT.toInt() ->
                    append("\\u{").append(Integer.toHexString(c.code)).append('}')
                else -> append(c)
            }
        }
        append('"')
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') { append(c); i++; continue }
            val next = s[i + 1]
            i += 2
            when (next) {
                't' -> append('\t')
                'n' -> append('\n')
                'r' -> append('\r')
                '0' -> append('\u0000')
                'u' -> {
                    val end = s.indexOf('}', i)
                    appendCodePoint(s.substring(i + 1, end).toInt(16))
                    i = end + 1
                }
                else -> append(next)
            }
        }
    }
}
