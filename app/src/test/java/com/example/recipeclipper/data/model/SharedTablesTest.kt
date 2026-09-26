package com.example.recipeclipper.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every shared table (#9) loads from the classpath, as the app loads it, and is well formed. */
class SharedTablesTest {

    private val languageTables = listOf(
        "densities", "units", "timers", "temperature", "yield", "ranges", "sections",
        "amounts", "durations", "language", "names", "aisles", "steps"
    )

    @Test
    fun everyLanguageOnDiskIsDetectedAndEveryShippedOneHasEveryTable() {
        val languages = File("../shared/tables").listFiles()!!.filter { it.isDirectory }.map { it.name }
        assertEquals(LanguageWords.DETECTED.toSet(), languages.toSet())
        assertTrue(LanguageWords.DETECTED.containsAll(LanguageWords.SHIPPED))
        for (language in languages) {
            val onDisk = File("../shared/tables/$language").list()!!.map { it.removeSuffix(".json") }.toSet()
            val expected = if (language in LanguageWords.SHIPPED) languageTables.toSet() else setOf("language")
            assertEquals(language, expected, onDisk)
            assertEquals(language, language, SharedTables.load("language", language).getString("language"))
        }
    }

    @Test
    fun everyTableLoadsWithItsSchemaVersion() {
        for (language in LanguageWords.SHIPPED) for (name in languageTables) {
            val table = SharedTables.load(name, language)
            assertEquals("$language/$name", 1, table.getInt("schemaVersion"))
            assertEquals("$language/$name", language, table.getString("language"))
        }
        assertEquals(1, SharedTables.read("url").getInt("schemaVersion"))
    }

    @Test
    fun everyUnitNameIsAMeasureUnit() {
        for (language in LanguageWords.SHIPPED) {
            val names = SharedTables.objects(SharedTables.load("units", language).getJSONArray("names"))
            for (name in names) MeasureUnit.valueOf(name.getString("unit"))
        }
        // Every unit is named by some language (cl, dl and VARIES only by non-English ones).
        val named = LanguageWords.SHIPPED.flatMap { language ->
            SharedTables.objects(SharedTables.load("units", language).getJSONArray("names")).map { MeasureUnit.valueOf(it.getString("unit")) }
        }
        assertEquals(MeasureUnit.values().toSet(), named.toSet())
    }

    @Test
    fun everyAisleTableNamesEveryAisleAndNoOther() {
        for (language in LanguageWords.SHIPPED) {
            val aisles = SharedTables.load("aisles", language).getJSONObject("aisles")
            assertEquals(language, Aisle.entries.map { it.key }.toSet(), aisles.keys().asSequence().toSet())
        }
    }

    @Test
    fun everyTimerTableLabelsHoursMinutesAndSeconds() {
        for (language in LanguageWords.SHIPPED) {
            val units = SharedTables.objects(SharedTables.load("timers", language).getJSONArray("units"))
            assertEquals(language, setOf(3600, 60, 1), units.map { it.getInt("seconds") }.toSet())
            for (unit in units) assertTrue(language, unit.getString("label").isNotBlank())
        }
    }

    @Test
    fun theCodeReadsTheTables() {
        assertNotNull(IngredientDensities.find("1 cup flour"))
        assertEquals(MeasureUnit.TBSP, MeasureUnit.fromText("Tbsp."))
        assertEquals(90 * 60, StepTimers.parse("Bake 1 hour and 30 minutes"))
        assertEquals("Preheat to 180°C", TemperatureConverter.convert("Preheat to 350 degrees Fahrenheit", TemperatureUnit.CELSIUS))
        assertEquals(YieldKind.MAKES, Servings.kind("Makes 12"))
        assertEquals("https://a.com/r?id=1", UrlCleaner.clean("https://a.com/r?id=1&utm_source=x&fbclid=y"))
        assertTrue(Regex(UnitPatterns.of().captured).containsMatchIn("2 cups"))
    }
}
